package app.marlboroadvance.mpvex.ui.browser.networkstreaming.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.repository.NetworkRepository
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** 一次下载的状态机。`Scanning` 只出现在「打包下载目录」——那一步要先列一遍全树。 */
enum class NetworkDownloadState { Scanning, Running, Success, Failed, Cancelled }

/**
 * 下载进度（**进程级**，见 [NetworkDownloadCenter]）。
 *
 * @param doneBytes 已写入字节（含所有已完成文件）
 * @param totalBytes 扫描阶段估出来的总字节；单文件时直接取 `NetworkFile.size`，
 *   拿到 0 说明服务端没给大小（部分 WebDAV），此时进度条退化为**不定态**。
 * @param failures 跳过的文件（打不开流）。列出来是为了让用户知道「这个压缩包里少了什么」，
 *   只报一句「部分失败」等于什么都没说。
 * @param outputLabel 落盘位置（`Download/CineIsle/xxx`），成功后才填 —— 用户下一步就是去这儿找文件。
 */
data class NetworkDownloadProgress(
  val title: String,
  val isDirectory: Boolean,
  val state: NetworkDownloadState,
  val phase: String,
  val currentFile: String = "",
  val totalFiles: Int = 0,
  val doneFiles: Int = 0,
  val totalBytes: Long = 0,
  val doneBytes: Long = 0,
  val failures: List<String> = emptyList(),
  val outputLabel: String? = null,
  val errorMessage: String? = null,
  val speedBps: Long = 0,
) {
  val running: Boolean
    get() = state == NetworkDownloadState.Scanning || state == NetworkDownloadState.Running

  /** 0..1；总大小未知时恒 0，界面据此显示不定态进度条 */
  val progress: Float
    get() =
      when {
        state == NetworkDownloadState.Success -> 1f
        totalBytes > 0 -> (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        else -> 0f
      }

  val indeterminate: Boolean get() = totalBytes <= 0 && running
}

/**
 * 网络文件 / 目录 → 本地 Download 目录（**进程级单例**）。
 *
 * ## 为什么又是进程级
 *
 * 和 [app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.NetworkUploadCenter]
 * 同一个理由：浏览页的 ViewModel **按目录建实例**，用户下一层目录、进播放器，
 * 下载就该继续。队列活在进程上，界面只是观察者。
 *
 * ## 落盘位置
 *
 * 与 Emby 下载共用一套约定（见 `EmbyDownloadManager.createTarget`）：
 * Android 10+ 走 `MediaStore.Downloads` + `IS_PENDING=1` 占位（下载中的半成品
 * 不出现在文件管理器里），完成后置 0 发布；10 以下回落到应用外部私有目录。
 * 统一落在 `Download/CineIsle/`。
 *
 * ## 目录为什么打成 zip
 *
 * AList 侧没有「下载目录」这种接口，只能一个个文件拉。几十上百个小文件散在
 * Download 里既难找也难搬；一个 zip 交给系统文件管理器解压是用户预期内的行为。
 *
 * ## 失败策略（关键）
 *
 * - **单文件**：失败即整单失败（删除半成品，不留一个大小不对的文件骗人）。
 * - **目录**：某个文件**流打不开**时跳过它并记账（常见于网盘里失效的条目）
 *   —— 一个坏文件不该废掉整包；但**拷贝中途**出错必须立刻中止整包：zip 的条目
 *   是按顺序追加的，中途断掉会让这个条目只写了一半字节，产出一个**打不开的压缩包**，
 *   那比明说「失败了」糟得多。
 */
object NetworkDownloadCenter {
  private const val TAG = "NetworkDownloadCenter"

  /** 落盘子目录（与 Emby 下载同一个） */
  private const val SUBDIR = "CineIsle"

  private const val BUFFER_SIZE = 256 * 1024

  /** 进度推送间隔：256KB 一次回调、2GB 就是 8000 多次，不节流会把 UI 重组碎掉 */
  private const val PROGRESS_THROTTLE_MS = 200L

  /** 打包上限：超出就截断并在结果里说明（不静默） */
  private const val MAX_FILES = 3000

  private const val MAX_DEPTH = 16

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _job = MutableStateFlow<NetworkDownloadProgress?>(null)

  /** 当前（或最近一次）下载。关掉面板也继续跑，重开能看到进度 */
  val job: StateFlow<NetworkDownloadProgress?> = _job.asStateFlow()

  private var runner: Job? = null

  /**
   * 取消令牌。⚠️ 不能只靠 `Job.cancel()`：拷贝循环卡在阻塞的 `read()` 里时，
   * 取消要等下个数据块才生效；令牌让每一圈都能立刻看到「用户点了取消」。
   */
  private val tokenRef = AtomicReference<CancelToken?>(null)

  private class CancelToken {
    @Volatile var cancelled = false
  }

  /** 上一次请求（「失败后重试」要用；也方便界面显示源条目名） */
  private var lastRequest: Request? = null

  private data class Request(
    val context: Context,
    val connection: NetworkConnection,
    val file: NetworkFile,
  )

  /** 是否正在下载（UI 拿来 disable 入口，避免连点两次） */
  val busy: Boolean get() = runner?.isActive == true

  /**
   * 开始下载。
   *
   * @return false = 已有任务在跑（调用方 toast 提示即可，不排队：同时往
   *   Download 里写两个大文件，用户根本分不清哪个进度条是谁的）
   */
  fun start(
    context: Context,
    connection: NetworkConnection,
    file: NetworkFile,
  ): Boolean {
    if (busy) return false
    val koin = GlobalContext.getOrNull() ?: return false
    val repository: NetworkRepository = koin.get()
    val appContext = context.applicationContext
    val request = Request(appContext, connection, file)
    lastRequest = request

    val token = CancelToken()
    tokenRef.set(token)
    _job.value =
      NetworkDownloadProgress(
        title = file.name,
        isDirectory = file.isDirectory,
        state = if (file.isDirectory) NetworkDownloadState.Scanning else NetworkDownloadState.Running,
        phase = if (file.isDirectory) "正在扫描目录…" else "准备下载…",
        // 单文件的大小一开始就知道 —— 不填的话进度条会是「不定态」，
        // 明明有分母却显示成一条转圈的光条。目录的总量要等扫描完才知道。
        totalFiles = if (file.isDirectory) 0 else 1,
        totalBytes = if (file.isDirectory) 0 else file.size.coerceAtLeast(0),
      )

    runner =
      scope.launch {
        try {
          if (file.isDirectory) {
            runDirectory(request, repository, token)
          } else {
            runSingle(request, repository, token)
          }
        } catch (e: CancellationException) {
          _job.value =
            _job.value?.copy(
              state = NetworkDownloadState.Cancelled,
              phase = "已取消",
              currentFile = "",
              speedBps = 0,
            )
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "下载失败: ${file.path} | ${e.message}")
          _job.value =
            _job.value?.copy(
              state = NetworkDownloadState.Failed,
              phase = "下载失败",
              currentFile = "",
              speedBps = 0,
              errorMessage = e.message ?: "下载失败",
            )
        }
      }
    return true
  }

  /** 用户点取消（做到哪儿算哪儿，半成品会删掉） */
  fun cancel() {
    tokenRef.get()?.cancelled = true
    runner?.cancel()
  }

  /** 面板 / 提示条关掉时清状态；任务还在跑就保留（与工具箱同一纪律） */
  fun clear() {
    if (!busy) _job.value = null
  }

  /** 失败 / 取消后重来一次（源条目还在） */
  fun retry(context: Context): Boolean {
    val request = lastRequest ?: return false
    if (busy) return false
    return start(context, request.connection, request.file)
  }

  // ──────────────────────────── 单文件 ────────────────────────────

  private suspend fun runSingle(
    request: Request,
    repository: NetworkRepository,
    token: CancelToken,
  ) {
    val file = request.file
    val target = createTarget(request.context, file.name, mimeOf(file.name))
    var published = false
    try {
      val size = file.size.coerceAtLeast(0)
      val input =
        repository.openFileStream(request.connection, file.path, file.sign).getOrThrow()
      val meter = Meter(size) { done, speed -> publishProgress(done, speed) }
      input.use { source ->
        openWriter(request.context, target.uri).use { sink ->
          val written = copy(source, sink, token) { done -> meter.report(done) }
          // 服务端报的大小和实际字节不一致时不报错（有些网盘 size 是估算），
          // 但让进度条走到 100% —— 否则界面停在 97% 而文件其实已经完整
          meter.finish(written)
        }
      }
      publish(request.context, target.uri)
      published = true
      _job.value =
        _job.value?.copy(
          state = NetworkDownloadState.Success,
          phase = "已保存",
          doneFiles = 1,
          totalFiles = 1,
          doneBytes = meter.total,
          totalBytes = if (meter.total > 0) meter.total else _job.value?.totalBytes ?: 0,
          outputLabel = target.label,
          currentFile = "",
          speedBps = 0,
        )
    } finally {
      // 没走到 publish 就是失败/取消：把占位的半成品删掉，别在 Download 里留个假文件
      if (!published) deleteTarget(request.context, target.uri)
    }
  }

  // ──────────────────────────── 目录（打包） ────────────────────────────

  private suspend fun runDirectory(
    request: Request,
    repository: NetworkRepository,
    token: CancelToken,
  ) {
    val dir = request.file
    val client = repository.clientFor(request.connection)

    // ① 先扫一遍，拿到「要打包哪些文件、总共多大」——进度条没有分母就没意义
    val entries = mutableListOf<WalkEntry>()
    var totalBytes = 0L
    var truncated = false
    val root = dir.path.trim('/')

    suspend fun walk(
      path: String,
      prefix: String,
      depth: Int,
    ) {
      if (token.cancelled) throw CancellationException("已取消")
      if (depth > MAX_DEPTH) {
        truncated = true
        return
      }
      val children =
        try {
          client.listFiles(path).getOrThrow()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // 某一级列不出来：跳过这一支（与工具箱 / 目录统计同一纪律）
          Log.w(TAG, "目录列不出来，跳过：$path | ${e.message}")
          truncated = true
          return
        }
      _job.value = _job.value?.copy(phase = "正在扫描目录…", currentFile = prefix.ifEmpty { "/" })
      for (child in children) {
        if (child.isDirectory) {
          walk(
            path = child.path.trim('/'),
            prefix = if (prefix.isEmpty()) child.name else "$prefix/${child.name}",
            depth = depth + 1,
          )
        } else {
          if (entries.size >= MAX_FILES) {
            truncated = true
            continue
          }
          entries +=
            WalkEntry(
              remotePath = child.path,
              entryName = if (prefix.isEmpty()) child.name else "$prefix/${child.name}",
              size = child.size.coerceAtLeast(0),
              sign = child.sign,
            )
          totalBytes += child.size.coerceAtLeast(0)
        }
      }
    }

    walk(root, "", 0)

    if (entries.isEmpty()) {
      throw IOException("这个目录里没有可下载的文件")
    }

    _job.value =
      _job.value?.copy(
        state = NetworkDownloadState.Running,
        phase = "正在打包…",
        totalFiles = entries.size,
        totalBytes = totalBytes,
        doneFiles = 0,
        doneBytes = 0,
      )

    val target = createTarget(request.context, "${dir.name.ifBlank { "download" }}.zip", "application/zip")
    var published = false
    try {
      val meter = Meter(totalBytes) { done, speed ->
        publishProgress(done, speed, entryName = _job.value?.currentFile)
      }
      openWriter(request.context, target.uri, buffered = false).use { raw ->
        ZipOutputStream(BufferedOutputStream(raw, BUFFER_SIZE)).use { zip ->
          // 媒体文件本来就是压缩过的，用最快档位（level 1）：
          // 默认级别会把 CPU 全吃在这上面，而体积几乎不变
          zip.setLevel(Deflater.BEST_SPEED)
          // ⚠️ `copy` 每个文件都从 0 开始数，而 `totalBytes` 是**整包**的总量 ——
          //    必须自己累加已完成的字节，否则进度条每换一个文件就跳回 0。
          var written = 0L
          for (entry in entries) {
            if (token.cancelled) throw CancellationException("已取消")
            _job.value = _job.value?.copy(currentFile = entry.entryName)
            val stream =
              try {
                repository.openFileStream(request.connection, entry.remotePath, entry.sign).getOrThrow()
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                // 流**打不开** ⇒ 这一个条目还没写任何字节进 zip，跳过是安全的
                Log.w(TAG, "跳过打不开的文件: ${entry.remotePath} | ${e.message}")
                recordFailure(entry.entryName)
                continue
              }
            val base = written
            stream.use { source ->
              zip.putNextEntry(ZipEntry(entry.entryName))
              // 这里失败**不能** continue：条目已经写了一部分，继续拖下去就是一个坏包
              written += copy(source, zip, token) { done -> meter.report(base + done) }
              zip.closeEntry()
            }
            _job.value = _job.value?.copy(doneFiles = (_job.value?.doneFiles ?: 0) + 1)
          }
        }
      }
      publish(request.context, target.uri)
      published = true
      val failures = _job.value?.failures.orEmpty()
      val note = buildList {
        if (truncated) add("文件数超过 $MAX_FILES，只打包了前 $MAX_FILES 个")
        if (failures.isNotEmpty()) add("跳过 ${failures.size} 个打不开的文件")
      }
      _job.value =
        _job.value?.copy(
          state = NetworkDownloadState.Success,
          phase = "已保存",
          doneBytes = meter.total.coerceAtLeast(totalBytes),
          totalBytes = totalBytes,
          outputLabel = target.label,
          currentFile = "",
          speedBps = 0,
          errorMessage = note.takeIf { it.isNotEmpty() }?.joinToString("；"),
        )
    } finally {
      if (!published) deleteTarget(request.context, target.uri)
    }
  }

  private fun recordFailure(name: String) {
    val current = _job.value ?: return
    _job.value = current.copy(failures = (current.failures + name).take(20))
  }

  private data class WalkEntry(
    val remotePath: String,
    val entryName: String,
    val size: Long,
    val sign: String?,
  )

  // ──────────────────────────── 进度 ────────────────────────────

  /**
   * 节流 + 测速。
   *
   * 速度用「两次上报之间」算，再和上一轮做 0.3 / 0.7 的滑动平均 ——
   * 网盘抖一下就跳到 0 或几百 MB/s 的数字没有意义。
   */
  private class Meter(
    initialTotal: Long,
    private val onReport: (done: Long, speed: Long) -> Unit,
  ) {
    var total: Long = initialTotal
      private set

    private var lastEmitMs = 0L
    private var lastBytes = 0L
    private var speed = 0L

    fun report(done: Long) {
      val now = System.currentTimeMillis()
      if (now - lastEmitMs < PROGRESS_THROTTLE_MS) return
      val deltaMs = now - lastEmitMs
      val deltaBytes = done - lastBytes
      if (deltaMs > 0) {
        val instant = (deltaBytes * 1000L) / deltaMs
        speed = if (speed <= 0) instant else (speed * 7 + instant * 3) / 10
      }
      lastEmitMs = now
      lastBytes = done
      onReport(done, speed)
    }

    fun finish(written: Long) {
      if (written > total) total = written
      onReport(total, 0)
    }
  }

  private fun publishProgress(
    doneBytes: Long,
    speed: Long,
    entryName: String? = null,
  ) {
    val current = _job.value ?: return
    _job.value =
      current.copy(
        doneBytes = doneBytes,
        speedBps = speed,
        currentFile = entryName ?: current.currentFile,
      )
  }

  // ──────────────────────────── 拷贝 ────────────────────────────

  private suspend fun copy(
    input: InputStream,
    output: OutputStream,
    token: CancelToken,
    onBytes: (Long) -> Unit,
  ): Long {
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    while (true) {
      if (token.cancelled) throw CancellationException("已取消")
      val read = input.read(buffer)
      if (read < 0) break
      if (read == 0) continue
      output.write(buffer, 0, read)
      total += read
      onBytes(total)
    }
    return total
  }

  // ──────────────────────────── 落盘 ────────────────────────────

  private class Target(
    val uri: Uri,
    val label: String,
  )

  /**
   * 在公共 `Download/CineIsle` 下建目标文件（与 Emby 下载同一套）。
   *
   * Android 10+ 用 MediaStore + `IS_PENDING=1` 占位：下载中的半成品对文件管理器
   * 不可见，重名时系统自己会加「(1)」后缀（于是这里回查一次真实文件名，
   * 结果里给用户看的必须是**真实**路径）。
   */
  private fun createTarget(
    context: Context,
    fileName: String,
    mime: String,
  ): Target {
    // 网盘里的名字可以直接带 `:` `?` `*`（Linux 侧合法），落到 FAT/exFAT 上会被系统
    // 静默改名甚至插入失败 —— 先自己洗一遍，用户看到的名字才是可预期的
    val safeName = safeFileName(fileName)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values =
        ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
          put(MediaStore.MediaColumns.MIME_TYPE, mime)
          put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR",
          )
          put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
      val uri =
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          ?: throw IOException("无法在 Download 目录创建文件")
      return Target(
        uri,
        labelOf(context, uri) ?: "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/$safeName",
      )
    }

    val dir = legacyDir(context)
    if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建目录 ${dir.absolutePath}")
    val file = File(dir, uniqueName(dir, safeName))
    return Target(Uri.fromFile(file), file.absolutePath)
  }

  /** 洗掉在 FAT/exFAT 上非法的字符（Linux 侧这些都合法，跨过来就得处理） */
  private fun safeFileName(name: String): String {
    val cleaned = name.map { if (it in "\\/:*?\"<>|") '_' else it }.joinToString("")
    return cleaned.trim().trimEnd('.').ifEmpty { "download" }
  }

  /** 回查 MediaStore 实际落下的文件名（重名时系统会改名） */
  private fun labelOf(
    context: Context,
    uri: Uri,
  ): String? =
    runCatching {
        context.contentResolver
          .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
          ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
          }
      }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }
      ?.let { "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/$it" }

  private fun legacyDir(context: Context): File {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    return File(base, SUBDIR)
  }

  /** `name.ext` 已存在就试 `name(1).ext` —— 与工具箱移动撞名时的命名一致 */
  private fun uniqueName(
    dir: File,
    fileName: String,
  ): String {
    if (!File(dir, fileName).exists()) return fileName
    val base = fileName.substringBeforeLast('.', fileName)
    val ext = fileName.substringAfterLast('.', "")
    val suffix = if (ext.isEmpty()) "" else ".$ext"
    var index = 1
    while (index < 1000) {
      val candidate = "$base($index)$suffix"
      if (!File(dir, candidate).exists()) return candidate
      index++
    }
    return "$base(${System.currentTimeMillis()})$suffix"
  }

  private fun openWriter(
    context: Context,
    uri: Uri,
    buffered: Boolean = true,
  ): OutputStream {
    val raw =
      if (uri.scheme == "content") {
        context.contentResolver.openOutputStream(uri) ?: throw IOException("无法写入下载文件")
      } else {
        val file = File(uri.path ?: throw IOException("下载路径无效"))
        file.parentFile?.mkdirs()
        file.outputStream()
      }
    return if (buffered) BufferedOutputStream(raw, BUFFER_SIZE) else raw
  }

  /** 完成：发布 MediaStore 里那条 pending 记录（或让 MediaScanner 认一下私有目录里的文件） */
  private fun publish(
    context: Context,
    uri: Uri,
  ) {
    if (uri.scheme == "content") {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
          val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
          context.contentResolver.update(uri, values, null, null)
        }
      }
    } else {
      uri.path?.let { path ->
        runCatching {
          android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
      }
    }
  }

  private fun deleteTarget(
    context: Context,
    uri: Uri,
  ) {
    if (uri.scheme == "content") {
      runCatching { context.contentResolver.delete(uri, null, null) }
    } else {
      uri.path?.let { runCatching { File(it).delete() } }
    }
  }

  /** 打包 / 保存用的 MIME（MediaStore 只用来分类，给不准也不影响写入） */
  private fun mimeOf(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
      "mp4", "m4v" -> "video/mp4"
      "mkv" -> "video/x-matroska"
      "avi" -> "video/x-msvideo"
      "mov" -> "video/quicktime"
      "ts", "m2ts", "mts" -> "video/mp2t"
      "mp3" -> "audio/mpeg"
      "flac" -> "audio/flac"
      "wav" -> "audio/wav"
      "m4a" -> "audio/mp4"
      "jpg", "jpeg" -> "image/jpeg"
      "png" -> "image/png"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      "zip" -> "application/zip"
      "txt", "nfo", "srt", "ass" -> "text/plain"
      "pdf" -> "application/pdf"
      else -> "application/octet-stream"
    }
}
