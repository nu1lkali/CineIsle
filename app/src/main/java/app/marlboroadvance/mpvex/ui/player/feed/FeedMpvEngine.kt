package app.marlboroadvance.mpvex.ui.player.feed

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceHolder
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

  /** 当前**期望**交给 mpv 的地址（同一地址重复 [play] 直接忽略，防止无谓的重新打开） */
  private var currentUrl: String? = null

  /** 已经真正下发过 `loadfile` 的地址。与 [currentUrl] 分开，避免「点名了但没加载」被误判成已在播。 */
  private var loadedUrl: String? = null

  /**
   * Surface 是否已经由 AAR 的 `surfaceCreated` attach 完成。
   *
   * ⚠️ 不能只看 `Surface.isValid`：从 GONE → VISIBLE 时，Surface 可能先变成 valid，
   * 而 `MPVView.surfaceCreated` 还没来得及 `attachSurface` / 恢复 `vo`。这时如果提前
   * `loadfile`，命令会落在一块尚未 attach 的输出面上 —— 画面停在旧帧，后续条目全部
   * 「加载了但不出画面」。我们的回调注册在 AAR 之后，所以这里置 true 时 attach 必已完成。
   */
  private var surfaceAttached = false

  /**
   * 已经点名、但**还没送进 mpv** 的地址。
   *
   * 为什么要攒一下：`SurfaceHolder.surface.isValid` 为 false 时 mpv 没有输出窗口，
   * 这时候把文件塞进去，`vo` 建不起来 ⇒ 有声音、画面全黑。所以 [play] 只登记，
   * 由 [pump] 在 Surface 真正就绪之后才执行 `loadfile`。
   */
  private var pendingUrl: String? = null

  /**
   * `initOptions` 选定的视频输出（`gpu` 或 `gpu-next`）。
   *
   * ⚠️ 必须自己记：`BaseMPVView.surfaceDestroyed()` 会把 `vo` 置成 `null`，
   * 而只有在「没有待加载文件」时它才会在 `surfaceCreated` 里恢复 —— 我们总会带着
   * 待加载文件，于是恢复这一步被跳过，重挂载后 `vo=null` ⇒ 界面黑着但有声音。
   * 这里在建层时就把它记下来，[pump] 每次挂好表面都核对一遍。
   */
  private var voName: String? = null

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
    // ⚠️ **必须覆盖 AAR 的 `idle=once`。**
    // `BaseMPVView.initialize()` 在 `init()` / `postInitOptions()` 之后无条件执行
    // `setOptionString("idle", "once")` —— 这是给 PlayerActivity「只放一个文件」的语义：
    // 第一条结束 / stop 后 mpv 进入一次性 idle，播放循环随即收尾。视界流是同一个
    // mpv 实例连续放多条，后续 `loadfile` 看似下发成功，实际不会再起播，画面就停在
    // 第一条的最后一帧（用户看到的「卡在第一帧」）。
    // AAR 用 setOptionString 在 init 后写，我们同样在 initialize 返回后写回去。
    runCatching { MPVLib.setOptionString("idle", "yes") }
    // 某些 AAR 版本会把 idle 暴露成运行期属性 / 只认 command；三条都尝试，
    // 失败不影响主路径（至少不会比 AAR 的 once 更差）。
    runCatching { MPVLib.setPropertyString("idle", "yes") }
    runCatching { MPVLib.command("set", "idle", "yes") }
    Log.i(
      "FeedMpv",
      "idle=" + runCatching { MPVLib.getPropertyString("idle") }.getOrNull(),
    )

    // 记下 initOptions 选定的输出（见 [voName] 的说明）：surfaceDestroyed 会把它
    // 置成 null，而我们不会走 AAR 那条「无待加载文件才恢复」的分支。
    voName = runCatching { MPVLib.getPropertyString("vo") }.getOrNull()

    // ⚠️ 这个回调必须加在 AAR 的 `initialize()` **之后**：AAR 在 initialize 里把自己
    // 注册为 SurfaceHolder.Callback，surfaceCreated 时先 attachSurface、恢复 vo；
    // 我们后注册，才能在这一刻安全地补一次 pump。只靠 250ms 心跳也能起播，但
    // 隐藏/显示画面层会让 Surface 反复销毁重建，心跳那一刻可能还没 ready ——
    // 后续条目切到 mpv 时就会一直等不到 `loadfile`。在 surfaceCreated 里立刻补载，
    // 起播时机与 Surface 生命周期对齐，不依赖轮询相位。
    runCatching {
      view.holder.addCallback(
        object : SurfaceHolder.Callback {
          override fun surfaceCreated(holder: SurfaceHolder) {
            // AAR 的 callback 先注册、先执行：走到这里 attachSurface 与 vo 恢复都已完成。
            surfaceAttached = true
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
    }.onFailure { Log.w("FeedMpv", "surface callback setup failed: ${it.message}") }

    // 回调注册时 Surface 可能已经创建过（例如后台恢复）；AAR 的 callback 先注册，
    // 此时 attach 已完成，可以直接认为可用。真正销毁时 surfaceDestroyed 会置回 false。
    surfaceAttached = runCatching { view.holder.surface.isValid }.getOrDefault(false)

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
    if (url == currentUrl && pendingUrl == null && loadedUrl == url) {
      // 已经真正加载过这一条（从别的条目滑回来时会被再次调用）：只把暂停解掉
      runCatching { MPVLib.setPropertyBoolean("pause", false) }
      return
    }
    currentUrl = url
    pendingUrl = url
    // 立刻试一次：多数时候画面层早就 attach 好了（滑回来、重载都是在同一层上）
    pump()
  }

  /**
   * 重新下发当前地址。
   *
   * 用于「已经发过 loadfile，但迟迟没有 duration / 画面」的看门狗：清掉 loadedUrl
   * 让 [play] 的幂等判断不再挡住重试，再由 [pump] 重新加载。
   */
  fun retryCurrent() {
    val url = currentUrl ?: return
    loadedUrl = null
    pendingUrl = url
    pump()
  }

  /**
   * 把「已点名但还没打开」的地址真正送进 mpv —— Surface 没就绪就继续等。
   *
   * ## 为什么不用 `BaseMPVView.playFile()`（这是 v143 黑屏的根因）
   *
   * 反编译 AAR 可以看到，`playFile()` 的实现只有一句 `this.filePath = path`；
   * 真正的 `command("loadfile", …)` 写在 `surfaceCreated()` 里，而且读一次就把
   * `filePath` 清空。也就是说：
   *
   * · 只有在 **Surface 还没创建**时调用它，文件才会被加载；
   * · Surface 已经就绪之后再调 ⇒ 只是把路径存进一个再也没人读的字段，
   *   mpv 那边**什么都没发生**（没有 path、没有 duration、没有画面）。
   *
   * 我们的画面层在切到 mpv 时早就 attach 过了（滑回来、重载都是同一层），
   * 走 `playFile` 就是那个「什么都没发生」的分支 —— 现象正是「黑屏 + 一直加载中」。
   * 所以这里一律走 `MPVLib.command("loadfile", …, "replace")`：它不看 Surface 状态，
   * 就绪后立刻生效。
   */
  fun pump() {
    val view = mpvView ?: return
    val url = pendingUrl ?: return
    // ⚠️ 必须同时满足：Surface 已 attach（AAR 已完成）且当前 surface 有效。
    // 只看 isValid 会在 GONE → VISIBLE 的瞬间把 loadfile 发给尚未 attach 的输出面。
    if (!surfaceAttached) return
    val surfaceReady = runCatching { view.holder?.surface?.isValid == true }.getOrDefault(false)
    if (!surfaceReady) return
    // ① 先把 vo 恢复回来：surfaceDestroyed 会把 vo 与 force-window 一起关掉，
    //    不恢复就是「挂上了 Surface 也不出画面」—— 有声音、全黑。
    ensureVo(view)
    pendingUrl = null
    // ② `replace` 而不是默认的追加：这里是「一屏一条」的语义，不留队列
    runCatching { MPVLib.command("loadfile", url, "replace") }
    runCatching { MPVLib.setPropertyBoolean("pause", false) }
    loadedUrl = url
    Log.w(
      "FeedMpv",
      "loadfile: $url (vo=${MPVLib.getPropertyString("vo")}, " +
        "attached=$surfaceAttached, valid=$surfaceReady)",
    )
  }

  /**
   * 把视频输出恢复成可用状态。
   *
   * 三条路径都要走对，少一条就是黑屏：
   * · `vo` 读写口径 —— 建层时 `initOptions` 里用的是 `setOptionString`（初始化**前**才
   *   生效），而运行期改 `vo` 只有 `setPropertyString` 管用；AAR 的 `surfaceDestroyed`
   *   也正是用 `setPropertyString("vo", "null")` 把它关掉的，对称着写回来。
   * · AAR 内部的 `voInUse` —— `setVo` 会更新它，之后 AAR 自己的 `surfaceCreated`
   *   恢复分支（`filePath == null` 时 `setPropertyString("vo", voInUse)`）才拿得到正确值。
   * · `force-window` —— `surfaceDestroyed` 顺手把它置成 `no`，重挂表面后不还原，
   *   mpv 会认为「没有窗口要画」。
   */
  private fun ensureVo(view: MPVView) {
    val current = runCatching { MPVLib.getPropertyString("vo") }.getOrNull()
    val usable = !current.isNullOrBlank() && current != "null" && current != "no"
    if (usable) {
      // 第一次成功出画面时把真实生效的 vo 记下来：`initOptions` 之后立刻读，mpv 可能
      // 还没建 VO（返回 null），那时 [voName] 是空的，这里补一次自愈。
      if (voName == null) voName = current
      runCatching { MPVLib.setPropertyString("force-window", "yes") }
      return
    }
    val target = voName ?: return
    runCatching { view.setVo(target) } // 同步 AAR 内部的 voInUse
    runCatching { MPVLib.setPropertyString("vo", target) } // 运行期真正恢复输出
    runCatching { MPVLib.setPropertyString("force-window", "yes") }
  }

  /**
   * 画面表面是否已经就绪。
   *
   * 调用方用它区分两种「一直加载中」：表面没就绪是**渲染链路**没起来（View 没挂上 /
   * 被裁掉），表面就绪却拿不到时长才是**源**的问题。两者的错误文案不该一样。
   */
  fun surfaceReady(): Boolean =
    surfaceAttached &&
      runCatching { mpvView?.holder?.surface?.isValid == true }.getOrDefault(false)

  /** 出问题时打一行可读状态：idle / 当前文件 / 时长 / vo / pause / eof。 */
  fun debugState(): String =
    if (mpvView == null) {
      "engine=null"
    } else {
      runCatching {
        buildString {
          append("idle=").append(MPVLib.getPropertyString("idle"))
          append(", path=").append(MPVLib.getPropertyString("path"))
          append(", dur=").append(MPVLib.getPropertyDouble("duration"))
          append(", vo=").append(MPVLib.getPropertyString("vo"))
          append(", pause=").append(MPVLib.getPropertyBoolean("pause"))
          append(", eof=").append(MPVLib.getPropertyBoolean("eof-reached"))
          append(", attached=").append(surfaceAttached)
          append(", valid=").append(surfaceReady())
        }
      }.getOrDefault("engine=error")
    }

  /** 停止播放并把内核置回空闲（滑走时调用：声音必须立刻停） */
  fun stop() {
    if (mpvView == null) return
    currentUrl = null
    pendingUrl = null
    loadedUrl = null
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
    // v217：显式 `+keyframes`。mpv 是进程级单例 —— 主播放器的 PlayerViewModel 会
    // 按时长**动态改写全局 `hr-seek`**（小于 2 分钟的片子会被切成精确 seek），
    // 视界流的短 clip 恰好全落在这个区间里，等于被那条逻辑误伤：拖一下每条
    // seek 都要「解到精确帧」。命令级 flag 优先于全局选项，写死 keyframes
    // 才不受污染；对短视频拖动来说，落点差不到一个 GOP，用户感知不出。
    runCatching { MPVLib.command("seek", sec.coerceAtLeast(0.0).toString(), "absolute+keyframes") }
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
