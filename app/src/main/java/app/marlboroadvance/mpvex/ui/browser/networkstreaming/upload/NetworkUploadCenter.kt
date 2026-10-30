package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.AlistClient
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 一个上传任务的状态机。`Cancelled` 与 `Failed` 分开，是因为 UI 文案完全不同 */
enum class UploadTaskState { Pending, Uploading, Success, Failed, Cancelled }

/**
 * 队列里的一条任务（一个文件）。
 *
 * @param relativePath **相对上传根**的路径，文件夹上传时带子目录（如 `DCIM/2024/a.jpg`），
 *   文件多选时就是文件名。UI 显示、服务器落盘路径都以它为准。
 * @param previewUri 本地源地址，进度页的小窗预览用它。相册来源是 `content://`，
 *   文件/文件夹来源是 `file://`。为 null 时不显示缩略图（例如以后加入的流式来源）。
 */
data class UploadTask(
  val id: Int,
  val relativePath: String,
  val sizeBytes: Long,
  val sentBytes: Long = 0,
  val state: UploadTaskState = UploadTaskState.Pending,
  val error: String? = null,
  val previewUri: Uri? = null,
  val isVideo: Boolean = false,
) {
  val progress: Float
    get() =
      when {
        state == UploadTaskState.Success -> 1f
        sizeBytes <= 0 -> 0f
        else -> (sentBytes.toFloat() / sizeBytes).coerceIn(0f, 1f)
      }
}

/**
 * 一批上传（一次「点上传」= 一批）。
 *
 * @param id 单调递增的批次号。**所有状态更新都拿它对账** —— 用户连点两次上传时，
 *   上一批的收尾（`finally`）会晚于新批次的创建，没有这个 id 就会把新批次标成
 *   「已完成」，进度条瞬间归零。
 */
data class UploadBatch(
  val id: Long,
  val connectionId: Long,
  val targetDir: String,
  val targetLabel: String,
  val tasks: List<UploadTask>,
  val running: Boolean,
) {
  val totalBytes: Long get() = tasks.sumOf { it.sizeBytes }
  val sentBytes: Long get() = tasks.sumOf { it.sentBytes }
  val successCount: Int get() = tasks.count { it.state == UploadTaskState.Success }
  val failedCount: Int get() = tasks.count { it.state == UploadTaskState.Failed }
  val cancelledCount: Int get() = tasks.count { it.state == UploadTaskState.Cancelled }
  val finishedCount: Int get() = successCount + failedCount + cancelledCount

  /** 按**字节**算的总进度。全部文件都是 0 字节时退化为按条数算，免得永远停在 0 */
  val progress: Float
    get() =
      when {
        !running && finishedCount == tasks.size -> 1f
        totalBytes > 0 -> (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        tasks.isEmpty() -> 0f
        else -> finishedCount.toFloat() / tasks.size
      }
}

/**
 * 待上传的一个本地文件 + 它在上传根下的相对路径。
 *
 * ## 为什么有两个数据来源
 *
 * 文件选择器给出的是 [File]；相册选择器给出的是 MediaStore 条目 —— 它的
 * `content://` URI 在 scoped storage 下比 `_data` 路径可靠得多（`_data` 可能为空）。
 * 本应用虽然持有 `MANAGE_EXTERNAL_STORAGE`、两条路通常都通，但**不能假定**：
 * 个别 ROM 会对自身以外写入的条目返回空 `_data`。所以两条都留着，
 * [open] 优先走 URI，没有 URI 才走 File。
 */
data class UploadPick(
  val file: File?,
  val relativePath: String,
  /** 相册来源的 `content://` 地址。为 null 时走 [file] */
  val contentUri: Uri? = null,
  /**
   * MediaStore 已经给过的字节数。
   * 纯内存值，**不触发任何 IO** —— [resolveSize] 才会去 stat。
   */
  val sizeHint: Long = -1L,
) {
  /** 用于界面展示的大小；拿不到就是 0（总进度会退化为按条数算） */
  val sizeBytes: Long
    get() = if (sizeHint > 0) sizeHint else (file?.length() ?: 0L)

  /**
   * 上传时真正使用的字节数。
   *
   * ⚠️ 必须在 IO 线程调用：`sizeHint` 为空时要开 FileDescriptor 去 stat。
   * 这个值会被写进 `Content-Length` —— 给 0 会让 AList 落一个空文件，
   * 所以这里宁可多问一次系统。
   */
  fun resolveSize(context: Context): Long =
    sizeBytes.takeIf { it > 0 }
      ?: contentUri?.let { uri ->
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
          }
          .getOrDefault(0L)
      }
      ?: 0L

  /** 打开数据源。每次重试都会重新调一次（上一次的流已经写光了） */
  fun open(context: Context): InputStream =
    contentUri?.let { uri ->
      context.contentResolver.openInputStream(uri) ?: error("无法读取：$relativePath")
    }
      ?: file?.inputStream()
      ?: error("无法读取：$relativePath")
}

