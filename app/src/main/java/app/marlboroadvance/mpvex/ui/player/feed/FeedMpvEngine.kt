package app.marlboroadvance.mpvex.ui.player.feed

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.ui.player.MPVView
import java.io.File
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils as MpvUtils

/**
 * 视界流里的「mpv 兜底引擎」—— 只负责**当前这一条**的解码与出画面。
 *
 * ── 它是什么、不是什么 ──
 * 这里不是播放页的缩小版：没有控制器、没有播放列表、没有弹幕，只有「一块能放视频的
 * Surface」+ 几个取值/取值写入的方法。界面（标题、进度、按钮、手势）仍然全部由
 * [FeedScreen] 用 Compose 画 —— 用户看不到任何「换了内核」的痕迹。
 *
 * ── 为什么需要它 ──
 * 内置内核（ExoPlayer）的解复用器只认 mp4 / mkv / ts / flv 这一批，解码只能走系统
 * MediaCodec。wmv / asf / rmvb，以及本机没有硬件解码器、构建里也没打包 ffmpeg 扩展
 * 的编码，在上面两条路都走不通。mpv 自带完整 ffmpeg，是这类文件的唯一出路。
 *
 * ── mpv 是进程级单例，所以「只能有一条」 ──
 * `MPVLib` 一个进程只有一份句柄，不存在「两条同时用 mpv 播」的可能。本类的用法因此是
 * **同一时刻只服务一个下标**：
 * · 用户滑到某条解不了 → [play] 接管；
 * · 滑走 → [stop]，把内核让回空转状态，下一条照常走 ExoPlayer（默认引擎）；
 * · 再滑回来 → 重新 [play] 同一条（mpv 仍然只占这一份实例）。
 * 这也正是「mpv 只在个别条目上兜底、其它一律默认引擎」能成立的原因：
 * 只要同一时刻只有一条在用 mpv，单例就不是限制。
 *
 * ── 生命周期 ──
 * [ensureHost] 必须在**主线程**调用，且要在画面 View 被加进窗口**之前**完成
 * `initialize()` —— 那个方法内部才会执行 `MPVLib.create()`，而 View 一旦 attach，
 * 首帧就会回调 `surfaceCreated → MPVLib.attachSurface()`；在 create 之前调用它
 * 等于解引用空指针（native SIGSEGV，Java 层的 runCatching 拦不住）。
 * 这正是 PlayerActivity 里那段「内核初始化必须同步完成」注释的同一个坑。
 */
