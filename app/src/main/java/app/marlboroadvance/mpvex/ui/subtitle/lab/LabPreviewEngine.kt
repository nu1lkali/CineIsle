package app.marlboroadvance.mpvex.ui.subtitle.lab

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.player.MPVView
import java.util.Locale
import kotlinx.coroutines.delay
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils as MpvUtils

/**
 * 字幕实验室的**预览播放引擎** —— 一块能放视频、能挂字幕的 mpv 画面。
 *
 * ## 它和正式播放器的关系
 *
 * 需求 7.4 要求「预览必须复用同一套字幕渲染框架，禁止另写 Overlay」。这里满足得很彻底：
 * 画面走的就是 `MPVView`（同一个类、同一份 `initOptions`），于是字号 / 字体 / 颜色 /
 * 描边 / 阴影 / 位置 / `sub-ass-override` / ASS 特效全部自动跟随用户在设置页的选择 ——
 * 不存在「预览好看、正片不一样」的可能。
 *
 * ## 为什么可以存在「第二个 mpv」
 *
 * `MPVLib` 是进程级单例，同一时刻只能有一份句柄。本类的用法与
 * [app.marlboroadvance.mpvex.ui.player.feed.FeedMpvEngine] **完全一致**：
 * 进来时自己 `initialize()`，离开时 `MPVLib.destroy()` 把句柄还回去。
 * 只要「用它的这段时间里没有别人在用」，单例就不是限制。
 *
 * 实验室在设置页（`MainActivity`），播放器是独立的 `PlayerActivity` ——
 * 能进到设置页，说明播放器已经退出并走过 `PlayerLib.destroy()`。
 * 因此这里接管不会撞车；反过来，**只要预览还开着就不该跳去播放**，
 * 这一点由调用方（预览面板只有一个「关闭预览」出口）保证。
 *
 * ## 三条必须照抄的既有经验（少一条就是黑屏）
 *
 * 1. `initialize()` 必须在 View **attach 之前**完成 —— 它内部才执行 `MPVLib.create()`，
 *    attach 后首帧就会 `attachSurface()`，在 create 之前调用等于解引用空指针（native 崩溃）。
 * 2. `initialize()` 之后要把 AAR 写死的 `idle=once` 改回 `yes`：`once` 的语义是
 *    「只放一个文件，stop 之后直接收工」，预览里换片子 / 换字幕后重新 `loadfile` 会静默失效。
 * 3. `surfaceDestroyed` 会把 `vo` 与 `force-window` 一起关掉。预览区在滚动 / 软键盘
 *    弹出时会被反复销毁重建，所以每次挂上表面都要把 `vo` 恢复回来，否则「有声音、全黑」。
 */
