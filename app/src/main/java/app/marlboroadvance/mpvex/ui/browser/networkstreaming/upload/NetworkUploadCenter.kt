package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.AlistClient
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * 手机里那份原文件的下场（v192）。
 *
 * 和 [UploadTaskState] 是**两件事**：上传成功不等于本地还在，本地被删也不代表上传失败。
 * 合并成一个状态机的话，「已上传但没删」「已上传且已删」「上传成功但没删掉（失败）」
 * 三种情况会被压成两种，界面就没法如实告诉用户手机里到底还剩没剩。
 */
enum class LocalCopyState {
  /** 还在手机上（默认；没开自动删除、或者这一条还没轮到） */
  Kept,

  /** 已经删掉了 */
  Deleted,

  /** 想删但没删成（权限、外置卡、文件被占用…）—— 文件还在，可以再试 */
  DeleteFailed,
}

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
  /**
   * 这条是**用户单独取消的**（不是取消整批）。
   *
   * 和 `Cancelled` 分不开的话，界面上「你只取消了这一条」和「你取消了整批」会长得一样，
   * 而两者的下一步完全不同：前者只是这一条没传（重试它就好），
   * 后者是整批都不要了（不该再提示重试）。
   */
  val cancelledByUser: Boolean = false,
  /**
   * 相册来源的 `content://`，删除本机文件时用它。
   *
   * ⚠️ 不能只靠 [previewUri] 反推：`Uri.fromFile()` 在路径含中文/空格/特殊字符时
   * 来回转换容易出岔子，而删除是不可逆的 —— 建任务时多存两个引用，比事后猜安全得多。
   */
  val contentUri: Uri? = null,
  /** 文件/文件夹来源，删除本机文件时用它 */
  val localFile: File? = null,
  /**
   * 服务端回查过字节数（[AlistClient.UploadVerify.Confirmed]）。
   *
   * **只有它为 true 才允许删本地** —— 「fs/put 返回 200」和「服务器上确实有这么大一个
   * 文件」是两件事，拿前者当依据删本地，等于赌驱动不会骗人。
   */
  val verified: Boolean = false,
  /** 手机里那份原文件的下场（与上传状态无关，见 [LocalCopyState]） */
  val localCopy: LocalCopyState = LocalCopyState.Kept,
  /** 删除失败的原因（[localCopy] 为 [LocalCopyState.DeleteFailed] 时有值） */
  val localDeleteError: String? = null,
) {
  val progress: Float
    get() =
      when {
        state == UploadTaskState.Success -> 1f
        sizeBytes <= 0 -> 0f
        else -> (sentBytes.toFloat() / sizeBytes).coerceIn(0f, 1f)
      }

  /**
   * 这一条现在能不能删手机里的原文件。
   *
   * 四个条件缺一不可：上传成功、服务端确认过字节数、本地还没删、本地来源还在。
   * 失败 / 取消的行**永远不能删** —— 那时本地是唯一副本，而且重试还要靠它重新 open()。
   *
   * ⚠️ [LocalCopyState.DeleteFailed] 也算「能删」：自动删除失败的那一条必须还能在结果页
   * 里手动补删一次。否则「自动删失败」就是死局 —— 文件明明还在手机上，却没有任何入口再试。
   */
  val canDeleteLocal: Boolean
    get() =
      state == UploadTaskState.Success &&
        verified &&
        localCopy != LocalCopyState.Deleted &&
        (contentUri != null || localFile != null)
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

  /**
   * 「还要传多少字节」—— 只算**还没结束**的任务。
   *
   * 不能用 `totalBytes - sentBytes`：失败/取消的任务永远不会再涨，那部分字节会
   * 一直挂在「剩余」里 ⇒ 剩余时间永远收不到 0（失败一个 2GB 的文件，界面就敢
   * 告诉你「剩余约 5 分钟」挂到最后）。
   */
  val remainingBytes: Long
    get() =
      tasks.sumOf {
        when (it.state) {
          UploadTaskState.Pending -> it.sizeBytes
          UploadTaskState.Uploading -> (it.sizeBytes - it.sentBytes).coerceAtLeast(0L)
          else -> 0L
        }
      }

  val successCount: Int get() = tasks.count { it.state == UploadTaskState.Success }
  val failedCount: Int get() = tasks.count { it.state == UploadTaskState.Failed }
  val cancelledCount: Int get() = tasks.count { it.state == UploadTaskState.Cancelled }

  /** 用户单独取消的条数（取消整批的那部分不算，见 [UploadTask.cancelledByUser]） */
  val cancelledByUserCount: Int get() = tasks.count { it.state == UploadTaskState.Cancelled && it.cancelledByUser }
  val finishedCount: Int get() = successCount + failedCount + cancelledCount

  /** 还能重试的条数：失败 + 用户单独取消的（整批取消的不算，那是「我不要了」） */
  val retryableCount: Int
    get() = tasks.count { it.state == UploadTaskState.Failed || (it.state == UploadTaskState.Cancelled && it.cancelledByUser) }

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
 * 实时速度与剩余时间（纯展示量）。
 *
 * **不放进 [UploadBatch]**：每一秒往批次里塞一次会让整张任务列表重组（列表有几十行时
 * 每秒几十次无谓重组）。单独一条流，只有「这一行文字」的两个观察者会动：
 * 进度面板的那行小字、以及通知栏。
 *
 * @param etaSeconds null = 还不可信（起播头两秒）或完全没在动
 */
data class UploadStats(val speedBps: Long = 0L, val etaSeconds: Long? = null)

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

  private val _stats = MutableStateFlow(UploadStats())

  /**
   * 实时速度 / 剩余时间。**由中心统一采样**（而不是进度面板自己数）：
   * 面板关掉、应用退到后台时，通知栏还要继续更新同一套数字 —— 两处各采一次
   * 只会得到两个互相打架的「剩余时间」。
   */
  val stats: StateFlow<UploadStats> = _stats.asStateFlow()

  /**
   * 被要求「单独取消」的任务号（正在传的那些）。
   *
   * 待传的可以直接标成「已取消」，但**已经在传的那一条**没法从外面掐断 ——
   * 它的取消判据在 `AlistClient.upload` 的写循环里，只能靠这个集合让它自己退出。
   */
  private val cancelIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

  private val jobRef = AtomicReference<Job?>(null)

  /**
   * 最近一批的「原始原料」：应用上下文 + 待传清单。
   *
   * 只为 [retryFailed] 服务 —— `UploadBatch` 里只有已经展开成任务的结果（路径/大小/预览地址），
   * 没有可以重新打开的数据源（`UploadPick` 持有 `content://` 与 `File`）。
   * 不保留就重试不了，除非让用户重新选一遍文件，那就不叫「重试」了。
   */
  private val appContextRef = AtomicReference<Context?>(null)
  private val lastPicksRef = AtomicReference<List<UploadPick>>(emptyList())

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

  // ────────────────────── 本机副本（v192）──────────────────────

  private const val SETTINGS_PREFS = "upload_settings"
  private const val KEY_AUTO_DELETE = "auto_delete_after_upload"
  private const val KEY_WARNED = "auto_delete_warned"

  private val _autoDelete = MutableStateFlow(false)

  /**
   * 「上传成功后删除本机原文件」开关。
   *
   * 默认**关**，而且是刻意的：删掉本地之后云盘就是唯一副本 —— 账号出问题、文件被审核
   * 删掉、空间超限被清理，任何一样都会直接变成数据丢失。这种事只能由用户主动选择。
   */
  val autoDeleteAfterUpload: StateFlow<Boolean> = _autoDelete.asStateFlow()

  @Volatile private var settingsLoaded = false

  /** 从落盘读一次开关状态。幂等 —— 面板每次打开都会调 */
  fun loadSettings(context: Context) {
    if (settingsLoaded) return
    val prefs = context.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    _autoDelete.value = prefs.getBoolean(KEY_AUTO_DELETE, false)
    settingsLoaded = true
  }

  fun setAutoDelete(context: Context, enabled: Boolean) {
    _autoDelete.value = enabled
    prefs(context).edit().putBoolean(KEY_AUTO_DELETE, enabled).apply()
  }

  /** 首次打开开关时的说明只弹一次 —— 之后用户已经知道代价，再拦就是打扰 */
  fun autoDeleteWarned(context: Context): Boolean = prefs(context).getBoolean(KEY_WARNED, false)

  fun markAutoDeleteWarned(context: Context) {
    prefs(context).edit().putBoolean(KEY_WARNED, true).apply()
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

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
    cancelIds.clear()

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
          // 删本地要靠它们 —— 不能从 previewUri 反推（见 UploadTask.contentUri 的注释）
          contentUri = pick.contentUri,
          localFile = pick.file,
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
    _stats.value = UploadStats()

    val app = context.applicationContext
    appContextRef.set(app)
    lastPicksRef.set(picks)
    // ① 队列落盘：进程被系统回收后还能问一句「上次还有 12 个没传完，继续吗」
    UploadQueueStore.save(app, connectionId, targetDir, targetLabel, picks)
    // ② 前台服务：锁屏 / 切走后系统不再当它是「可随时回收的后台任务」，
    //    同时把进度摆进通知栏（见 UploadForegroundService）
    UploadForegroundService.start(app)
    launchRunner(app, id, token, connectionId, targetDir, picks.mapIndexed { index, pick -> index to pick })
  }

  /**
   * 重试**失败 / 跳过**的那几条（成功与「取消整批」的不再传一遍）。
   *
   * ⚠️ 在**同一批**里重跑，不新开批次：
   * 新开批次会让整块进度页的统计清零（已经成功的几十个文件从列表里消失），
   * 而且分享页靠 `batch.id` 判断「这一批是不是我开的」，一换 id 就会被踢回选择页。
   *
   * 拿不到上下文（进程被回收后又被页面唤起）或没有可重试项时静默返回 ——
   * 这属于「没什么可重试」而不是错误，不该弹提示。
   */
  fun retryFailed() = retryTasks(null)

  /** 只重试某一条（进度页每行的「重试」） */
  fun retryTask(taskId: Int) = retryTasks(setOf(taskId))

  private fun retryTasks(only: Set<Int>?) {
    val current = _batch.value ?: return
    if (current.running) return
    val ctx = appContextRef.get() ?: return
    val picks = lastPicksRef.get()
    val ids =
      current.tasks
        .filter {
          (only == null || it.id in only) &&
            (it.state == UploadTaskState.Failed ||
              (it.state == UploadTaskState.Cancelled && it.cancelledByUser))
        }
        .map { it.id }
    if (ids.isEmpty()) return
    // 用上一批的**原始 picks**按下标挑：任务里没有可重新打开的数据源
    val items = ids.mapNotNull { id -> picks.getOrNull(id)?.let { id to it } }
    if (items.isEmpty()) return

    val token = CancelToken()
    tokenRef.set(token)
    cancelIds.clear()
    _batch.value =
      current.copy(
        running = true,
        tasks =
          current.tasks.map {
            if (it.id in ids) {
              it.copy(
                state = UploadTaskState.Pending,
                sentBytes = 0,
                error = null,
                cancelledByUser = false,
              )
            } else {
              it
            }
          },
      )
    _stats.value = UploadStats()
    // 只把「还没传完的」写进落盘队列：这一批里已成功的不该再出现在恢复清单里
    // （写全量的话，进程在重试途中被回收，恢复时会连已传完的一起重传一遍）
    persistLeftover(ctx, current.connectionId, current.targetDir, current.targetLabel)
    UploadForegroundService.start(ctx)
    launchRunner(ctx, current.id, token, current.connectionId, current.targetDir, items)
  }

  /**
   * 取消**单个**文件的上传（整批还在跑的时候用）。
   *
   * - 待传的：直接标成「已取消」（队列还没碰它，立刻生效）；
   * - 正在传的：塞进 [cancelIds]，`AlistClient.upload` 下一次检查时自己退出。
   *   ⚠️ 不能在这里直接把状态改成 Cancelled —— 写循环还在跑，它会紧接着把
   *   success/failed 写回来，界面上就会出现「已取消」和「已完成」互相覆盖。
   *
   * ⚠️ 已经在传的那一条被掐断后，**服务端那半截文件由 `AlistClient.upload` 自己清掉**
   * （见那里的「没传完就把残缺文件删掉」）。所以这一条之后要重传就得从头来。
   *
   * @return 是否真的动了状态（界面上要据此关掉确认框）
   */
  fun cancelTask(taskId: Int): Boolean {
    val current = _batch.value ?: return false
    if (!current.running) return false
    val task = current.tasks.getOrNull(taskId) ?: return false
    return when (task.state) {
      UploadTaskState.Pending -> {
        updateTask(current.id, taskId) {
          it.copy(state = UploadTaskState.Cancelled, cancelledByUser = true, sentBytes = 0)
        }
        true
      }
      UploadTaskState.Uploading -> {
        cancelIds.add(taskId)
        true
      }
      else -> false
    }
  }

  /** 有没有可重试的失败项（界面据此决定显不显示「重试」按钮） */
  val canRetry: Boolean
    get() {
      val current = _batch.value ?: return false
      return !current.running && current.retryableCount > 0 && appContextRef.get() != null
    }

  /**
   * 取消这一批上传。
   *
   * ## 为什么没有「暂停」
   *
   * AList 的 `fs/put` 是「服务端从头往目标路径写一条完整流」，**没有断点续传**：
   * 一旦停下，已经传上去的那部分字节在服务端就没有任何凭据可以接上（详见
   * [AlistClient.upload] 的 KDoc）。所以「暂停 / 继续」在这里是假的 ——
   * 恢复必然整份重传，叫「暂停」只会让人以为回头能接着传。
   * 现在一律叫「取消」，并在界面上**先警告、再执行**。
   *
   * 语义：已传完的保留（服务器上就是有了），没传完的标成已取消；
   * 正在传的那一个由写循环中断，服务端残留的半截文件由 `AlistClient.upload` 清掉。
   */
  fun cancel() {
    val current = _batch.value ?: return
    if (!current.running) return
    cancelIds.clear()
    // 运行器还活着：让它自己走 settle 收尾（那里才有落盘与通知的完整处理）
    tokenRef.get()?.cancelled = true
  }

  /** 清掉已完成批次的记录（界面关掉时用，避免下次打开还看到上一批的进度） */
  fun clearFinished() {
    if (running) return
    _batch.value = null
  }

  // ── 执行 ──

  /**
   * 起一条运行器 + 一个统计采样协程。
   *
   * 统计与传输**分开**：传输的 `finally` 里要立刻把状态收尾（界面不能卡在「正在上传」），
   * 而统计每秒写一次、由 `stats` 流单独承担；两者生命周期用同一个 job 绑住，
   * 传输结束统计自然停（否则后台会有一个每秒钟醒一次的协程跟着进程活到天荒地老）。
   *
   * @param items `任务号 to 原始 file` —— 重试时只带其中几条，任务号必须原样保留
   *   （进度页按 id 定位那一行；重新编号会把进度写到别的文件上）
   */
  private fun launchRunner(
    app: Context,
    batchId: Long,
    token: CancelToken,
    connectionId: Long,
    targetDir: String,
    items: List<Pair<Int, UploadPick>>,
  ) {
    jobRef.set(
      scope.launch {
        val ticker = launch { trackStats(batchId) }
        try {
          runTasks(app, batchId, token, connectionId, targetDir, items)
        } finally {
          ticker.cancel()
          settle(batchId, token, connectionId)
        }
      },
    )
  }

  /** 每秒采一次，写进 [stats]。数据不够（起播头两秒）时速度给 0、剩余时间给 null */
  private suspend fun trackStats(batchId: Long) {
    val meter = UploadSpeedometer()
    // 先记起点：否则第一个点在 1 秒后才落，要等到第 3 秒才凑够跨度
    _batch.value?.takeIf { it.id == batchId }?.let {
      meter.sample(SystemClock.elapsedRealtime(), it.sentBytes)
    }
    while (true) {
      delay(1000)
      val now = _batch.value ?: break
      if (now.id != batchId || !now.running) break
      meter.sample(SystemClock.elapsedRealtime(), now.sentBytes)
      _stats.value = UploadStats(meter.speedBps, meter.etaSeconds(now.remainingBytes))
    }
    _stats.value = UploadStats()
  }

  private suspend fun runTasks(
    app: Context,
    batchId: Long,
    token: CancelToken,
    connectionId: Long,
    targetDir: String,
    items: List<Pair<Int, UploadPick>>,
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
      collectDirs(items.map { it.second }).forEach { sub ->
        val full = join(targetDir, sub)
        client.mkdir(full).onFailure { Log.d(TAG, "mkdir 忽略（可能已存在）: $full | ${it.message}") }
      }

      for ((index, pick) in items) {
        // 整批被取消：队列停在这里，剩下的由 settle 标成「已取消」
        if (token.cancelled) return
        // 排队期间被单独取消的：不碰网络，直接越过
        val queued = _batch.value?.tasks?.getOrNull(index)
        if (queued == null || queued.state == UploadTaskState.Cancelled) continue
        cancelIds.remove(index)
        updateTask(batchId, index) {
          it.copy(state = UploadTaskState.Uploading, sentBytes = 0, error = null)
        }

        // 文件落在「目标目录 + 它在选择里的父目录」下：选整个 DCIM 时不能把几百张
        // 照片平铺进当前目录，那会把用户的目标目录搞乱。
        val parentRel = pick.relativePath.substringBeforeLast('/', "")
        val uploadDir = if (parentRel.isEmpty()) targetDir else join(targetDir, parentRel)

        var lastEmit = 0L
        // 服务端残留文件被清掉时由 [AlistClient.upload] 回调回来，写进这一行的失败原因
        var cleanedPartial: String? = null
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
            // 整批取消 / 这一条被单独取消 —— 写循环下一次检查就退出
            isCancelled = { token.cancelled || cancelIds.contains(index) },
            onPartialCleaned = { cleanedPartial = it },
          )

        val cancelledHere = cancelIds.remove(index)
        result
          .onSuccess { verify ->
            updateTask(batchId, index) {
              it.copy(
                state = UploadTaskState.Success,
                sentBytes = it.sizeBytes,
                // 服务端回查过字节数才允许删本地，见 UploadTask.verified
                verified = verify == AlistClient.UploadVerify.Confirmed,
              )
            }
            // 自动删除：开关开 + 服务端确认过，两个都满足才动手机里的文件。
            // 删失败不影响这一条的上传结果，只把原因记在行上（用户还能手动再删一次）。
            if (autoDeleteAfterUpload.value && verify == AlistClient.UploadVerify.Confirmed) {
              applyLocalDelete(batchId, index, pick.contentUri, pick.file)
            }
          }
          .onFailure { e ->
            val detail = failureText(e, cleanedPartial)
            updateTask(batchId, index) {
              when {
                // 这一条是用户自己按「取消」掐断的：记成「已取消」但**可重试**
                // （他只是不想现在传这一个，不代表整批不要了）
                cancelledHere ->
                  it.copy(state = UploadTaskState.Cancelled, cancelledByUser = true, error = detail)
                token.cancelled || e is CancellationException ->
                  it.copy(state = UploadTaskState.Cancelled)
                else -> it.copy(state = UploadTaskState.Failed, error = detail)
              }
            }
          }
        if (token.cancelled) return
        // ② 每传完一个就更新一次落盘队列：进程若在下一个文件的传输中被回收，
        //    恢复时最多多列一条「其实已经传完的」，重传一次即可（远端同名会改写，
        //    不会产生两个文件）。反之若不更新，恢复时会把已传完的全都再传一遍。
        persistLeftover(app, connectionId, targetDir, _batch.value?.targetLabel.orEmpty())
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
    }
  }

  /**
   * 失败原因的人话（进度页那一行显示的就是它）。
   *
   * 除了内核给的原文，还要**把清理结果说出来，而且要说清是哪一个路径**：
   * 用户看到「失败」之后第一反应是「云盘里是不是留了半个文件」，只说「已清理」
   * 他也没法核对；给出完整路径（`电影/xxx.mkv`）才能自己去确认那一份没了。
   */
  private fun failureText(e: Throwable, cleanedPartial: String?): String {
    val reason = e.message?.takeIf { it.isNotBlank() } ?: "上传失败（没有更多信息）"
    return if (cleanedPartial != null) {
      "上传失败：$reason\n已清理服务器上的残留文件「$cleanedPartial」"
    } else {
      "上传失败：$reason"
    }
  }

  /**
   * 删掉一条任务的本地副本，并把结果写回这一行。
   *
   * 自动删除和手动清理**共用这一条路**：两边若各写一套判定和文案，会出现
   * 「自动删失败说 A、手动删失败说 B」的分裂。
   */
  private suspend fun applyLocalDelete(
    batchId: Long,
    taskId: Int,
    contentUri: Uri?,
    file: File?,
  ) {
    val app = appContextRef.get() ?: return
    val result = LocalCopyCleaner.delete(app, contentUri, file)
    updateTask(batchId, taskId) {
      if (result.isSuccess) {
        it.copy(
          localCopy = LocalCopyState.Deleted,
          localDeleteError = null,
          // 本地没了，预览入口必须一起失效 —— 否则点开就是一个打不开的文件
          previewUri = null,
        )
      } else {
        it.copy(
          localCopy = LocalCopyState.DeleteFailed,
          localDeleteError = result.exceptionOrNull()?.message ?: "未知原因",
        )
      }
    }
  }

  /**
   * 结束态的「手动清理」：删掉指定任务的本地副本，返回真的删掉了几个。
   *
   * 逐条删、逐条写结果 —— 一条失败不能让其余全停：批量删 50 张照片时第 3 张被别的
   * App 占着，剩下 47 张照样得删掉。
   *
   * 只认 [UploadTask.canDeleteLocal]：正在传的、传失败的、已经删过的都会被跳过，
   * 所以调用方把整批 id 丢进来也不会出事。
   */
  suspend fun deleteLocalCopies(taskIds: Collection<Int>): Int {
    val current = _batch.value ?: return 0
    var deleted = 0
    for (id in taskIds) {
      val task = current.tasks.firstOrNull { it.id == id } ?: continue
      if (!task.canDeleteLocal) continue
      applyLocalDelete(current.id, id, task.contentUri, task.localFile)
      if (_batch.value?.tasks?.firstOrNull { it.id == id }?.localCopy == LocalCopyState.Deleted) {
        deleted++
      }
    }
    return deleted
  }

  /**
   * 收尾：把没跑到的标成已取消、发「完成」事件、决定落盘队列留不留、撤掉通知。
   *
   * ⚠️ 这段在协程被取消时也会执行，所以只能做非挂起操作。
   */
  private fun settle(batchId: Long, token: CancelToken, connectionId: Long) {
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
    _stats.value = UploadStats()
    val finished = _batch.value
    if (finished?.id == batchId) {
      // 有成功的才让浏览页重列目录（全失败/全取消时目录没变，不该白刷一次）
      if (!token.cancelled && finished.successCount > 0) {
        _completed.tryEmit(connectionId)
      }
      val ctx = appContextRef.get()
      if (ctx != null) {
        // 还有没传完的（失败 / 单独取消）就把它们留在落盘队列里，
        // 没剩下就直接清空 —— 下次上传不该看到一条「还有 0 个未完成」的横幅
        if (unfinishedIds(finished).isEmpty()) {
          UploadQueueStore.clear(ctx)
        } else {
          persistLeftover(ctx, finished.connectionId, finished.targetDir, finished.targetLabel)
        }
      }
    }
    // ⚠️ 这里**不**停前台服务：服务自己每秒看一眼批次，发现不在跑就补一条
    // 「上传完成 / 有失败项」的结果通知再退出前台。在这里 stopService 会把它
    // 连那一条结果通知一起掐掉 —— 通知栏无声消失，看起来像上传被系统杀了。
  }

  private fun unfinishedIds(batch: UploadBatch): List<Int> =
    batch.tasks
      .filter {
        it.state == UploadTaskState.Pending || it.state == UploadTaskState.Failed || it.cancelledByUser
      }.map { it.id }

  /** 把「还没传完」的那几条写进落盘队列（[UploadQueueStore]），供下次恢复 */
  private fun persistLeftover(context: Context, connectionId: Long, targetDir: String, targetLabel: String) {
    val batch = _batch.value ?: return
    val picks = lastPicksRef.get()
    val left =
      unfinishedIds(batch).mapNotNull { id -> picks.getOrNull(id)?.let { id to it } }
    if (left.isEmpty()) {
      UploadQueueStore.clear(context)
    } else {
      UploadQueueStore.save(context, connectionId, targetDir, targetLabel, left.map { it.second })
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
