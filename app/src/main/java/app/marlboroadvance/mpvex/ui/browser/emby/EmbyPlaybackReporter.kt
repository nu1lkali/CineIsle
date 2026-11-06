package app.marlboroadvance.mpvex.ui.browser.emby
import app.marlboroadvance.mpvex.ui.player.PlayerDiagLog
import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyLog
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

/**
 * Emby 播放进度上报器。
 *
 * 职责：把 mpv 的播放状态实时同步回 Emby 服务器——
 * - 开始播放：POST /Sessions/Playing
 * - 播放中 / 暂停：POST /Sessions/Playing/Progress
 * - 切换下一集：上一集 Stopped + 新一集 Start（服务端"正在播放"随之更新）
 * - 退出播放页：POST /Sessions/Playing/Stopped
 *
 * 实现方式：直接读 MPVLib 的静态属性（time-pos / pause / path），
 * 以轮询的方式工作，因此不需要改动 PlayerActivity 内部的播放控制逻辑，
 * 只需在 Activity 上挂一个 lifecycle 观察者即可。
 */
object EmbyPlaybackReporter {
  private const val TAG = "EmbyReporter"

  /** 上报间隔：Emby 官方客户端也是每 10 秒一次进度上报 */
  private const val PROGRESS_INTERVAL_MS = 10_000L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var pollingJob: Job? = null

  private var serverId: Long = -1L
  private var itemIds: List<String> = emptyList()

  /**
   * 当前正在上报的条目 Id。
   *
   * ⚠️ `@Volatile`：写它的是轮询协程（IO），读它的可能是 [stop] 里的另一条协程，
   * 不加就可能读到旧值，把 Stopped 发到上一集上。
   */
  @Volatile private var currentItemId: String? = null

  @Volatile private var playSessionId: String = ""
  @Volatile private var lastReportedTicks: Long = 0L

  /**
   * 最近一次「Start 请求」的发出时刻（`SystemClock.elapsedRealtime()`）；0 = 没有在飞。
   *
   * 用途只有一个：别在 Start 还在路上时判定「没有 session」又补发一次 —— 那会变成
   * 同一场播放两个 session，服务端上就是两条并行的「正在播放」。
   */
  @Volatile private var startRequestedAt: Long = 0L

  /**
   * 已经为哪个条目补发过 Start。
   *
   * 补发是**兜底**不是重试机制，所以同一条目只准补一次：否则每次轮询都会发现
   * 「还是空串」再发一次，10 秒一条 Start 打到服务器上，比不修还糟。
   */
  @Volatile private var sessionRepairFor: String? = null

  /**
   * [playSessionId] 对应的那次 Start 是否**被服务端成功接收过**（2xx / 204 都算）。
   *
   * ⚠️ 判据必须是「请求成败」，不能是「有没有拿到 PlaySessionId」—— Emby 的
   * `/Sessions/Playing` 正常就返回 204 / 空 JSON，那个字段压根不存在。曾经拿空串当
   * 失败判据，结果每场只补一次 Start、之后整场一条 Progress 都不发（0% 的元凶）。
   */
  @Volatile private var startAcknowledged = false

  /**
   * 由播放页提供的「当前正在播的是哪一条」。
   *
   * ## 为什么需要这个口子（v223）
   *
   * 本类是个 object，拿不到播放页的 `playlistIndex` —— 那是 Compose 状态，而且被
   * 「拖动重排队列 / 随机播放」改过，与 intent 里的原始顺序早就错位了。播放页的
   * `PlayerActivity.currentEmbyItemId()` 已经把「路径反查 + 按当前下标兜底」两条路
   * 都实现了（302 直链、ISO 本地代理两种 path 都覆盖），这里直接借用，不复制逻辑。
   *
   * ⚠️ 不接这个口子的后果就是 v222 及之前的线上现象：开了「预解析直链（302）」后
   * mpv 的 `path` 是网盘 CDN 域名，`/Videos/{id}/stream` 正则恒不命中 ⇒
   * [readCurrentItemId] 恒 null ⇒ 永远退回起播那一条 ⇒ 连播时检测不到切集，
   * 上一集不报 Stopped、新一集不报 Start，新一集的时间被以第一条的 itemId 写出去。
   * 用户侧看到的就是「随机播放后首页继续观看不更新」。
   *
   * 返回 null 是正常情况（非 Emby 播放 / 列表还没就绪），此时退回路径反查。
   */
  @Volatile
  var idProvider: (() -> String?)? = null

  /**
   * 由播放页提供的「当前位置」，单位 ticks；读不到返回 null。
   *
   * 为什么需要：本类默认读 `PlayerLib.getPropertyInt("time-pos")`，而
   * `PlayerLib.canTouchNative = isMpv && coreReady` ⇒ **GSY 内核下恒读不到**。
   * GSY 播放页用 `GSYVideoManager.instance().curPlayerManager.currentPosition` 喂进来。
   *
   * ⚠️ 一旦设了就以它的返回值为准（哪怕是 null），**不再回退**到 mpv 属性 ——
   * 两边同时存在时回退会读到上一个内核的残留位置。
   */
  @Volatile
  var positionProvider: (() -> Long?)? = null