internal class FeedMpvEngine(
  private val context: Context,
) {

  /** 画面 View（外层那层 FrameLayout）；null = 还没建（或建失败） */
  private var host: View? = null

  private var mpvView: MPVView? = null

  /** 初始化走到了不可恢复的失败 —— 不再重试，避免反复碰 native */
  private var broken = false

  /** 当前交给 mpv 的地址（同一地址重复 [play] 直接忽略，防止无谓的重新打开） */
  private var currentUrl: String? = null

  /**
   * 已经点名、但**还没送进 mpv** 的地址。
   *
   * 为什么要攒一下：`vo=gpu-next` 的视频输出是在「第一次有画面要渲染」时按当前
   * Surface 建起来的。如果 Surface 还没 attach 就先 `playFile`，mpv 会拿不到窗口，
   * 这一次 vo 创建直接失败 —— 声音照出、画面永远黑着（和「硬解吐不出帧」一模一样的
   * 表象，但根因完全不同）。所以 [play] 只登记、[pump] 负责在 Surface 就绪后真正打开。
   */
  private var pendingUrl: String? = null

  /** 是否已经成功初始化（之后才允许碰任何 `MPVLib` 属性） */
  val ready: Boolean get() = mpvView != null

  /** 已建好的画面 View（交给 Compose 的 AndroidView 托管用） */
  fun hostView(): View? = host

  /**
   * 建好画面 View 并完成内核初始化。返回 null 表示这条路走不通（调用方应摆错误卡片）。
   *
   * ⚠️ 调用时机见类注释：[ensureHost] 返回后调用方才可以把返回的 View 交给 Compose。
   */
  fun ensureHost(inflater: LayoutInflater): View? {
    host?.let { return it }
    if (broken) return null

    // ① 脚本 / 字体 / shader 先就位：`glsl-shaders`、`sub-fonts-dir` 这类都是
    //    setOptionString，过了 initialize 再设就不生效了（与 PlayerActivity 的
    //    prepareMpvFilesOnIoThread 同一个原因）。拷失败不致命 —— 少的是自定义脚本，
    //    不影响到「能不能解码」这件事，所以只记日志不判死。
    runCatching { MpvUtils.copyAssets(context) }

    val root = runCatching { inflater.inflate(R.layout.view_feed_mpv, null, false) }
      .getOrNull() ?: run {
      broken = true
      return null
    }
    val view = root.findViewById<MPVView>(R.id.feedMpv) ?: run {
      broken = true
      return null
    }

    // ② create + 选项 + init 全在这次调用里（AAR 的实现），必须在 attach 之前完成
    val inited = runCatching { view.initialize(context.filesDir.path, context.cacheDir.path) }
      .isSuccess
    if (!inited) {
      broken = true
      return null
    }

    mpvView = view
    host = root

    // ④ `keep-open` 保持 MPVView.initOptions 里的 true（不要改成 false）：
    //    到片尾时 mpv 停在最后一帧、`eof-reached` 一直为真，[reachedEnd] 才有稳定的
    //    判据；改成 false 会在 EOF 瞬间把播放列表清掉，`eof-reached` 跟着复位，
    //    心跳很可能一次都读不到 ⇒ 播完就这么停在黑屏上。
    //    视界流的「接着下一条」由调用方 eof → goNext 完成，不需要 mpv 自己收尾。

    return root
  }

  /**
   * 把与 ExoPlayer 侧**同一套**的请求头交给 mpv。
   *
   * 为什么必须一致：strm 的真实媒体在网盘 CDN 上，这类直链的签名把 UA 与 Referer
   * 一起算进去。两个内核用不同的 UA 去拉同一个地址，就会一边能播、一边 403。
   */
  fun applyHeaders(headers: Map<String, String>) {
    if (mpvView == null) return
    headers["User-Agent"]?.let { ua ->
      runCatching { MPVLib.setPropertyString("user-agent", ua) }
    }
    headers["Referer"]?.let { referer ->
      runCatching { MPVLib.setPropertyString("http-header-fields", "Referer: $referer") }
    }
  }

  /** 开始播 [url]（同一地址重复调用无副作用） */
  fun play(url: String) {
    if (mpvView == null) return
    if (url == currentUrl) {
      // 已经在放这一条（从别的条目滑回来时会被再次调用）：只把暂停解掉
      runCatching { MPVLib.setPropertyBoolean("pause", false) }
      return
    }
    currentUrl = url
    pendingUrl = url
    // 立刻试一次：多数时候画面层早就 attach 好了（滑回来、重载都是在同一层上）
    pump()
  }

  /**
   * 把「已点名但还没打开」的地址真正送进 mpv —— Surface 没就绪就继续等。
   *
   * 为什么不在 `play` 里死等：Surface 的 attach 是系统回调（surfaceCreated），
   * 调用方（`switchToMpv`）是在 Compose 那一帧里设的标志，同一帧内 Surface 一定还
   * 没建好。所以这里采用**轮询**：由调用方 250ms 一次的心跳顺带调 [pump]，
   * 一旦 Surface 有效就立刻打开 —— 既不需要猜时序，也不会漏掉。
   */
  fun pump() {
    val view = mpvView ?: return
    val url = pendingUrl ?: return
    // BaseMPVView 继承自 SurfaceView：holder.surface 有效 = 已经 attach 上去，
    // mpv 建 vo 的时候能拿到窗口。
    val surfaceReady = runCatching { view.holder?.surface?.isValid == true }.getOrDefault(false)
    if (!surfaceReady) return
    pendingUrl = null
    runCatching { view.playFile(url) }
    runCatching { MPVLib.setPropertyBoolean("pause", false) }
  }

  /** 停止播放并把内核置回空闲（滑走时调用：声音必须立刻停） */
  fun stop() {
    if (mpvView == null) return
    currentUrl = null
    pendingUrl = null
    runCatching { MPVLib.command("stop") }
  }

  fun setPaused(paused: Boolean) {
    if (mpvView == null) return
    runCatching { MPVLib.setPropertyBoolean("pause", paused) }
  }

  fun isPaused(): Boolean =
    if (mpvView == null) true else runCatching { MPVLib.getPropertyBoolean("pause") ?: true }
      .getOrDefault(true)

  fun positionSec(): Double =
    if (mpvView == null) 0.0 else runCatching { MPVLib.getPropertyDouble("time-pos") ?: 0.0 }
      .getOrDefault(0.0)

  fun durationSec(): Double =
    if (mpvView == null) 0.0 else runCatching { MPVLib.getPropertyDouble("duration") ?: 0.0 }
      .getOrDefault(0.0)

  fun seekTo(sec: Double) {
    if (mpvView == null) return
    runCatching { MPVLib.command("seek", sec.coerceAtLeast(0.0).toString(), "absolute") }
  }

  /**
   * 画面出来了没有 —— 加载圈撤不撤的判据。
   *
   * 用 `video-params/w` 而不是 `video-params/aspect`：前者只有**真正解出第一帧**之后
   * 才有值，后者在某些容器上容器头一读完就能读到，拿它撤圈会撤出一个黑屏。
   */
  fun hasPicture(): Boolean =
    if (mpvView == null) false else runCatching {
      (MPVLib.getPropertyInt("video-params/w") ?: 0) > 0
    }.getOrDefault(false)

  fun isBuffering(): Boolean =
    if (mpvView == null) false else runCatching {
      MPVLib.getPropertyBoolean("paused-for-cache") ?: false
    }.getOrDefault(false)

  fun reachedEnd(): Boolean =
    if (mpvView == null) false else runCatching {
      MPVLib.getPropertyBoolean("eof-reached") ?: false
    }.getOrDefault(false)

  /**
   * 截图：写到 [target]，由调用方负责转存进相册。
   *
   * 为什么不能像 ExoPlayer 那条路一样从 View 上取像素：mpv 的输出是 SurfaceView 的
   * Surface，画面在独立图层里，`getBitmap()` 拿不到（见 view_feed_mpv.xml 的说明）。
   * mpv 自己能把当前帧按原分辨率写文件，效果更好。
   */
  fun screenshot(target: File): Boolean {
    if (mpvView == null) return false
    return runCatching {
      MPVLib.command("screenshot-to-file", target.absolutePath)
      true
    }.getOrDefault(false)
  }

  /**
   * 释放内核。
   *
   * ⚠️ 只调 `MPVLib.destroy()`，**不**调 `BaseMPVView.destroy()` —— 与 PlayerActivity
   * 的 `PlayerLib.destroy()` 同一条路径（那条路径是线上验证过的）。View 侧随窗口销毁
   * 自己会 detachSurface，重复销毁反而容易碰上已释放的句柄。
   */
  fun release() {
    stop()
    if (mpvView != null) runCatching { MPVLib.destroy() }
    mpvView = null
    host = null
    currentUrl = null
  }
}