/** 扫描一个本地文件夹的结果。[truncated] = 文件数超上限、只收了前 [MAX_FOLDER_FILES] 个 */
data class FolderScanResult(val picks: List<UploadPick>, val truncated: Boolean)

/**
 * 本地 → AList 的上传队列（**进程级单例**）。
 *
 * ## 为什么不是 ViewModel 持有
 *
 * 上传是分钟级的动作，而浏览页的 ViewModel **按目录建实例**（`NetworkBrowser_连接_路径`
 * 作为 key）。用户传着 2GB 的文件往下翻目录、进播放器，ViewModel 随时会被回收 ——
 * 队列必须活在进程上，界面只是它的一个观察者。这也顺带解释了为什么这里用
 * `GlobalContext` 取仓库而不是构造注入：它没有 Composable 生命周期可言。
 *
 * ## 为什么串行
 *
 * 1. AList 后端对同一个存储的并发写不友好，SMB/FTP 底层驱动更甚；
 * 2. 并发上传时分不清「哪个文件的进度」，界面上会变成一堆跳来跳去的进度条；
 * 3. 顺序上传的总体耗时与并发几乎无差（瓶颈在服务端落盘与上行带宽）。
 *
 * ## 状态更新为什么要节流
 *
 * [AlistClient.upload] 每 64KB 回调一次 —— 一个 2GB 文件就是 3 万多次回调。
 * 每次都 `_batch.update` 会让整张列表重组 3 万次（帧率会碎掉，且每次都要
 * 复制一份 tasks 列表）。这里按 100ms 节流，进度条看起来依然是连续的。
 */
object NetworkUploadCenter {
  private const val TAG = "NetworkUploadCenter"

  /** 进度推送的最小间隔（毫秒），见类 KDoc 的节流说明 */
  private const val PROGRESS_THROTTLE_MS = 100L

  /**
   * 单个文件夹最多收多少个文件。
   *
   * 5000 是本机目录树的实际上限（一个相册文件夹几百张，一部剧几十集）：
   * 再多说明用户误选了存储根目录 —— 那时候「扫描 20 分钟然后传 3 天」比
   * 「明确告诉你太多了」糟得多。超了截断并在界面上标出（见 [FolderScanResult]）。
   */
  const val MAX_FOLDER_FILES = 5000

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val batchSeq = AtomicLong(0)

  private val _batch = MutableStateFlow<UploadBatch?>(null)

  /** 当前（或最近一次）批次。界面直接 `collectAsState()` 即可 */
  val batch: StateFlow<UploadBatch?> = _batch.asStateFlow()

  private val _completed = MutableSharedFlow<Long>(extraBufferCapacity = 4)

  /**
   * 一批上传成功结束时发一次（值为 connectionId）。
   *
   * 浏览页收到它就重新列一次当前目录 —— 传完的文件必须**立刻出现**在列表里，
   * 否则用户以为没传上去、又点一次。
   */
  val completed: SharedFlow<Long> = _completed.asSharedFlow()

  private val jobRef = AtomicReference<Job?>(null)

  /**
   * 当前批次的取消令牌。
   *
   * ⚠️ **不能用一个全局 boolean**：用户「取消 → 立刻重新上传」时，上一批的
   * `isCancelled` 会读到被重置后的 false，而它正卡在阻塞的 `read()` 里 ——
   * 结果是**两批一起往服务器写**。令牌跟着批次走就没这个问题。
   */
  private val tokenRef = AtomicReference<CancelToken?>(null)

  private class CancelToken {
    @Volatile var cancelled = false
  }

  /**
   * 选择器上次停留的本地目录（进程内记忆）。
   *
   * 不做持久化：传文件是「一次连传好几个」的场景，同一次会话里记住就够；
   * 存进偏好反而会出现「上次停在 Download，这次想从相册传」的多余导航。
   */
  @Volatile
  var lastLocalDir: String? = null

  /**
   * 选择器上次用的模式（进程内记忆，同 [lastLocalDir]）。
   *
   * 每一次都从「相册」起步，对「我就是来传某个文件夹」的人是每天多两次点击；
   * 而真正天天传相册的人也只会多看到一次别的档位 —— 记住上次的选择更划算。
   */
  @Volatile
  internal var lastMode: UploadMode? = null