  /**
   * 由播放页提供的「是否暂停」；读不到返回 null（按「未暂停」处理）。
   *
   * 同一个理由：mpv 的 `pause` 属性在 GSY 内核下读不到。
   */
  @Volatile
  var pausedProvider: (() -> Boolean?)? = null

  /**
   * 当前这场播放的令牌。
   *
   * 一场播放一个（见 [EmbyPlaybackLifecycleObserver.token]）。存在的唯一原因：
   * 切换内核时新旧两个播放页会**短暂共存**（GSY 的 `onCreate` 先于 mpv 的 `onDestroy`），
   * 两个观察者都指向本单例。没有令牌的话，旧页面退场那次 [stop] 会拿**新页面**刚写进来的
   * `currentItemId` / `playSessionId` 去发 Stopped —— 把刚开始的那一集结算掉。
   */
  @Volatile
  private var sessionToken: Any? = null

  /** 与 [itemIds] **同序同长**的媒体源 Id（多版本条目预热时要用与播放一致的那个） */
  private var mediaSourceIds: List<String> = emptyList()

  /**
   * 已经预热过的 `"服务器Id:条目Id"`。
   *
   * 服务端 probe 完就写库了，同一条**没必要再打一次**；这份记录让「同一场播放里
   * 来回切集」不会重复预热，也让本类对服务器的请求总数 = 条目数（每条最多一次）。
   */
  private val warmedKeys = HashSet<String>()

  /**
   * 预热任务（单槽）。
   *
   * ⚠️ 必须串行：用户明确要求「不能同时向 CDN 发大量请求」。这里用**一个 Job 槽**
   * 保证同一时刻最多一条预热在飞 —— 上一个还没回来（服务端 probe 慢时可能十几秒）
   * 就直接放弃新的预热，而不是排队堆起来。预热是纯优化，少做一次没有后果。
   */
  private var warmJob: Job? = null

  /**
   * 排队中的下一条预热（`itemId` / 媒体源 Id）。
   *
   * 只保留**最新**一个：切集是往前走的，排一队旧的没有意义；同时保证「永远只有一个
   * 预热在飞」这条硬约束 —— 用户在意的是网盘 CDN 的风控，并发请求是大忌。
   */
  private var pendingWarm: Pair<String, String?>? = null

  /**
   * 结算「上一场还没收尾的播放」。
   *
   * 只在切换内核时用到：GSY 的 `onCreate` 先于 mpv 的 `onDestroy`，两场会短暂共存。
   * 不在这里结算的话，mpv 那场发出的 Stopped 会因为令牌不符被 [stop] 拒掉，
   * 服务端上就一直挂着一条「正在播放」（网页端「正在播放」里那条不走）。
   *
   * ⚠️ 位置取 [lastReportedTicks]、**不取实时读数**：此刻新页面已经把 `positionProvider`
   * 换成它自己的了，读出来是新片的 0 秒 —— 拿它给旧片发 Stopped 等于把旧片续播点清零。
   * 一次都没成功上报过（ticks ≤ 0）就干脆不发，服务端本来也没有那条记录。
   */
  private fun settlePreviousSession() {
    val itemId = currentItemId ?: return
    val sid = serverId
    val sessionId = playSessionId
    val sourceId = mediaSourceIdOf(itemId)
    val ticks = lastReportedTicks
    currentItemId = null
    playSessionId = ""
    if (ticks <= 0L) return
    scope.launch {
      runCatching {
        val repository = get<EmbyRepository>(EmbyRepository::class.java)
        val server = repository.getServer(sid) ?: return@launch
        repository.reportPlaybackStopped(
          server = server,
          itemId = itemId,
          playSessionId = sessionId,
          positionTicks = ticks,
          mediaSourceId = sourceId,
        )
      }
    }
  }

