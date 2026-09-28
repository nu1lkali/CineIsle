package app.marlboroadvance.mpvex.domain.emby

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** 单个下载任务的状态机。 */
@Serializable
enum class EmbyDownloadStatus {
  /** 已入队，等待前面的任务让出并发位 */
  QUEUED,

  /** 正在下载 */
  RUNNING,

  /** 暂停（含连接中断），已下字节保留，可从断点续传 */
  PAUSED,

  /** 下载完成，文件已发布到公共 Download 目录 */
  COMPLETED,

  /** 失败（网络/服务器错误），可重试 */
  FAILED,
}

/**
 * 一条下载任务。
 *
 * 这个对象会整体序列化成 JSON 存进 SharedPreferences，所以字段都要能序列化；
 * 运行期才算得出来的东西（速度）用 [Transient] 标掉。
 */
@Serializable
data class EmbyDownloadTask(
  /** Emby 的 ItemId，同时作为任务主键 */
  val itemId: String,
  /** 所属服务器 id，用于展示与后续重登校验 */
  val serverId: Long,
  /** 展示用标题（片名或剧集名） */
  val title: String,
  /** 落盘文件名（含扩展名） */
  val fileName: String,
  /** 下载地址（已带 api_key，可直接用 OkHttp 拉） */
  val url: String,
  /** 文件总大小；-1 表示未知（响应头没给） */
  val totalBytes: Long = -1L,
  /** 已下载字节数 */
  val downloadedBytes: Long = 0L,
  val status: EmbyDownloadStatus = EmbyDownloadStatus.QUEUED,
  /** 附加说明（失败原因 / 中断提示），成功时为 null */
  val message: String? = null,
  /** 落盘位置：content:// uri（MediaStore）或 file:// uri（旧系统回落） */
  val targetUri: String? = null,
  /** 给人看的位置文本，如 Download/CineIsle/xxx.mkv */
  val locationLabel: String? = null,
  val createdAt: Long = System.currentTimeMillis(),
  /** 实时速度（字节/秒）。只活在内存里，不落盘，所以标 [Transient]。 */
  @Transient val speedBps: Long = 0L,
) {
  /** 进度 0..1；总大小未知时返回 null（界面走不确定进度动画） */
  val progressFraction: Float?
    get() = if (totalBytes > 0 && downloadedBytes > 0) {
      (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
      null
    }

  val isActive: Boolean
    get() = status == EmbyDownloadStatus.RUNNING || status == EmbyDownloadStatus.QUEUED
}

/** [EmbyDownloadManager.enqueue] 的结果，界面据此给出不同提示。 */
enum class EmbyEnqueueResult {
  STARTED,
  /** 已在队列里（未完成） */
  EXISTS,
  /** 已经下载过了 */
  COMPLETED,
  /** 该条目不可下载（没有 ItemId） */
  INVALID,
}

/**
 * Emby 离线下载管理器。
 *
 * 为什么不用系统 [android.app.DownloadManager]：它没有公开的暂停/继续 API，
 * 而本项目的下载管理页明确要求「暂停下载 / 继续下载」。所以这里自己基于
 * OkHttp + HTTP Range 实现断点续传：
 *
 * - 写盘：Android 10+ 通过 MediaStore 直接写**公共 Download/CineIsle 目录**
 *   （IS_PENDING 占位，下载完再发布），不需要任何存储权限；
 *   Android 9 及以下回落到应用外部私有目录（系统不提供无权限写公共目录的能力）。
 * - 暂停：直接把已下字节留在目标文件里，取消网络请求即可。
 * - 继续：新请求带 `Range: bytes=<已有大小>-`，以**追加**模式接着写。
 * - 进度：字节数写进 StateFlow，界面按 400ms 粒度刷新，5s 落一次盘。
 *
 * 任务元数据持久化在 SharedPreferences，进程被杀后重新打开应用，
 * 未完成的任务会停在「已暂停」，可以手动继续。
 */
class EmbyDownloadManager(context: Context) {
  private val appContext = context.applicationContext
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val client = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

  private val _tasks = MutableStateFlow<List<EmbyDownloadTask>>(emptyList())
  val tasks: StateFlow<List<EmbyDownloadTask>> = _tasks.asStateFlow()

  private val jobs = HashMap<String, Job>()
  private val calls = HashMap<String, Call>()

  /** 速度采样：[已写字节, 采样时间, 平滑速度] */
  private val speedTrack = HashMap<String, LongArray>()

  private var lastPersistAt = 0L

  init {
    restore()
  }

  // ──────────────────────────── 对外操作 ────────────────────────────

  fun taskFor(itemId: String): EmbyDownloadTask? = _tasks.value.firstOrNull { it.itemId == itemId }

  /**
   * 把一条媒体加入下载队列。
   *
   * 同一 ItemId 只会有一条任务：已经在队列里、或者已经下好了，都会如实返回，
   * 避免用户连点两下下两份。
   */
  fun enqueue(server: EmbyServer, item: EmbyItem): EmbyEnqueueResult {
    val itemId = item.Id ?: return EmbyEnqueueResult.INVALID

    val existing = taskFor(itemId)
    if (existing != null) {
      return if (existing.status == EmbyDownloadStatus.COMPLETED) {
        EmbyEnqueueResult.COMPLETED
      } else {
        EmbyEnqueueResult.EXISTS
      }
    }

    val task = EmbyDownloadTask(
      itemId = itemId,
      serverId = server.id,
      title = item.Name ?: item.SeriesName ?: "未命名",
      fileName = buildFileName(item),
      url = EmbyClient.itemDownloadUrl(server, itemId),
      totalBytes = item.MediaSources?.firstOrNull()?.Size ?: -1L,
    )
    _tasks.value = _tasks.value + task
    persist()
    pump()
    return EmbyEnqueueResult.STARTED
  }

  /** 暂停。已下字节全部保留，之后可 [resume] 续传。 */
  fun pause(itemId: String) {
    val task = taskFor(itemId) ?: return
    if (!task.isActive) return

    // 先改状态再取消网络请求：否则 OkHttp 抛出的 IOException 会先跑到
    // 异常处理分支，把任务误判成 FAILED。
    update(itemId) { it.copy(status = EmbyDownloadStatus.PAUSED) }
    persist()
    calls.remove(itemId)?.cancel()
    jobs.remove(itemId)?.cancel()
    speedTrack.remove(itemId)
    pump()
  }

  /** 继续（或对失败任务重试）。 */
  fun resume(itemId: String) {
    val task = taskFor(itemId) ?: return
    if (task.status != EmbyDownloadStatus.PAUSED && task.status != EmbyDownloadStatus.FAILED) return
    update(itemId) { it.copy(status = EmbyDownloadStatus.QUEUED, message = null) }
    persist()
    pump()
  }

  fun pauseAll() {
    _tasks.value.filter { it.isActive }.forEach { pause(it.itemId) }
  }

  fun resumeAll() {
    _tasks.value.filter { it.status == EmbyDownloadStatus.PAUSED }.forEach { resume(it.itemId) }
  }

  /**
   * 从列表里移除任务。
   *
   * @param deleteFile 半成品/已下好的文件是否一起删掉（取消下载时 true，清理记录时 false）
   */
  fun remove(itemId: String, deleteFile: Boolean = false) {
    val task = taskFor(itemId)
    calls.remove(itemId)?.cancel()
    jobs.remove(itemId)?.cancel()
    speedTrack.remove(itemId)
    _tasks.value = _tasks.value.filterNot { it.itemId == itemId }
    persist()
    if (deleteFile) task?.targetUri?.let { runCatching { deleteTarget(Uri.parse(it)) } }
    pump()
  }

  /** 清掉所有已完成任务的记录（文件保留在 Download 目录里）。 */
  fun clearCompleted() {
    _tasks.value = _tasks.value.filterNot { it.status == EmbyDownloadStatus.COMPLETED }
    persist()
  }

  // ──────────────────────────── 调度 ────────────────────────────

  private fun pump() {
    if (_tasks.value.count { it.status == EmbyDownloadStatus.RUNNING } >= MAX_CONCURRENT) return
    val next = _tasks.value
      .filter { it.status == EmbyDownloadStatus.QUEUED }
      .minByOrNull { it.createdAt }
      ?: return
    start(next.itemId)
  }

  private fun start(itemId: String) {
    if (jobs[itemId]?.isActive == true) return
    update(itemId) { it.copy(status = EmbyDownloadStatus.RUNNING, message = null) }

    jobs[itemId] = scope.launch {
      try {
        download(itemId)
      } catch (cancel: CancellationException) {
        // 暂停/取消：状态由调用方设置，这里不要覆盖
        throw cancel
      } catch (t: Throwable) {
        update(itemId) { current ->
          if (current.status == EmbyDownloadStatus.PAUSED) {
            current
          } else {
            current.copy(
              status = EmbyDownloadStatus.FAILED,
              message = t.message ?: t.javaClass.simpleName,
            )
          }
        }
        persist()
      } finally {
        calls.remove(itemId)
        speedTrack.remove(itemId)
        update(itemId) { it.copy(speedBps0()) }
        pump()
      }
    }
  }

  // ──────────────────────────── 下载主流程 ────────────────────────────

  private suspend fun download(itemId: String) {
    var task = taskFor(itemId) ?: return

    // 1. 目标文件：没有就先建一个（MediaStore 公共目录 / 旧系统私有目录）
    val target = task.targetUri?.let { Uri.parse(it) }
      ?: createTarget(task.fileName).also { uri ->
        task = task.copy(
          targetUri = uri.toString(),
          locationLabel = labelOf(uri, task.fileName),
        )
        replace(task)
        persist()
      }

    // 2. 断点：以目标文件的实际大小为续传起点
    val existing = sizeOf(target)

    val requestBuilder = Request.Builder().url(task.url)
    if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")

    val call = client.newCall(requestBuilder.build())
    calls[itemId] = call

    call.execute().use { response ->
      if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
      val body = response.body ?: throw IOException("响应为空")

      val partial = response.code == 206
      val offset = if (partial) existing else 0L
      val total = totalFrom(response, offset).takeIf { it > 0 } ?: task.totalBytes

      if (total != task.totalBytes) {
        update(itemId) { it.copy(totalBytes = total) }
      }

      var written = offset
      val out = openWriter(target, append = partial && offset > 0)
      try {
        val input = body.byteStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var lastTick = 0L
        while (true) {
          coroutineContext.ensureActive()
          val read = input.read(buffer)
          if (read <= 0) break
          out.write(buffer, 0, read)
          written += read

          val now = System.currentTimeMillis()
          if (now - lastTick >= PROGRESS_TICK_MS) {
            lastTick = now
            reportProgress(itemId, written, total)
          }
        }
        out.flush()
      } finally {
        runCatching { out.close() }
      }

      if (total > 0 && written < total) {
        // 服务器只给了一半就断流（比如 Wi-Fi 掉了）：留在 PAUSED，可以接着下
        update(itemId) {
          it.copy(
            status = EmbyDownloadStatus.PAUSED,
            downloadedBytes = written,
            message = "连接已断开，可继续下载",
          )
        }
        persist()
        return
      }

      publish(target)
      update(itemId) {
        it.copy(
          status = EmbyDownloadStatus.COMPLETED,
          downloadedBytes = maxOf(written, total),
          message = null,
          locationLabel = it.locationLabel ?: labelOf(target, it.fileName),
        )
      }
      persist()
    }
  }

  // ──────────────────────────── 落盘细节 ────────────────────────────

  /**
   * 在公共 Download/CineIsle 下建目标文件。
   *
   * Android 10+ 用 MediaStore，并以 `IS_PENDING=1` 占位——这样下载中的半成品
   * 不会出现在相册/文件管理器里；下载完成后再置 0 发布出去。
   */
  private fun createTarget(fileName: String): Uri {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(fileName))
        put(
          MediaStore.MediaColumns.RELATIVE_PATH,
          "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR",
        )
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
      return appContext.contentResolver
        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("无法在 Download 目录创建文件")
    }

    // Android 9 及以下：MediaStore.Downloads 不存在，直接写公共目录又需要存储权限，
    // 这里回落到应用外部私有目录（零权限），至少保证功能可用。
    val dir = legacyDir()
    if (!dir.exists()) dir.mkdirs()
    return Uri.fromFile(File(dir, uniqueName(dir, fileName)))
  }

  private fun legacyDir(): File {
    val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
      ?: appContext.filesDir
    return File(base, SUBDIR)
  }

  private fun openWriter(uri: Uri, append: Boolean): OutputStream {
    val raw = if (uri.scheme == "content") {
      appContext.contentResolver.openOutputStream(uri, if (append) "wa" else "w")
        ?: throw IOException("无法打开下载文件")
    } else {
      val file = File(uri.path ?: throw IOException("下载路径无效"))
      file.parentFile?.mkdirs()
      FileOutputStream(file, append)
    }
    return BufferedOutputStream(raw, BUFFER_SIZE)
  }

  /** 目标文件当前实际大小（字节）。用 fstat 而不是 MediaStore 的 SIZE 列，准。 */
  private fun sizeOf(uri: Uri): Long = runCatching {
    if (uri.scheme == "content") {
      appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    } else {
      File(uri.path ?: return 0L).takeIf { it.exists() }?.length() ?: 0L
    }
  }.getOrDefault(0L)

  /** 下载完成：把 MediaStore 里那条 pending 记录发布出去。 */
  private fun publish(uri: Uri) {
    if (uri.scheme == "content") {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
          val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
          appContext.contentResolver.update(uri, values, null, null)
        }
      }
    } else {
      uri.path?.let { path ->
        runCatching { MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null) }
      }
    }
  }

  private fun deleteTarget(uri: Uri) {
    if (uri.scheme == "content") {
      runCatching { appContext.contentResolver.delete(uri, null, null) }
    } else {
      uri.path?.let { runCatching { File(it).delete() } }
    }
  }

  private fun labelOf(uri: Uri, fileName: String): String = if (uri.scheme == "content") {
    "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/$fileName"
  } else {
    uri.path ?: fileName
  }

  // ──────────────────────────── 进度 / 持久化 ────────────────────────────

  private fun reportProgress(itemId: String, written: Long, total: Long) {
    val speed = sampleSpeed(itemId, written)
    _tasks.value = _tasks.value.map {
      if (it.itemId == itemId) {
        it.copy(downloadedBytes = written, totalBytes = total, speedBps = speed)
      } else {
        it
      }
    }

    // 进度刷新很密集，落盘按 5 秒节流，避免把 SharedPreferences 写爆
    val now = System.currentTimeMillis()
    if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
      lastPersistAt = now
      persist()
    }
  }

  /** 指数平滑瞬时速度，避免数字乱跳。 */
  private fun sampleSpeed(itemId: String, written: Long): Long {
    val now = System.currentTimeMillis()
    val previous = speedTrack[itemId]
    if (previous == null) {
      speedTrack[itemId] = longArrayOf(written, now, 0L)
      return 0L
    }
    val elapsed = now - previous[1]
    if (elapsed < SPEED_WINDOW_MS) return previous[2]
    val instant = (written - previous[0]) * 1000L / elapsed
    val smoothed = if (previous[2] == 0L) instant else (previous[2] * 3L + instant) / 4L
    speedTrack[itemId] = longArrayOf(written, now, smoothed)
    return smoothed
  }

  private fun update(itemId: String, block: (EmbyDownloadTask) -> EmbyDownloadTask) {
    _tasks.value = _tasks.value.map { if (it.itemId == itemId) block(it) else it }
  }

  private fun replace(task: EmbyDownloadTask) {
    _tasks.value = _tasks.value.map { if (it.itemId == task.itemId) task else it }
  }

  private fun persist() {
    runCatching {
      prefs.edit().putString(KEY_TASKS, json.encodeToString(_tasks.value)).apply()
    }
  }

  private fun restore() {
    runCatching {
      val raw = prefs.getString(KEY_TASKS, null) ?: return@runCatching
      val saved = json.decodeFromString<List<EmbyDownloadTask>>(raw)
      // 进程重启后不可能还有活跃连接：原来的 RUNNING/QUEUED 一律降级成「已暂停」，
      // 用户点「继续」即可从断点续传。
      _tasks.value = saved.map {
        if (it.isActive) it.copy(status = EmbyDownloadStatus.PAUSED) else it
      }
    }
  }

  // ──────────────────────────── 杂项工具 ────────────────────────────

  /** 从响应头推断文件总大小（优先 Content-Range 的 total）。 */
  private fun totalFrom(response: okhttp3.Response, offset: Long): Long {
    response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
      ?.let { if (it > 0) return it }
    val length = response.body?.contentLength() ?: -1L
    return when {
      length <= 0 -> -1L
      offset > 0 || response.code == 206 -> offset + length
      else -> length
    }
  }

  /** 剧集用「剧名 S01E05 - 集名」，电影直接用片名。 */
  private fun buildFileName(item: EmbyItem): String {
    val rawTitle = if (item.Type == "Episode" && !item.SeriesName.isNullOrBlank()) {
      val season = item.ParentIndexNumber
      val episode = item.IndexNumber
      val tag = if (season != null && episode != null) "S%02dE%02d ".format(season, episode) else ""
      "${item.SeriesName} $tag- ${item.Name.orEmpty()}".trim()
    } else {
      item.Name ?: item.SeriesName ?: item.Id ?: "video"
    }

    val extension = item.MediaSources?.firstOrNull()?.Container?.takeIf { it.isNotBlank() }
      ?: item.Path?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
      ?: "mkv"

    return "${sanitize(rawTitle)}.${extension.lowercase()}"
  }

  private fun sanitize(raw: String): String {
    val cleaned = raw
      .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
      .trim()
      .trimEnd('.')
    val bounded = if (cleaned.length > MAX_NAME_LENGTH) {
      cleaned.substring(0, MAX_NAME_LENGTH).trim()
    } else {
      cleaned
    }
    return bounded.ifEmpty { "video" }
  }

  /** 旧系统回落路径下避免同名覆盖（MediaStore 自己会去重，不需要这个）。 */
  private fun uniqueName(dir: File, fileName: String): String {
    if (!File(dir, fileName).exists()) return fileName
    val dot = fileName.lastIndexOf('.')
    val stem = if (dot > 0) fileName.substring(0, dot) else fileName
    val ext = if (dot > 0) fileName.substring(dot) else ""
    var index = 1
    while (true) {
      val candidate = "$stem ($index)$ext"
      if (!File(dir, candidate).exists()) return candidate
      index++
    }
  }

  private fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts", "m2ts" -> "video/mp2t"
    "flv" -> "video/x-flv"
    "webm" -> "video/webm"
    "wmv" -> "video/x-ms-wmv"
    "mpg", "mpeg" -> "video/mpeg"
    else -> "video/*"
  }

  companion object {
    private const val PREFS_NAME = "emby_downloads"
    private const val KEY_TASKS = "tasks_json"

    /** 同时下载数上限，太小跑不满带宽，太大容易被服务器限流 */
    private const val MAX_CONCURRENT = 2
    private const val BUFFER_SIZE = 256 * 1024
    private const val PROGRESS_TICK_MS = 400L
    private const val PERSIST_INTERVAL_MS = 5_000L
    private const val SPEED_WINDOW_MS = 800L
    private const val MAX_NAME_LENGTH = 120

    /** 公共 Download 目录下的子目录名 */
    const val SUBDIR = "CineIsle"
  }
}