  val running: Boolean get() = _batch.value?.running == true

  /**
   * 开一批上传。
   *
   * @param context 只用来读 `content://`（相册来源）。内部立刻取 applicationContext，
   *   不会泄露 Activity。
   * @param picks 已经展开好的文件表（文件夹由 [scanFolder] 展开）
   */
  fun start(
    context: Context,
    connectionId: Long,
    targetDir: String,
    targetLabel: String,
    picks: List<UploadPick>,
  ) {
    if (picks.isEmpty()) return
    // 上一批还在跑：先请它停（新任务优先，不排队 —— 排队会让用户以为点了没反应）
    tokenRef.get()?.cancelled = true
    jobRef.getAndSet(null)?.cancel()

    val token = CancelToken()
    tokenRef.set(token)

    val id = batchSeq.incrementAndGet()
    val tasks =
      picks.mapIndexed { index, pick ->
        UploadTask(
          id = index,
          relativePath = pick.relativePath,
          sizeBytes = pick.sizeBytes,
          // 进度页的小窗预览要用本地源地址：相册来源是 content://（比 _data 路径可靠），
          // 文件/文件夹来源是 file://
          previewUri = pick.contentUri ?: pick.file?.let { Uri.fromFile(it) },
          isVideo = FileTypeUtils.isVideoExtension(pick.relativePath.substringAfterLast('.', "")),
        )
      }
    _batch.value =
      UploadBatch(
        id = id,
        connectionId = connectionId,
        targetDir = targetDir,
        targetLabel = targetLabel,
        tasks = tasks,
        running = true,
      )

    val app = context.applicationContext
    jobRef.set(scope.launch { runBatch(app, id, token, connectionId, targetDir, picks) })
  }
  /** 取消当前批次。已传完的保留（服务器上就是有了），未开始的标成已取消 */
  fun cancel() {
    if (!running) return
    tokenRef.get()?.cancelled = true
  }

  /** 清掉已完成批次的记录（界面关掉时用，避免下次打开还看到上一批的进度） */
  fun clearFinished() {
    if (running) return
    _batch.value = null
  }

  // ── 执行 ──

  private suspend fun runBatch(
    app: Context,
    batchId: Long,
    token: CancelToken,
    connectionId: Long,
    targetDir: String,
    picks: List<UploadPick>,
  ) {
    try {
      val koin = GlobalContext.getOrNull() ?: error("Koin 未初始化")
      val repository: NetworkRepository = koin.get()
      val connection = repository.getConnectionById(connectionId)
        ?: error("连接不存在（可能已删除）")
      val client =
        repository.clientFor(connection) as? AlistClient
          ?: error("当前协议不支持上传（只有 AList 有写接口）")

      // ① 先建齐子目录（文件夹上传时相对路径里带层级）。
      //    ⚠️ mkdir 失败**不中断**：目标目录已存在时 AList 会报「文件已存在」，
      //    那不是错误。真正的判据是随后的 put —— 目录没建出来，put 自己会报。
      collectDirs(picks).forEach { sub ->
        val full = join(targetDir, sub)
        client.mkdir(full).onFailure { Log.d(TAG, "mkdir 忽略（可能已存在）: $full | ${it.message}") }
      }

      picks.forEachIndexed { index, pick ->
        if (token.cancelled) return@forEachIndexed
        updateTask(batchId, index) { it.copy(state = UploadTaskState.Uploading) }

        // 文件落在「目标目录 + 它在选择里的父目录」下：选整个 DCIM 时不能把几百张
        // 照片平铺进当前目录，那会把用户的目标目录搞乱。
        val parentRel = pick.relativePath.substringBeforeLast('/', "")
        val uploadDir = if (parentRel.isEmpty()) targetDir else join(targetDir, parentRel)

        var lastEmit = 0L
        val result =
          client.upload(
            targetDir = uploadDir,
            name = pick.relativePath.substringAfterLast('/'),
            // 相册来源的 sizeHint 可能为 0（异常的媒体索引），此时 resolveSize 会去 stat。
            // 给 0 会让服务端落一个空文件，所以这里必须是真实字节数。
            length = pick.resolveSize(app),
            contentType = null,
            open = { pick.open(app) },
            onProgress = { sent ->
              val now = System.currentTimeMillis()
              if (now - lastEmit >= PROGRESS_THROTTLE_MS) {
                lastEmit = now
                updateTask(batchId, index) { it.copy(sentBytes = sent) }
              }
            },
            isCancelled = { token.cancelled },
          )

        result
          .onSuccess {
            updateTask(batchId, index) {
              it.copy(state = UploadTaskState.Success, sentBytes = it.sizeBytes)
            }
          }
          .onFailure { e ->
            updateTask(batchId, index) {
              if (token.cancelled || e is CancellationException) {
                it.copy(state = UploadTaskState.Cancelled)
              } else {
                it.copy(state = UploadTaskState.Failed, error = e.message ?: "上传失败")
              }
            }
          }
        if (token.cancelled) return@forEachIndexed
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "上传批次异常终止", e)
      // 整批级失败（Koin / 连接 / 协议不支持）：把还没跑的任务全部标成失败，
      // 界面上必须有个明确的结局，不能让进度条永远转下去。
      _batch.update { current ->
        if (current == null || current.id != batchId) {
          current
        } else {
          current.copy(
            running = false,
            tasks =
              current.tasks.map {
                if (it.state == UploadTaskState.Pending || it.state == UploadTaskState.Uploading) {
                  it.copy(state = UploadTaskState.Failed, error = e.message ?: "上传异常")
                } else {
                  it
                }
              },
          )
        }
      }
    } finally {
      // ⚠️ 这段在协程被取消时也会执行，所以只能做非挂起操作
      _batch.update { current ->
        if (current == null || current.id != batchId) {
          current
        } else {
          current.copy(
            running = false,
            tasks =
              current.tasks.map {
                if (it.state == UploadTaskState.Pending || it.state == UploadTaskState.Uploading) {
                  it.copy(state = UploadTaskState.Cancelled)
                } else {
                  it
                }
              },
          )
        }
      }
      val finished = _batch.value
      if (!token.cancelled && finished?.id == batchId && finished.successCount > 0) {
        _completed.tryEmit(connectionId)
      }
    }
  }