  /**
   * 播放页创建时调用：上报开始并启动轮询。
   *
   * @param token 本场播放的令牌；同一个观察者的 [EmbyPlaybackLifecycleObserver.onDestroy]
   *   会带同一个令牌回来，用来区分「这是不是我那一场」（见 [sessionToken]）。
   * @return true 表示这次播放来自 Emby，已接管上报
   */
  fun start(intent: Intent?, token: Any? = null): Boolean {
    val ids = intent?.getStringArrayListExtra(EXTRA_ITEM_IDS)
    val sid = intent?.getLongExtra(EXTRA_SERVER_ID, -1L) ?: -1L
    if (ids.isNullOrEmpty() || sid <= 0) return false

    // 上一场还没收尾（切内核时新旧两个播放页短暂共存）⇒ 先结算它，再认领这一场。
    // 顺序不能反：settlePreviousSession 读的是「当前字段」，一旦下面开始覆盖就晚了。
    if (sessionToken != null && sessionToken !== token) settlePreviousSession()
    // 认领这一场：之后只有拿着同一令牌的 stop 才能收尾
    sessionToken = token
    serverId = sid
    itemIds = ids
    currentItemId = ids.firstOrNull()
    lastReportedTicks = 0L
    // ⚠️ 会话 id **由客户端自己生成**，不等服务端给。
    //    Emby 的 `POST /Sessions/Playing` 正常返回 204（空 body）或 `{}`，里面根本没有
    //    `PlaySessionId` 字段 —— 靠响应取等于永远空串，而 Progress / Stopped 拿着空串
    //    去对账，服务端记不住这次播放（「退出后已观看 0%」就是这么来的）。
    playSessionId = newPlaySessionId()
    startAcknowledged = false
    sessionRepairFor = null
    mediaSourceIds =
      intent.getStringArrayListExtra(EXTRA_MEDIA_SOURCE_ID)?.toList() ?: emptyList()

    val repository = runCatching { get<EmbyRepository>(EmbyRepository::class.java) }.getOrNull()
      ?: return false

    val firstId = currentItemId ?: return false
    // 用启动时指定的续播位置作为起点
    val startPositionTicks =
      EmbyTicks.secondsToTicks((intent.getIntExtra("position", 0) / 1000).toLong())
    val firstSourceId = mediaSourceIdOf(firstId)
    val sessionForStart = playSessionId
    startRequestedAt = android.os.SystemClock.elapsedRealtime()
    scope.launch {
      try {
        runCatching {
          val server = repository.getServer(sid) ?: return@runCatching
          PlayerDiagLog.log(
            "report",
            "Start item=$firstId ticks=$startPositionTicks session=$sessionForStart",
          )
          playSessionId = repository.reportPlaybackStart(
            server = server,
            itemId = firstId,
            positionTicks = startPositionTicks,
            mediaSourceId = firstSourceId,
            playSessionId = sessionForStart,
          )
          // 判据是「请求成功返回」（2xx / 204 都算），**不是**「有没有拿到 session id」
          startAcknowledged = true
        }
      } finally {
        startRequestedAt = 0L
      }
    }

    // 让播放页的 Emby 快捷操作（收藏等）拿到当前 server / item
    EmbyPlayerActions.bind(intent)
    // ⚠️「不喜欢」同样必须**起播时就 bind**。
    //  之前只在 `reportProgressOnce()` 里、且仅当检测到切集时才 bind ——
    //  于是刚进播放页那一整段时间 `DislikePlayerActions.currentKey` 还是 null，
    //  横屏点按钮只会提示「当前视频没有可标记的来源」（反馈）。
    //  切集时那次 bind 保留：它是「画面已经切到下一集」的权威时机。
    //  bind 对相同 key 幂等（直接 return，零查询），两处都调没有额外开销。
    app.marlboroadvance.mpvex.ui.player.DislikePlayerActions.bind(
      app.marlboroadvance.mpvex.domain.dislike.DislikeKey(sid, firstId),
      // ⚠️ 片名必须传。播放器里标记时**只有 id**，不传的话存进库里的记录
      //   `title` 是空串 —— 管理页只能显示「（无标题）」，用户认不出标的是哪一部。
      //   播放页本来就有片名（启动播放器时作为 title extra 传进来的），顺手带上即可。
      title = intent?.getStringExtra("title").orEmpty(),
    )

    startPolling()
    // 第一条已经在放了 ⇒ 顺手把**下一条**预热掉（服务端 probe 完，切过去就秒开）。
    warmNextItem(firstId)
    return true
  }

  /**
   * 播放页退出时调用：上报停止并结束轮询。
   *
   * ## 调用时机（v227 起的约定）
   *
   * **先 `onStop`、后 `onDestroy`**，两次都调，靠令牌去重：
   * - `onStop` 里只在「Activity 正在 finish」（返回手势 / 返回键 / 点关闭）时报一次 ——
   *   此刻内核**还活着**，位置读得准，而且比 `onDestroy` 早一个生命周期，
   *   进程被系统回收前有充裕时间把请求发出去；
   * - `onDestroy` 是兜底（按 Home 后从最近任务划掉、PiP 关闭等）：那时位置多半已经
   *   读不到，只能退回 [lastReportedTicks]。
   *
   * @param token 本场播放的令牌。与当前会话不符时**直接返回** —— 那是「上一个内核的
   *   播放页」在退场，它要结算的是它自己那一集，不能拿新页面刚写进来的字段去收尾。
   */
  fun stop(token: Any? = null) {
    if (token != null && token !== sessionToken) return

    // ⚠️ 位置必须在摘掉 provider **之前**读。
    //    两个 provider 是 GSY 内核唯一的位置来源（`PlayerLib` 在 GSY 下 `canTouchNative`
    //    恒 false，属性读出来是 null），mpv 这条路进到 `onDestroy` 时内核也已经被
    //    `cleanupMPV()` 拆掉了。原来「先摘 provider、后读位置」的顺序 ⇒ 两者都读不到
    //    ⇒ 只能退回 `lastReportedTicks`（最多差 10 秒）；播放不满 10 秒就退出时它还是 0，
    //    于是 Stopped 把续播点写成 0 —— 用户看到的就是「退出后已观看 0%」。
    val itemId = currentItemId
    val liveTicks = if (itemId != null) readPositionTicks() else null

    sessionToken = null
    pollingJob?.cancel()
    pollingJob = null
    // 预热只是优化：用户已经离开播放页了，没必要再占着一条请求
    warmJob?.cancel()
    warmJob = null
    pendingWarm = null
    // ⚠️ 先摘掉回调再往下走：这些 lambda 捕获着 Activity，不摘就是静态引用把它钉住
    idProvider = null
    positionProvider = null
    pausedProvider = null
    EmbyPlayerActions.release()

    if (itemId == null) return
    // 实时读数优先；读不到才用「最后一次成功上报的位置」（别让 Stopped 把续播点写成 0）
    val ticks = liveTicks ?: lastReportedTicks
    val stopSourceId = mediaSourceIdOf(itemId)
    val sid = serverId
    // ⚠️ session 取**快照**：下面那段是异步发出去的，等它真跑起来时字段可能已经被
    //    下一场播放改写了（快速退出再进入就是这个时序）。
    val sessionId = playSessionId
    currentItemId = null
    playSessionId = ""

    scope.launch {
      runCatching {
        PlayerDiagLog.log(
          "report",
          "Stopped item=$itemId ticks=$ticks live=${liveTicks != null} session=$sessionId",
        )
        val repository = get<EmbyRepository>(EmbyRepository::class.java)
        val server = repository.getServer(sid) ?: return@launch
        repository.reportPlaybackStopped(
          server = server,
          itemId = itemId,
          playSessionId = sessionId,
          positionTicks = ticks,
          mediaSourceId = stopSourceId,
        )
      }.onFailure { EmbyLog.warn("上报 Stopped 失败（item=$itemId）", it) }
    }
  }