internal class LabPreviewEngine(
  private val context: Context,
) {
  private var host: View? = null
  private var mpvView: MPVView? = null

  /** 初始化走到不可恢复的失败 —— 不再重试，避免反复碰 native。 */
  private var broken = false

  private var surfaceAttached = false

  /** 已点名、但还没送进 mpv 的地址（等 Surface 就绪）。 */
  private var pendingUrl: String? = null
  private var pendingStartSec: Double = 0.0

  /** 已经真正下发过 `loadfile` 的地址。 */
  private var loadedUrl: String? = null

  /** `initOptions` 选定的 vo（`surfaceDestroyed` 后要按它恢复，见类注释第 3 条）。 */
  private var voName: String? = null

  /** 内核是否可用（之后才允许碰任何 `MPVLib` 属性）。 */
  val ready: Boolean get() = mpvView != null

  fun hostView(): View? = host

  /**
   * 建好画面 View 并完成内核初始化；返回 null 表示这条路走不通（调用方摆错误提示）。
   *
   * ⚠️ 必须在**主线程**调用，且要在返回的 View 交给 Compose **之前**完成（类注释第 1 条）。
   * 调用前请先在 IO 线程跑一次 `MpvUtils.copyAssets` —— `sub-fonts-dir` / `tls-ca-file`
   * 都指向这些资源，缺了会表现为「字幕用默认字体」或「https 源握手失败」。
   */
  fun ensureHost(inflater: LayoutInflater): View? {
    host?.let { return it }
    if (broken) return null

    val root =
      runCatching { inflater.inflate(R.layout.view_lab_subtitle_mpv, null, false) }
        .getOrNull() ?: run {
        broken = true
        return null
      }
    val view =
      root.findViewById<MPVView>(R.id.labMpv) ?: run {
        broken = true
        return null
      }

    val inited =
      runCatching { view.initialize(context.filesDir.path, context.cacheDir.path) }.isSuccess
    if (!inited) {
      broken = true
      return null
    }

    mpvView = view
    host = root

    // 覆盖 AAR 的 idle=once（类注释第 2 条）
    runCatching { MPVLib.setOptionString("idle", "yes") }
    runCatching { MPVLib.setPropertyString("idle", "yes") }
    runCatching { MPVLib.command("set", "idle", "yes") }

    voName = runCatching { MPVLib.getPropertyString("vo") }.getOrNull()

    // ⚠️ 回调必须注册在 AAR 的 initialize() **之后**：AAR 在 initialize 里把自己注册成
    //    SurfaceHolder.Callback，surfaceCreated 时先 attachSurface、恢复 vo；我们后注册，
    //    才能在这一刻安全地补一次 pump。
    runCatching {
      view.holder.addCallback(
        object : SurfaceHolder.Callback {
          override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceAttached = true
            // 首次挂面 / 从后台回来：AAR 的 surfaceDestroyed 会把 vo 与 force-window
            // 一起关掉，而它自己的恢复分支只在「没有待加载文件」时才走 —— 我们通常
            // 已经加载过文件，所以必须自己把输出恢复回来，否则「有声音、全黑」。
            val v = mpvView ?: return
            runCatching { ensureVo(v) }
            pump()
          }

          override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
          ) {
            surfaceAttached = true
            pump()
          }

          override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceAttached = false
          }
        },
      )
    }.onFailure { Log.w(TAG, "surface callback setup failed: ${it.message}") }

    surfaceAttached = runCatching { view.holder.surface.isValid }.getOrDefault(false)

    // 预览要能出声，但默认音量跟系统走（mpv 默认 100 = 不缩放）
    runCatching { MPVLib.setPropertyString("volume", "100") }

    return root
  }

  /**
   * 开始播 [url]（同一地址重复调用无副作用）。
   *
   * [startSec] 用 mpv 的 `start` 选项落地，而不是 loadfile 之后再 seek：命令是排队执行的，
   * `loadfile` 还没切完就发 `seek`，落点会飘到旧文件的时间轴上（上一部片的进度）。
   */
  fun play(
    url: String,
    startSec: Double = 0.0,
  ) {
    if (mpvView == null) return
    if (url == loadedUrl && pendingUrl == null) {
      runCatching { MPVLib.setPropertyBoolean("pause", false) }
      return
    }
    pendingUrl = url
    pendingStartSec = startSec.coerceAtLeast(0.0)
    pump()
  }

  /**
   * Surface 没就绪就继续等；就绪后 `loadfile ... replace`。
   *
   * ## 为什么不用 `BaseMPVView.playFile()`
   *
   * 反编译 AAR：`playFile()` 只把路径存进 `filePath` 字段，真正的 `loadfile` 写在
   * `surfaceCreated()` 里，且读一次就清空。Surface 已经 attach 之后再调它 ⇒ 什么都不发生。
   * 这条坑在视界流上踩过（v143 黑屏），这里直接用 `MPVLib.command("loadfile", ...)`。
   */
  private fun pump() {
    val view = mpvView ?: return
    val url = pendingUrl ?: return
    if (!surfaceAttached) return
    val surfaceReady = runCatching { view.holder?.surface?.isValid == true }.getOrDefault(false)
    if (!surfaceReady) return

    ensureVo(view)
    pendingUrl = null
    runCatching { MPVLib.setOptionString("start", String.format(Locale.US, "%.3f", pendingStartSec)) }
    // replace 而不是追加：预览是「一屏一条」，不留队列
    runCatching { MPVLib.command("loadfile", url, "replace") }
    runCatching { MPVLib.setPropertyBoolean("pause", false) }
    loadedUrl = url
  }

  /** 把视频输出恢复成可用状态（类注释第 3 条）。 */
  private fun ensureVo(view: MPVView) {
    val current = runCatching { MPVLib.getPropertyString("vo") }.getOrNull()
    val usable = !current.isNullOrBlank() && current != "null" && current != "no"
    if (usable) {
      if (voName == null) voName = current
      runCatching { MPVLib.setPropertyString("force-window", "yes") }
      return
    }
    val target = voName ?: return
    runCatching { view.setVo(target) } // 同步 AAR 内部的 voInUse
    runCatching { MPVLib.setPropertyString("vo", target) } // 运行期真正恢复输出
    runCatching { MPVLib.setPropertyString("force-window", "yes") }
  }

  // ──────────────────────────── 字幕 ────────────────────────────

  /**
   * 挂一条本地字幕并选中。
   *
   * ## 为什么不是「`sub-add ... select` 就完事」
   *
   * `sub-add` 是异步命令：命令返回那一刻轨道还没进 `track-list`。此时 `select` 标记
   * 可能落空（项目里为此专门写过 `awaitTrackCountAtLeast`）。所以这里等轨道条目真的
   * 多出来一条，再显式把 `sid` 设到**最后那条 external 字幕轨**上 —— 比赌 select 稳。
   *
   * ## 必须先有媒体
   *
   * `loadfile` 还没完成时发过去的 `sub-add` 会被直接丢掉（轨道列表是空的）。
   * 调用方要等 [hasMedia] 为真再调。
   *
   * ⚠️ 只能在主线程调用（内部 `delay` 会让出主线程，但 `MPVLib` 的读写必须在主线程）。
   *
   * @return 是否选中成功（失败时调用方可以提示「该格式可能不受支持」）
   */
  suspend fun attachSubtitle(
    path: String,
    title: String?,
    offsetMs: Long,
  ): Boolean {
    if (mpvView == null) return false
    val before = runCatching { MPVLib.getPropertyInt("track-list/count") ?: 0 }.getOrDefault(0)
    val readable = title?.trim()?.takeIf { it.isNotEmpty() }
    runCatching {
      if (readable != null) {
        MPVLib.command("sub-add", path, "select", readable)
      } else {
        MPVLib.command("sub-add", path, "select")
      }
    }
    // 等轨道真的建出来（最多 1.5s）。不等就设 sid 会被当成「不存在的轨道」丢掉。
    var sid: Int? = null
    val deadline = SystemClock.uptimeMillis() + 1_500L
    while (SystemClock.uptimeMillis() < deadline) {
      val count = runCatching { MPVLib.getPropertyInt("track-list/count") ?: before }.getOrDefault(before)
      if (count > before) {
        sid = externalSubtitleSid(count)
        if (sid != null) break
      }
      delay(40)
    }
    sid?.let { runCatching { MPVLib.setPropertyInt("sid", it) } }
    setSubtitleDelay(offsetMs)
    runCatching { MPVLib.setPropertyBoolean("sub-visibility", true) }
    return sid != null
  }

  /** 轨道列表里最后一条 `external` 字幕轨的 id（新加的那条）。 */
  private fun externalSubtitleSid(count: Int): Int? {
    var found: Int? = null
    for (i in 0 until count) {
      val type = runCatching { MPVLib.getPropertyString("track-list/$i/type") }.getOrNull()
      if (type != "sub") continue
      val external = runCatching { MPVLib.getPropertyString("track-list/$i/external") }.getOrNull()
      if (external != "yes") continue
      runCatching { MPVLib.getPropertyInt("track-list/$i/id") }.getOrNull()?.let { found = it }
    }
    return found
  }

  /**
   * 时间轴偏移。**只改运行期属性，不动字幕文件本身**（需求 8：优先用偏移量）。
   *
   * 正数 = 字幕整体延后。mpv 的 `sub-delay` 就是这个语义，单位秒。
   */
  fun setSubtitleDelay(offsetMs: Long) {
    if (mpvView == null) return
    runCatching {
      MPVLib.setPropertyString("sub-delay", String.format(Locale.US, "%.3f", offsetMs / 1000.0))
    }
  }

  /** 关掉字幕显示，用于对比「有字幕 / 没字幕」的画面。 */
  fun setSubtitleVisible(visible: Boolean) {
    if (mpvView == null) return
    runCatching { MPVLib.setPropertyBoolean("sub-visibility", visible) }
  }

  // ──────────────────────────── 播放控制 ────────────────────────────

  val hasMedia: Boolean
    get() = loadedUrl != null

  /**
   * 等到 mpv 真的把轨道列出来（最多 [timeoutMs]，默认 8 秒）。
   *
   * `sub-add` 在 `loadfile` 完成之前发过去会被**直接丢掉**：那时 `track-list` 还是空的，
   * 字幕既进不了列表、也不会报错。所以挂字幕前必须先等这一拍 —— 项目里
   * `restoreBoundSubtitle` 出于同一个原因也做了同样的轮询。
   *
   * ⚠️ 只能在主线程调用（`MPVLib` 属性读写不能跨线程）。
   */
  suspend fun awaitTracks(timeoutMs: Long = 8_000L): Boolean {
    if (mpvView == null) return false
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
      val count = runCatching { MPVLib.getPropertyInt("track-list/count") ?: 0 }.getOrDefault(0)
      if (count > 0) return true
      delay(60)
    }
    return false
  }

  fun setPaused(paused: Boolean) {
    if (mpvView == null) return
    runCatching { MPVLib.setPropertyBoolean("pause", paused) }
  }

  fun isPaused(): Boolean =
    if (mpvView == null) true
    else runCatching { MPVLib.getPropertyBoolean("pause") ?: true }.getOrDefault(true)

  fun positionSec(): Double =
    if (mpvView == null) 0.0
    else runCatching { MPVLib.getPropertyDouble("time-pos") ?: 0.0 }.getOrDefault(0.0)

  fun durationSec(): Double =
    if (mpvView == null) 0.0
    else runCatching { MPVLib.getPropertyDouble("duration") ?: 0.0 }.getOrDefault(0.0)

  /** 真正解出第一帧了没有（加载圈撤不撤的判据）。 */
  fun hasPicture(): Boolean =
    if (mpvView == null) false
    else runCatching { (MPVLib.getPropertyInt("video-params/w") ?: 0) > 0 }.getOrDefault(false)

  fun isBuffering(): Boolean =
    if (mpvView == null) false
    else runCatching { MPVLib.getPropertyBoolean("paused-for-cache") ?: false }.getOrDefault(false)

  /**
   * 跳到 [sec]。
   *
   * 用 `absolute+exact` 而不是主播放器那边的 `absolute+keyframes`：实验室的拖动
   * 全部来自「跳到某一句台词」，落点差一个 GOP（可能好几秒）就等于没跳对 ——
   * 这里精度优先，宁可多解几帧。命令级 flag 同样能盖掉全局 `hr-seek` 的设置。
   */
  fun seekTo(sec: Double) {
    if (mpvView == null) return
    runCatching { MPVLib.command("seek", sec.coerceAtLeast(0.0).toString(), "absolute+exact") }
  }

  fun setVolume(percent: Int) {
    if (mpvView == null) return
    runCatching { MPVLib.setPropertyString("volume", percent.coerceIn(0, 150).toString()) }
  }

  fun stop() {
    pendingUrl = null
    loadedUrl = null
    if (mpvView == null) return
    runCatching { MPVLib.command("stop") }
  }

  /**
   * 「关闭预览」用：停播 + 暂停，但**不销毁内核**。
   *
   * ## 为什么关预览不 destroy
   *
   * 用户会反复点「预览 / 关闭预览」。每次关闭都 destroy、再点又 create，等于在一个
   * 进程里反复重建 mpv 实例 —— 这正是 v313 闪退的来源。改成「关 = 停播」之后：
   * 整个实验室页生命周期内只 create 一次、只 destroy 一次（见 [release]），
   * 重建次数从「每次点按」降到「每次进页面」。
   *
   * 画面留在最后一帧也无所谓：调用方会在上面盖一层不透明占位（见 `LabPreviewPane`）。
   */
  fun pauseAndStop() {
    if (mpvView == null) return
    stop()
    runCatching { MPVLib.setPropertyBoolean("pause", true) }
  }

  /**
   * 释放内核 —— 一定要在离开实验室时调，否则下一部片进播放器会拿不到句柄。
   *
   * ⚠️ 只调 `MPVLib.destroy()`，**不**调 `BaseMPVView.destroy()`：与 `PlayerLib.destroy()` /
   * `FeedMpvEngine.release()` 同一条线上验证过的路径。View 侧随窗口销毁自己会 detachSurface，
   * 重复销毁反而容易碰上已释放的句柄。
   *
   * ## ⚠️⚠️ 为什么不能「stop 完立刻 destroy」
   *
   * v313 之前的实现就是 `stop()` 紧接 `MPVLib.destroy()`，真机上表现为
   * **「点预览 → 关预览 → 再点预览」必闪退**（native SIGSEGV，Java 层抓不到）。
   * 根因与 [app.marlboroadvance.mpvex.ui.player.engine.PlayerLib.isIdleActive] 的注释同源：
   * `stop` / `quit` 都只是**把命令投进队列**就返回，此刻 mpv 的解码线程、音频输出线程
   * 仍在跑，它们持有的是即将被 destroy 释放的互斥量与队列 ⇒ 必崩。
   * `PlayerActivity.cleanupMPV` 为此专门做了「quit → 轮询 `idle-active` → destroy」。
   *
   * 这里照抄那套收尾：**pause → quit → 等真正空闲 → destroy**。
   * 上限 160ms（比播放页的 100ms 宽松一点：预览是新开的实例，收尾路径更长），
   * 典型情况几毫秒就返回，最坏也不会把主线程卡成 ANR。
   */
  fun release() {
    val view = mpvView ?: return
    // 先置空引用：这样 surface 回调（surfaceCreated / pump）在这之后一律短路，
    // 不会在 destroy 之后再对句柄发命令。
    mpvView = null
    pendingUrl = null
    loadedUrl = null
    surfaceAttached = false

    runCatching { MPVLib.setPropertyBoolean("pause", true) }
    runCatching { MPVLib.command("stop") }
    runCatching { MPVLib.command("quit") }
    awaitIdle()
    runCatching { MPVLib.destroy() }

    host = null
    voName = null
    broken = false
    Log.i(TAG, "released")
    // view 只用于日志/排查，避免被误判为未使用
    if (view.holder == null) Log.w(TAG, "release: holder already gone")
  }

  /**
   * 等 mpv 真的进入 idle，而不是盲等固定时长。
   *
   * 读不到 `idle-active`（实例已经在收尾 / 属性不可读）时**视为已空闲** ——
   * 宁可少等，也不能因为读不到就把退出流程卡死。
   */
  private fun awaitIdle() {
    val deadline = SystemClock.uptimeMillis() + IDLE_WAIT_BUDGET_MS
    while (SystemClock.uptimeMillis() < deadline) {
      val idle =
        runCatching { MPVLib.getPropertyString("idle-active") }.getOrNull()
      if (idle == null || idle == "yes") return
      SystemClock.sleep(IDLE_POLL_INTERVAL_MS)
    }
    Log.w(TAG, "mpv did not go idle within ${IDLE_WAIT_BUDGET_MS}ms; destroying anyway")
  }

  /** 一行可读状态，排查「黑屏 / 一直转圈」时打到日志里。 */
  fun debugState(): String =
    if (mpvView == null) {
      "engine=null"
    } else {
      runCatching {
        "path=${MPVLib.getPropertyString("path")}, " +
          "dur=${MPVLib.getPropertyDouble("duration")}, " +
          "vo=${MPVLib.getPropertyString("vo")}, " +
          "sid=${MPVLib.getPropertyString("sid")}, " +
          "sub-delay=${MPVLib.getPropertyString("sub-delay")}, " +
          "attached=$surfaceAttached"
      }.getOrDefault("engine=error")
    }

  companion object {
    private const val TAG = "LabPreview"

    /** 等 mpv 进入 idle 的上限（毫秒）。与 `PlayerActivity.MPV_IDLE_WAIT_BUDGET_MS` 同量级。 */
    private const val IDLE_WAIT_BUDGET_MS = 160L

    /** 轮询间隔：mpv 处理命令的粒度远大于 5ms，再密也不会更准。 */
    private const val IDLE_POLL_INTERVAL_MS = 5L

    /**
     * 在 IO 线程把脚本 / 字体 / CA 证书备到内部存储。
     *
     * `Utils.copyAssets` 是纯文件拷贝（几 MB），但 `sub-fonts-dir`、`tls-ca-file`、
     * `glsl-shaders` 全是 `setOptionString` —— 过了 `initialize()` 再补就不生效。
     * 所以调用方要在建画面层**之前**先 await 它。
     */
    fun prepareAssetsOnIo(context: Context) {
      runCatching { MpvUtils.copyAssets(context.applicationContext) }
        .onFailure { Log.w(TAG, "copyAssets failed: ${it.message}") }
    }
  }
}