  private fun updateTask(batchId: Long, index: Int, transform: (UploadTask) -> UploadTask) {
    _batch.update { current ->
      if (current == null || current.id != batchId) {
        current
      } else {
        val tasks = current.tasks.toMutableList()
        val old = tasks.getOrNull(index) ?: return@update current
        tasks[index] = transform(old)
        current.copy(tasks = tasks)
      }
    }
  }

  /** 从相对路径里反推出所有需要的子目录（`a/b/c.jpg` → `a`、`a/b`），按层级从浅到深 */
  private fun collectDirs(picks: List<UploadPick>): List<String> =
    buildList {
        picks.forEach { pick ->
          val parts = pick.relativePath.trim('/').split('/')
          var acc = ""
          for (i in 0 until parts.size - 1) {
            acc = if (acc.isEmpty()) parts[i] else "$acc/${parts[i]}"
            add(acc)
          }
        }
      }
      .distinct()
      .sortedBy { it.count { c -> c == '/' } }

  private fun join(dir: String, sub: String): String {
    val base = dir.trim('/')
    return if (base.isEmpty()) sub else "$base/$sub"
  }

  // ── 文件夹展开 ──

  /**
   * 把一个本地文件夹递归展开成待上传文件表。
   *
   * - 相对路径以**文件夹本身**为根：选 `DCIM` 会在服务器上建 `目标/DCIM/...`；
   * - 隐藏文件（`.` 开头）跳过：与浏览页、选择器的口径一致；
   * - 空文件夹不会被上传（目录是从文件路径反推出来的，见 [collectDirs]）——
   *   相册场景里没有意义，真需要的话手动建一下更快；
   * - 到达 [MAX_FOLDER_FILES] 即停并标记 [FolderScanResult.truncated]。
   */
  suspend fun scanFolder(root: File): FolderScanResult =
    withContext(Dispatchers.IO) {
      val picks = ArrayList<UploadPick>(256)
      val rootName = root.name.ifBlank { "upload" }
      var truncated = false

      fun walk(dir: File, prefix: String) {
        if (truncated) return
        val children =
          try {
            dir.listFiles()
          } catch (_: Exception) {
            null
          } ?: return
        // 先文件后目录：文件列表在前，扫到上限时至少保证「同一层的文件是齐的」
        children.filter { it.isFile && !it.name.startsWith(".") }.forEach { file ->
          if (picks.size >= MAX_FOLDER_FILES) {
            truncated = true
            return
          }
          picks.add(UploadPick(file = file, relativePath = "$prefix/${file.name}"))
        }
        children
          .filter { it.isDirectory && !it.name.startsWith(".") && it.canRead() }
          .forEach { sub -> walk(sub, "$prefix/${sub.name}") }
      }

      walk(root, rootName)
      FolderScanResult(picks = picks, truncated = truncated)
    }
}