  /**
   * 预热「[currentId] 的下一条」。
   *
   * ## 为什么需要它（v142）
   *
   * 随机播放 / 连播都是「起播才去请求这一条」。strm 条目在服务端还没 probe 过源文件时，
   * 那次请求要先让服务端读远端文件、把大小时长写库 —— 能挂到分钟级。用户在播放页
   * 点「下一个」，看到的就是又卡住了；同一部片第二次播就快了，因为服务端已经有缓存。
   *
   * 所以这里提前一条：当前这条放起来之后（[WARM_DELAY_MS] 之后，别跟它抢带宽），
   * 用 [EmbyClient.warmStream] 让服务端先把下一条 probe 完。
   *
   * ## 三条硬约束（都是「别把服务器打爆」）
   *
   * · **只预热下一条**：不做整列表预取，请求数 = 条目数 × 1；
   * · **串行**：单槽 [warmJob]，上一个没回来就不起新的；期间新的请求只**记成待办**
   *   （[pendingWarm]），当前这条跑完再接着做 —— 不排队堆积、不并发；
   * · **不跟 302**：[EmbyClient.warmStream] 内部 `followRedirects=false`，
   *   请求只到我们自己的 Emby 服务器，**一个字节都不打网盘 CDN**。
   *
   * ⚠️ 本服务器的形态是「Emby 只存 .strm，真实文件在网盘 CDN 上」，所以这一条尤其要紧：
   * 预热**不增加** CDN 的总请求量（那次服务端读远端文件的 probe 本来就要在切播时发生），
   * 只是把它从「用户等待时」提前到「当前这条还在播时」。
   *
   * 预热失败 / 超时都没有后果：切到那一条时播放器自己再请求一次，行为与没有预热时一致。
   */
  private fun warmNextItem(currentId: String) {
    val idx = itemIds.indexOf(currentId)
    if (idx < 0 || idx + 1 >= itemIds.size) return
    val nextId = itemIds[idx + 1]
    val key = "$serverId:$nextId"
    if (key in warmedKeys) return
    val sourceId = mediaSourceIds.getOrNull(idx + 1)?.takeIf { it.isNotBlank() }

    // ⚠️ 顺序要紧：先判「有没有在飞」，再记账。反过来的话，被跳过的那条会被误记成
    //    「已预热」，之后就再也不会真的预热它了。
    if (warmJob?.isActive == true) {
      // 串行但不丢：只保留**最新**一个待办（旧的已经过时了，没必要排一队）
      pendingWarm = nextId to sourceId
      return
    }
    startWarm(nextId, sourceId)
  }

  /** 真正发一条预热（调用方保证同一时刻只有一个在飞） */
  private fun startWarm(itemId: String, mediaSourceId: String?) {
    val key = "$serverId:$itemId"
    if (key in warmedKeys) return
    warmedKeys.add(key)
    // 记录别无限长：一场播放的条目数有限，真到几千条时清一次（服务端本来就已缓存）
    if (warmedKeys.size > 512) warmedKeys.clear()
    warmJob = scope.launch {
      // 先让当前这条自己的缓冲跑起来，别跟它抢链路
      delay(WARM_DELAY_MS)
      runCatching {
        val repository = get<EmbyRepository>(EmbyRepository::class.java)
        val server = repository.getServer(serverId) ?: return@launch
        EmbyClient.warmStream(server, itemId, mediaSourceId = mediaSourceId)
      }
      // 跑完再看有没有排队的：有就接着做（仍然只有一个在飞）
      val queued = pendingWarm
      pendingWarm = null
      if (queued != null) startWarm(queued.first, queued.second)
    }
  }

  /**
   * 预热前的等待：当前这条刚起播时链路最紧张（拉容器头 + 首帧 + 缓冲），
   * 这时候再压一条预热上去，帮倒忙的概率比帮忙大。6 秒后当前这条基本已经稳住。
   */
  private const val WARM_DELAY_MS = 6_000L

  /**
   * 「Start 已经发出去了但还没回来」的宽限期。
   *
   * 轮询间隔 10 秒，给两轮的时间：超过这个时长还没有 `PlaySessionId` 就认为那次
   * Start 真的失败了（没网 / 服务端 500 / 回包字段缺失），[ensureSession] 才补发。
   * 不设这道闸的话，第一轮轮询时 Start 十有八九还在路上，会白白多开一个 session。
   */
  private const val SESSION_START_GRACE_MS = 20_000L

  private fun startPolling() {
    pollingJob?.cancel()
    pollingJob = scope.launch {
      // 等 mpv 加载完文件再读属性，避免读到空值
      delay(PROGRESS_INTERVAL_MS)
      while (isActive) {
        // ⚠️ **整轮兜住**。这里是 IO 协程的根：`scope` 用的是 `SupervisorJob` 且没有
        // CoroutineExceptionHandler ⇒ 任何一条漏兜的异常都会冒到线程的默认处理器，
        // 也就是「未捕获异常 ⇒ 进程直接死」。而本类恰好在**换片瞬间**读播放器状态
        // （mpv 正在销毁/重建 core、GSY 的 manager 刚 detach），正是最容易抛的时刻 ——
        // 用户看到的就是「切下一集偶发闪退」。内部各步该兜的仍然各自兜，但兜漏一处
        // 不该由用户买单。
        runCatching { reportProgressOnce() }
          .onFailure { EmbyLog.warn("进度上报异常（已忽略，下一轮继续）", it) }
        delay(PROGRESS_INTERVAL_MS)
      }
    }
  }

  /**
   * 一次进度上报。
   *
   * 若发现当前播放的已经是播放列表里的另一项（播完自动切下一集，或用户手动切换），
   * 则先结束上一集再开始新一集，让服务端的"正在播放"跟着走。
   */
  private suspend fun reportProgressOnce() {
    val repository = runCatching { get<EmbyRepository>(EmbyRepository::class.java) }.getOrNull()
      ?: return
    val server = repository.getServer(serverId) ?: return

    // 内核还没就绪 / 读不到位置 ⇒ 这一轮整轮跳过（不能当 0 发，见 readPositionTicks 注释）
    val positionTicks = readPositionTicks() ?: return
    val isPaused = readIsPaused()
    val playingItemId = readCurrentItemId() ?: currentItemId ?: return
    val playingSourceId = mediaSourceIdOf(playingItemId)

    if (playingItemId != currentItemId) {
      // ── 切换了媒体：结束上一集，开始新一集 ──
      val previousItemId = currentItemId
      val previousTicks = lastReportedTicks
      val previousSessionId = playSessionId
      val previousSourceId = previousItemId?.let { mediaSourceIdOf(it) }
      currentItemId = playingItemId
      // ⚠️ 会话必须**紧跟着作废**：这一轮只要在中途抛了（下面几行都是外部调用），
      //    下一轮就会发现 `playingItemId == currentItemId` 而直接拿这个 sessionId 去发
      //    新一集的 Progress —— 上一集的会话记新一集的位置，服务端上两集全乱。
      playSessionId = ""
      // 换条目 ⇒ 上一场补发记录作废；新条目的 Start 马上就来
      sessionRepairFor = null
      // 同步播放页 UI（收藏按钮等）到新的这一集。
      // 各自兜住：任何一处抛都不该让下面的 Start 不发（漏一条 Start，这一集就不进
      // 「继续观看」了）。
      runCatching { EmbyPlayerActions.onItemChanged(playingItemId) }
      // 「不喜欢」按钮同样要跟着切。走**内存快照**（一次 HashMap 查找，无 IO），
      // 所以连播时按钮状态与画面在同一帧完成，不会出现"先亮后暗"
      runCatching {
        app.marlboroadvance.mpvex.ui.player.DislikePlayerActions.bind(
          app.marlboroadvance.mpvex.domain.dislike.DislikeKey(serverId, playingItemId),
        )
      }

      if (previousItemId != null) {
        runCatching {
          repository.reportPlaybackStopped(
            server = server,
            itemId = previousItemId,
            playSessionId = previousSessionId,
            positionTicks = previousTicks,
            mediaSourceId = previousSourceId,
          )
        }
      }
      // ⚠️ 换一集就换一个会话 id：上一集的 Progress 绝不能记到新一集头上。
      //    这里**先生成再发**（不等服务端回话），Start 抛异常也不会留着旧的。
      val sessionForNext = newPlaySessionId()
      playSessionId = sessionForNext
      startAcknowledged = false
      runCatching {
        playSessionId = repository.reportPlaybackStart(
          server = server,
          itemId = playingItemId,
          positionTicks = 0,
          mediaSourceId = playingSourceId,
          playSessionId = sessionForNext,
        )
        startAcknowledged = true
      }
      lastReportedTicks = 0
      // 切到下一条了 ⇒ 立刻把「再下一条」预热掉。这就是 v142 修「随机播放切下一个
      // 还是卡住」的那一刀：卡的不是客户端，是服务端还没 probe 过那条 strm 的远端源。
      warmNextItem(playingItemId)
      return
    }

    // 手上没有可用 session 就先补一次 Start；补不上本轮跳过（带空串发出去等于白打）
    if (!ensureSession(repository, server, playingItemId, playingSourceId, positionTicks)) return

    lastReportedTicks = positionTicks
    runCatching {
      repository.reportPlaybackProgress(
        server = server,
        itemId = playingItemId,
        playSessionId = playSessionId,
        positionTicks = positionTicks,
        isPaused = isPaused,
        mediaSourceId = playingSourceId,
      )
    }
  }

  /**
   * 确保 Start 至少被服务端接收过一次；**无论补发成败都放行本轮 Progress**。
   *
   * ## 为什么需要它
   *
   * [start] 里那次 Start 是**异步发**的，网络抖动 / 服务端 5xx 都可能让它没送到。
   * 这种情况要补一次，否则服务端上这场播放「没开始过」，后续 Progress 无处归账。
   *
   * ## ⚠️ 曾经的重大错误（v223~v225，用户报「退出后已观看 0%」）
   *
   * 那时用「`playSessionId` 是否为空」判断 Start 成没成功 —— 可 Emby 的
   * `/Sessions/Playing` 正常就返回 **204 / 空 JSON，里面没有这个字段**，于是每次都判成
   * 失败：每场只补一次，之后**整场一条 Progress 都不发**，Stopped 又只能拿
   * `lastReportedTicks`（从没更新过 = 0）结账 ⇒ 服务端记成「已观看 0%，剩余 = 总时长」。
   *
   * 现在会话 id 由客户端自己生成（[newPlaySessionId]），所以**不存在**「空串」这个失败
   * 信号；成败只看请求是否成功返回（[startAcknowledged]）。
   *
   * ## 约束
   *
   * · **不在飞时补**：Start 还在路上（[startRequestedAt] 非 0）就等下一轮，
   *   否则同一场播放会开出两个会话，服务端上就是两条并行的「正在播放」；
   * · **同一条目只补一次**（[sessionRepairFor]）：这是兜底不是重试，变成每 10 秒
   *   一条 Start 比不修更糟。补过之后不再拦 Progress。
   *
   * @return false 仅表示「Start 正在路上，这一轮先别发」
   */
  private suspend fun ensureSession(
    repository: EmbyRepository,
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    positionTicks: Long,
  ): Boolean {
    if (startAcknowledged) return true
    val requestedAt = startRequestedAt
    if (requestedAt > 0L && android.os.SystemClock.elapsedRealtime() - requestedAt < SESSION_START_GRACE_MS) {
      return false
    }
    if (sessionRepairFor == itemId) return true
    sessionRepairFor = itemId
    val session = newPlaySessionId()
    playSessionId = session
    runCatching {
      playSessionId = repository.reportPlaybackStart(
        server = server,
        itemId = itemId,
        positionTicks = positionTicks,
        mediaSourceId = mediaSourceId,
        playSessionId = session,
      )
      startAcknowledged = true
    }.onFailure {
      // 补不上也让本轮 Progress 照发：带会话 id 的 Progress 服务端能按 ItemId + 会话对账，
      // 比整场不上报好得多。
      EmbyLog.warn("补发 Start 失败（item=$itemId），本轮进度照发", it)
    }
    return true
  }

  /**
   * 生成一个播放会话 id。
   *
   * 形态跟 Emby 官方客户端一致：32 位十六进制（去掉 UUID 的连字符）。客户端生成、
   * 三处（Start / Progress / Stopped）共用同一个值，服务端据此把三次请求串成一条会话。
   */
  private fun newPlaySessionId(): String =
    java.util.UUID.randomUUID().toString().replace("-", "").lowercase()

  // ─── mpv 属性读取 ───

  /**
   * 当前播放位置（ticks）；**读不到返回 null**。
   *
   * ① 播放页给了 [positionProvider] 就用它（GSY 内核走这条，见那里的注释）；
   * ② 否则读 mpv 的 `time-pos`。
   *
   * ⚠️ 读不到绝不能当成 0：`PlayerLib.getPropertyInt` 在内核还没就绪（`coreReady`
   * 未置位）、或当前内核不是 mpv 时返回 null。把这个 null 写成 0 发出去，等于告诉
   * 服务端「这部片从头开始播」—— 服务端于是把续播点清零，「继续观看」里那条记录
   * 就退回到第 0 秒。比「这一轮不报」有害得多，所以宁可跳过本轮。
   */
  private fun readPositionTicks(): Long? {
    // ⚠️ provider 存在就以它的返回值为准（哪怕是 null），不回退 —— 两边同时存在时
    //    回退会读到上一个内核残留的位置。provider 抛异常同样按「读不到」处理。
    positionProvider?.let { provider ->
      return runCatching { provider() }
        .onFailure { EmbyLog.warn("positionProvider 异常（已忽略）", it) }
        .getOrNull()
    }
    val seconds = runCatching { PlayerLib.getPropertyInt("time-pos") }.getOrNull() ?: return null
    return EmbyTicks.secondsToTicks(seconds.toLong())
  }

  private fun readIsPaused(): Boolean {
    pausedProvider?.let { provider ->
      return runCatching { provider() }
        .onFailure { EmbyLog.warn("pausedProvider 异常（已忽略）", it) }
        .getOrNull() ?: false
    }
    return runCatching { PlayerLib.getPropertyBoolean("pause") }.getOrNull() == true
  }

  /**
   * 当前正在播的 Emby 条目 Id。
   *
   * ① **先问宿主**（[idProvider]）：播放页知道「现在是队列里的第几条」，它的
   *   `PlayerActivity.currentEmbyItemId()` 已经把「路径反查 + 按当前下标兜底」两条路
   *   都做了，302 直链与 ISO 本地代理两种 path 都覆盖；
   * ② 再退到从 mpv 的 `path` 反查（播放地址形如
   *   `/emby/Videos/{itemId}/stream?static=true&api_key=...`）。
   *
   * ⚠️ 只有 ② 是不够的：开了「预解析直链（302）」之后 `path` 是网盘 CDN 的真实地址，
   * 没有 `/Videos/{id}/stream` 可反查 ⇒ 恒 null ⇒ [reportProgressOnce] 退回
   * `currentItemId`（永远是起播那一条）⇒ 连播时永远检测不到切集。这个坑播放页早就
   * 踩过并修好了（见 `PlayerActivity.currentEmbyItemId()` 的 ② 号分支），本类 borrow 它。
   */
  private fun readCurrentItemId(): String? {
    // ⚠️ provider 是播放页的 lambda（里面读 `playlistIndex` 与 mpv 的 `path`）。
    //    换片那一瞬间 mpv 正在重建 core，它抛异常是完全可能的 —— 兜住，退回路径反查。
    //    这条兜底本身就是防「切下一集偶发闪退」的（异常冒到 IO 协程根会直接杀进程）。
    val fromHost = idProvider
      ?.let { provider -> runCatching { provider() }.getOrNull() }
      ?.takeIf { it.isNotBlank() }
    val resolved = fromHost
      ?: runCatching { parseItemIdFromPath(PlayerLib.getPropertyString("path")) }.getOrNull()
    // 只认可本次播放列表里的 ID，避免 mpv 自动连播到无关文件时误报
    return resolved?.takeIf { itemIds.contains(it) }
  }

  /**
   * 某条目**实际在播的那个版本**的媒体源 Id；单版本 / 不是本次队列里的条目返回 null。
   *
   * [mediaSourceIds] 与 [itemIds] **同序同长**（启动侧就是这么写的），所以按下标取。
   */
  private fun mediaSourceIdOf(itemId: String): String? {
    val idx = itemIds.indexOf(itemId)
    if (idx < 0) return null
    return mediaSourceIds.getOrNull(idx)?.takeIf { it.isNotBlank() }
  }

  /**
   * 从 mpv 当前播放路径里解析 Emby 媒体 ID；不是 Emby 流（或没解析出）返回 null。
   *
   * `public` 是给播放页用的：它要按「正在播的这一条」构造按版本的进度键，
   * 而**不能用 `playlistIndex` 去索引 intent 里的 itemIds** —— 用户拖动过播放队列后
   * 下标就与 intent 的原始顺序错位了。从路径反查 Id 与队列顺序无关，天然免疫这种错位。
   */
  fun parseItemIdFromPath(path: String?): String? =
    path?.let { ITEM_ID_REGEX.find(it)?.groupValues?.getOrNull(1) }
      ?.takeIf { it.isNotBlank() }

  private val ITEM_ID_REGEX = Regex("/Videos/([^/?]+)/stream")

  // ─── Intent extra 键（与 EmbyViewModel 启动播放器时写入的保持一致）───

  const val EXTRA_SERVER_ID = "emby_server_id"
  const val EXTRA_ITEM_IDS = "emby_item_ids"

  /**
   * 当前播放的媒体源 Id（在线字幕搜索要用）。
   *
   * 为什么单独一个 extra 而不是让播放页反查：反查要走
   * `GET /Items/{Id}` 再从 `MediaSources` 里挑，播放页是**每切一集都变**的，
   * 而这个值在启动播放器时就已经知道了。多版本媒体（4K/1080P 各带不同字幕）
   * 时挑错就搜到别的版本的字幕，所以必须由启动侧显式传。
   */
  const val EXTRA_MEDIA_SOURCE_ID = "emby_media_source_id"

  /**
   * 与 [EXTRA_ITEM_IDS] **同序同长**的多版本名列表（取不到的用空串占位）。
   *
   * 播放页顶栏会在片名后面追加当前这一条的版本名（如「片名 · 1080P HEVC」）——
   * 用户在多版本条目里选了哪个版本，播起来得能看见，否则「切了版本」没有任何反馈。
   * 用列表而不是单值，理由同 [EXTRA_MEDIA_SOURCE_ID]：连播切集后版本会变。
   */
  const val EXTRA_MEDIA_SOURCE_NAMES = "emby_media_source_names"

  /**
   * 与 [EXTRA_ITEM_IDS] **同序同长**的「原始剧目顺序号」，编码成
   * `ParentIndexNumber*1000 + IndexNumber`（任一为空则存空串）。
   *
   * ⚠️ 为什么弹幕非要这个：`PlayerActivity` 传给弹幕匹配的是「播放队列下标」，
   * 那是**队列里的第几个**，不是「第几集」。用户从第 5 集点进详情页播放时，
   * 队列只有 1 条 ⇒ 下标 0 ⇒ 会被当成第 1 集去取弹幕。
   * 有了这个字段，弹幕按真实集号匹配（跨季也成立：index 随 Season 累积）。
   *
   * 为什么不用 [EXTRA_ITEM_IDS] 反查服务端：那是异步的，而弹幕加载在切集瞬间就要用，
   * 多一次网络往返只在换集时失败率更高，还会与「预解析直链」的路径反查打架。
   */
  const val EXTRA_EPISODE_NUMBERS = "emby_episode_numbers"

  /**
   * 挂在 PlayerActivity 上的生命周期观察者。
   *
   * 用法：`lifecycle.addObserver(EmbyPlaybackLifecycleObserver(intent))`
   */
  class EmbyPlaybackLifecycleObserver(
    private val intent: Intent?,
  ) : DefaultLifecycleObserver {
    /**
     * 本场播放的令牌。
     *
     * 每个播放页一个。切换内核时新旧页面会短暂共存（GSY 的 `onCreate` 先于 mpv 的
     * `onDestroy`），两个观察者指向同一个单例 —— 旧页面必须能认出「我要收尾的那一场
     * 已经不是当前这一场了」，否则会拿新页面刚写进去的条目去发 Stopped。
     */
    private val token = Any()

    override fun onCreate(owner: LifecycleOwner) {
      start(intent, token)
    }

    /**
     * 退出播放页时**提前**报 Stopped（v227）。
     *
     * ## 为什么不能只靠 [onDestroy]
     *
     * 1. **内核已经拆了**：`PlayerActivity` 的 `super.onDestroy()` 在最后一行，那时
     *    `cleanupMPV()` 已经跑过、GSY 那边 `super.onDestroy()` 反而在最前面 ——
     *    两条路都读不到实时位置，只能退回「最后一次上报的位置」。
     * 2. **进程随时可能被回收**：返回手势 / 从最近任务划掉之后，系统可以在 `onDestroy`
     *    刚结束时就杀掉进程，那条才 `launch` 出去的请求根本来不及发出。
     *
     * `onStop` 比 `onDestroy` 早一个生命周期，是「用户真的要走了」与「按 Home / 进小窗 /
     * 转屏」的分界：只有 Activity 已经在 finish 时才报，后三种 `isFinishing` 为 false，
     * 报上去反而会把「后台继续播放」这条会话掐掉。
     *
     * 与 [onDestroy] 不冲突：第一次调完 `sessionToken` 已置空，第二次在令牌那一步直接返回。
     */
    override fun onStop(owner: LifecycleOwner) {
      val activity = owner as? Activity ?: return
      if (activity.isFinishing) stop(token)
    }

    override fun onDestroy(owner: LifecycleOwner) {
      stop(token)
    }
  }
}

/**
 * 供 EmbyViewModel 调用：写入上报所需的 extra。
 *
 * @param mediaSourceIds 与 [itemIds] **同序同长**的媒体源 Id 列表；
 *   取不到的用空串占位（在线字幕搜索会跳过空串）。
 *   用列表而不是单值，是因为播放器支持连播 —— 切集后当前媒体源会变，
 *   播放页按当前 `playlistIndex` 取对应那个。
 */
internal fun Intent.putEmbyPlaybackExtras(
  server: EmbyServer,
  itemIds: List<String>,
  mediaSourceIds: List<String> = emptyList(),
  /**
   * 与 [itemIds] **同序同长**的版本显示名（空串 = 这一条没有多版本 / 没选版本）。
   * 播放页顶栏据此在片名后追加「· <版本名>」。
   */
  mediaSourceNames: List<String> = emptyList(),
  /**
   * 与 [itemIds] **同序同长**的「S*E*」原始序号，见 [EmbyPlaybackReporter.EXTRA_EPISODE_NUMBERS]。
   * 非剧集（电影 / 本地文件）传空表即可 —— 播放页拿不到就退回队列下标。
   */
  episodeNumbers: List<String> = emptyList(),
) {
  putExtra(EmbyPlaybackReporter.EXTRA_SERVER_ID, server.id)
  putStringArrayListExtra(EmbyPlaybackReporter.EXTRA_ITEM_IDS, ArrayList(itemIds))
  if (episodeNumbers.isNotEmpty()) {
    putStringArrayListExtra(EmbyPlaybackReporter.EXTRA_EPISODE_NUMBERS, ArrayList(episodeNumbers))
  }
  if (mediaSourceIds.isNotEmpty()) {
    putStringArrayListExtra(
      EmbyPlaybackReporter.EXTRA_MEDIA_SOURCE_ID,
      ArrayList(mediaSourceIds),
    )
  }
  if (mediaSourceNames.isNotEmpty()) {
    putStringArrayListExtra(
      EmbyPlaybackReporter.EXTRA_MEDIA_SOURCE_NAMES,
      ArrayList(mediaSourceNames),
    )
  }
}
