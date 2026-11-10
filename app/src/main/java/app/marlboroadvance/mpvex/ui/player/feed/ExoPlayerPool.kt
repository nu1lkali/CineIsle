package app.marlboroadvance.mpvex.ui.player.feed

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.marlboroadvance.mpvex.R
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 视界流的 ExoPlayer 实例池 —— 「无感滑动」的全部秘密都在这个类里。
 *
 * ── 为什么需要一「池」，而不是一个 ──
 * 单核时代（mpv）的做法是：整个页面只有一个解码实例，滑动时把新片子灌进去，
 * 「打开 → 探测容器 → 出首帧」这段时间屏幕是空的，肉眼可见地卡一下。
 *
 * ExoPlayer 没有这个限制：`ExoPlayer.Builder(context).build()` 每次调用都返回**彼此
 * 独立**的实例。于是可以让相邻几条各自持有一个实例，提前把自己缓冲到就绪：
 * 用户手指刚到位，那一条早就在手上握着了，切过去只是把 `playWhenReady` 打开，
 * 没有冷启动，画面立即跟上。
 *
 * ── 为什么 prepare 不等于占用解码器 ──
 * `prepare()` 会把容器信息读全、把前面一段数据拉下来，但**视频解码器要等真正渲染
 * 第一帧时才会创建**。也就是说，相邻那几条处于 prepared 但 `playWhenReady=false`
 * 的状态时，几乎不占 MediaCodec 额度。所以「同时挂着三条」是安全的；真正同时
 * 出画面的永远只有当前那一条。
 *
 * ── 本类管两套东西，务必分清（这里出过一次线上 bug）──
 * 1. **画面登记**（[surfaces] / [containers] / [videoSizes]）：跟着页面的 View 走。
 *    页面在 Composition 里就登记着，页面被回收就注销。
 * 2. **播放实例**（[players] / [boundTextures]）：跟着「当前页 ±1」的窗口走。
 *    页面不在窗口里就不该占实例。
 *
 * 这两套的**范围并不相等**：Pager 为了预组合相邻页，滑动时 Composition 里可能有
 * 4 页（甚至更多），而窗口严格只有 3 个。旧的写法在这里出了事 —— 窗口裁剪会把
 * 第 4 页的实例释放掉，可它的 View 还留在屏幕上；而 `AndroidView.update` 并不会
 * 因为「池里少了个实例」而重跑，于是那一页再也没人把画面接回去。
 * **症状：划了几下之后，某一条视频有声音、进度条在走、画面全黑。**
 *
 * 所以现在的规矩是：
 * - 裁剪只动实例，**绝不注销画面登记** —— 那一页还指着它的 TextureView 呢；
 * - 每次翻页都把窗口内三页的绑定**重新接一遍**，谁掉了都能续上；
 * - 再加一条 [ensureActiveBound] 由心跳兜底自愈。
 *
 * ── 显示尺寸为什么也归这里管 ──
 * 画面该铺满还是留黑边，取决于「容器方向 × 视频方向」两个变量，而这个判断需要
 * 视频尺寸（只有这里知道）与容器宽高（只有 View 知道）。把它统一放在
 * [applyVideoLayout] 里算，旋转（容器尺寸变化）与换片（视频尺寸变化）都只走这一条路。
 *
 * ── 生命周期约定 ──
 * 所有方法都必须在主线程调用：ExoPlayer 要求在自己创建时所绑定的 Looper 线程上操作，
 * 而这个池创建于 Activity 主线程的 Composition 里。页面回收时由 [detach] 解绑画面、
 * 但**不释放**实例（实例去留由 [onPageChanged] 的窗口裁剪决定），这样滑回去还能接着用。
 */
internal class ExoPlayerPool(
  private val context: Context,
  /**
   * 某一条出错。[kind] 决定调用方走哪条兜底路：
   * - [FeedFailureKind.DECODER] 换解码器可能救回来 ⇒ 先就地切 FFmpeg，再换内核；
   * - [FeedFailureKind.CONTAINER] 容器拆不开 ⇒ **换解码器一定没用**，直接换内核；
   * - [FeedFailureKind.OTHER] 网络 / IO / DRM ⇒ 换什么都不管用，只报错。
   *
   * 为什么不给一个「是否可恢复」的布尔：容器失败恰恰是"可恢复"的（mpv 能放），
   * 只是**恢复手段不同**。一个布尔会把这两条完全不同的路压成同一条，
   * 于是要么白重建一次播放器，要么把能播的片子报成放不了。
   */
  private val onError: (index: Int, reason: String, kind: FeedFailureKind) -> Unit,
  /**
   * `index` 这一条的**帧真的画到了屏幕上**时回调一次（同一个 index 只回调一次）。
   *
   * 注意是「画到屏幕上」，不是「渲染器交出去了」—— 两者的区别见 [frameOnScreen] 的注释。
   * 加载态的结束判据必须是这一个：早任何一步，用户看到的都是「转圈消失 → 黑屏 → 画面浮现」。
   */
  private val onFrameVisible: (index: Int) -> Unit,
  /**
   * 拉流时附带的默认请求头（`User-Agent` / `Referer` 等）。
   *
   * **这不是可选项，缺了就会导致 strm 播不了**（现象是「服务器拒绝了」）：
   * strm 条目的真实媒体在网盘 CDN 上（115 / 阿里云盘之类），这类直链的签名
   * **把 UA 和 Referer 一起算进去**。主播放器（mpv）一直显式带着
   * `EmbyClient.STREAM_USER_AGENT` + 服务器 origin 作 Referer，所以那边能播；
   * 视界流早先用裸 `MediaItem.fromUri(url)`，ExoPlayer 用自己的默认 UA 去拉，
   * 签名对不上 ⇒ CDN 直接 403。
   *
   * 两边的 UA **必须一致**：预解析直链用 A、真正拉流用 B，A≠B 一样会被拒。
   */
  private val defaultHttpHeaders: Map<String, String> = emptyMap(),
) {

  /** index → 实例。只包含当前窗口（±1）内的条目 */
  private val players = HashMap<Int, ExoPlayer>()

  /**
   * index → 画面 View。**这是页面的登记**，随页面的 composition 生命周期起落。
   *
   * 存的是「我们有引用的那个 TextureView」而不是 PlayerView —— 截图直接从它
   * getBitmap()，不需要再去问 PlayerView「你的画面是哪个 View」。
   */
  private val surfaces = HashMap<Int, TextureView>()

  /**
   * index → 承载画面的那个容器（view_feed_video.xml 的根 FrameLayout）。
   *
   * 画面的尺寸由我们自己设进 TextureView 的 LayoutParams，依据是**容器的宽高**
   * —— 所以必须拿到容器本身。同时在它身上挂一次布局监听：横竖屏切换、分屏、
   * 折叠屏展开都会让容器换一个尺寸，那时画面矩形要重算。
   */
  private val containers = HashMap<Int, View>()

  /** index → 内核报上来的视频尺寸；attach 可能晚于 onVideoSizeChanged，所以留着补算 */
  private val videoSizes = HashMap<Int, VideoSize>()

  /**
   * index → **已经真正下发过** `setVideoTextureView` 的那个 TextureView。
   *
   * 为什么要在 [surfaces] 之外再记一份：ExoPlayer 的「当前输出面」是它的内部状态，
   * 只进不出、没有 getter，我们问不到。这份账本替代了那个查询 ——
   * 有了它才能判断「实例还活着、View 也还在，可两者已经脱钩」这种状态。
   */
  private val boundTextures = HashMap<Int, TextureView>()

  /**
   * index → 装在那个实例上的 [Player.Listener]（清单 #20 建议项）。
   *
   * 只为释放时能成对摘掉而存在。listener 闭包捕获了 index 与本池，
   * 实例 released 之后若还留着它，等于留了一条「已释放的实例仍可能回调进来」的路。
   */
  private val listeners = HashMap<Int, Player.Listener>()

  /**
   * index → 这条实例的解码模式控制器。
   *
   * NextLib 从 0.14 起带了 `DecoderManager`：在**不重建播放器**的前提下，把视频解码
   * 在「平台解码器」和「FFmpeg 软解」之间来回切。它靠的是把落选那个渲染器的
   * `supportsFormat` 全部压成 0，再让 `DefaultTrackSelector` 重选一次轨道 ——
   * 这条路径没有 release/rebuild，所以不会有 `reloadCurrent` 那一下黑屏转圈。
   *
   * 只在 [build] 里 `attach` 成功后才登记（`attach` 会校验它的 controller 确实拥有
   * 这个 player，失败就不该假装能用）。
   */
  private val decoders = HashMap<Int, DecoderManager>()

  /**
   * index → 渲染器是否已经**产出过**第一帧（`Player.Listener.onRenderedFirstFrame`）。
   *
   * ⚠️ 它**不等于「用户看到了」**。这个回调是 ExoPlayer 把这一帧**交给输出面**的那一刻
   * 发出的（`releaseOutputBuffer` + 预定的上屏时刻），而 TextureView 还要等自己的绘制
   * 流程跑完 —— `onFrameAvailable` → `invalidate` → 下一个绘制帧里 `updateTexImage` →
   * 硬件层合成上屏 —— 中间隔着若干个 vsync。
   *
   * 曾经直接拿它撤加载圈，结果就是「转圈消失 → 一段黑屏 → 画面浮现」：圈撤掉了，可
   * 屏幕上还没有东西。所以它现在只用来给 [revealNotified] 排一个兜底定时器。
   */
  private val firstFrameRendered = HashSet<Int>()

  /**
   * index → 这一帧**真的已经画到那块 TextureView 上了**（`SurfaceTextureListener.onSurfaceTextureUpdated`）。
   *
   * 这才是「画面好了」的最终判据：它由 TextureView 自己的绘制流程在 `updateTexImage()`
   * 之后发出，意味着像素这一帧就会进到 View 的硬件层里。比 [firstFrameRendered] 晚几个
   * vsync，但只有它能把「撤加载圈」与「屏幕上有东西」对齐 —— 而那一下黑屏的闪烁，
   * 差的就是这几个 vsync。
   */
  private val frameOnScreen = HashSet<Int>()

  /**
   * index → **这一条的加载态已经结束**（对外只通知过一次 `onFrameVisible`）。
   *
   * ⚠️ 它跟 [frameOnScreen] **不是一回事**，这个区分是刻意的：
   *
   * · [frameOnScreen] 是**故障判据**，只有在 TextureView 真的收到帧时才写；
   * · 本集合是**界面判据**，兜底定时器到点也会写（不能让加载圈永远转）。
   *
   * 合在一起写过一次，代价是：「渲染器交出了首帧、但 TextureView 一直没收到」这种故障
   * 会被兜底当成「画面已好」，而 `detectNoVideoStall` 的第一条判据正是
   * `idx in frameOnScreen` ⇒ 此后它永远返回 null，**这条片子就再也没人管了**，
   * 用户看到的是一圈撤掉之后的永久黑屏。拆开之后，兜底只管撤圈，故障判定仍然有效。
   */
  private val revealNotified = HashSet<Int>()

  /** index → 上一次心跳读到的播放位置。用来判断「时间轴是真的在走」而不是「停在某个位置」 */
  private val probePos = HashMap<Int, Long>()

  /** index → 「时间轴开始连续推进」的时刻。暂停 / 回退 / seek 都会把它清掉 */
  private val advanceSince = HashMap<Int, Long>()

  /**
   * index → 兜底定时器：渲染器说「第一帧交出去了」，可 TextureView 迟迟没回报「我画上去了」。
   *
   * 正常情况两者只差一两个 vsync，定时器根本不会跑到。留着只为极端情形（这一页这一段
   * 恰好没走到绘制流程）不让转圈永远转下去。
   */
  private val revealFallbacks = HashMap<Int, Runnable>()

  /**
   * index → 已经判定过「有声音、没画面」并上报了。
   *
   * 去重是必须的：判定逻辑由 250ms 心跳驱动，不去重就是每秒报四次同样的错，
   * 错误卡片会一直闪。它在 [forgetFrameLedgers] 里随实例一起清 —— 换了实例
   * （软解重试 / 重载）就该重新判一次。
   */
  private val noVideoReported = HashSet<Int>()

  /**
   * index → 已经试过「只准软解」这一次重建。
   *
   * 只试一次是刻意的：软解也不行的时候必须收手，否则「硬解失败 → 软解也失败 →
   * 再重试」会变成无限重建，用户看到的是画面一直闪。
   */
  private val softRetried = HashSet<Int>()

  /**
   * index → 下次建实例时**只准用软件解码器**（硬解已经被证明出不了画面）。
   *
   * 与 [softRetried] 分开：前者是「允许试一次」的闸门，后者是「已经试过、从此只用软解」
   * 的状态。合并成一个的话，重试请求与重建之间就没有先后关系了。
   */
  private val forceSoftware = HashSet<Int>()

  /** 兜底定时器跑在主线程 —— 账本和回调本来就都约定在主线程上碰 */
  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * 已经**交给别的内核**（mpv）接管的条目下标。
   *
   * 视界流默认每条都走 ExoPlayer，但内置内核解不了的文件（wmv / asf / rmvb、本机
   * 没有对应解码器的编码）只能交给 mpv。这一条一旦交出去，池子就不该再为它建实例 ——
   * 建了也是白占一个解码器，还会在它滑回窗口时重建一次失败实例、把画面再黑一下。
   *
   * ⚠️ 与「窗口裁剪」是两回事：裁剪是临时的，滑回来还要接着用；这里是**永久的**，
   * 本次页面生命周期内这条都由 mpv 负责。
   */
  private val skipped = HashSet<Int>()

  /** 当前真正出声出画面的那一条 */
  private var activeIndex = -1

  /** 用来给 TextureView 打标记，避免每次重组都重装一次 SurfaceTexture 监听 */
  private val listenerTag = Any()

  /** 这一页是不是在「当前页 ±1」的预缓冲窗口里 */
  private fun inWindow(index: Int): Boolean =
    activeIndex >= 0 && abs(index - activeIndex) <= 1

  /**
   * 取到 [index] 对应的实例；没有就现建一个并开始 prepare。
   *
   * 幂等：同一个 index 反复调用不会重复创建，也不会打断已经准备好的缓冲。
   *
   * 返回 null = 这一条已经交给别的内核（见 [skipped]），池子不为它建实例。
   */
  private fun acquire(index: Int, url: String): ExoPlayer? {
    if (index in skipped) return null
    players[index]?.let { return it }
    val player = build(index, url, softOnly = index in forceSoftware)
    val listener = listenerFor(index)
    player.addListener(listener)
    // 记下这一对，释放时成对摘掉（清单 #20 建议项）。
    // 严格说 ExoPlayer.release() 自己会清空 listener 列表，所以这不算真泄漏；
 // 但显式成对 removeListener 让「谁装谁摘」在代码上成对可见 ——
    // 将来若改成复用实例（不 release 只 reset），漏摘就会立刻变成真泄漏。
    listeners[index] = listener
    players[index] = player
    return player
  }

  /**
   * 建一个播放实例并登记它的解码模式控制器。
   *
   * @param softOnly 已经确定硬解出不了画面，这一条**从头就用 FFmpeg 软解**
   *   （见 [retryInSoftware] 的 REBUILD 分支；就地切换那条路不走这里）。
   */
  private fun build(index: Int, url: String, softOnly: Boolean = false): ExoPlayer {
    // ── 解码能力的三层 ──
    // 1) 正常路径：平台 MediaCodec 优先，NextLib 的 FFmpeg Renderer 只在平台
    //    不支持该格式时兜底（EXTENSION_RENDERER_MODE_ON）。这样 H.264/HEVC 仍然
    //    走硬解，不会因为引入软解就整体变慢。
    // 2) 软解重试：把 FFmpeg 提到平台前面（PREFER），专治「硬解建得起来但不吐帧」
    //    （10bit HEVC、部分 AVI/MPEG-2）。平台侧的 SoftwareOnlyCodecSelector 仍然
    //    保留，FFmpeg 不支持的编码还能再试一次系统软解。
    // 3) 两者都不行：Activity 切 mpv（RMVB/WMV/VP6/Xvid 这类 NextLib 也没编进去的）。
    val extensionMode =
      if (softOnly) {
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
      } else {
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
      }
    // 解码模式控制器：正常路径给 AUTO。
    // ⚠️ AUTO **不是**「偏向某一方」，它的语义是「两个渲染器都启用、按能力值竞争」——
    //    也就是跟不加控制器时的 ON 行为完全一致（反编译 `enables()` 确认：
    //    `mode == AUTO || (mode == FFMPEG) == isFfmpeg`）。所以换成它不会让硬解退化。
    val decoderManager = DecoderManager(
      if (softOnly) DecoderMode.FFMPEG else DecoderMode.AUTO,
      // 音频不动：软解救「有声无画面」从来不需要动音频，动它反而会多一次重缓冲。
      DecoderMode.AUTO,
    )
    val renderersFactory =
      NextRenderersFactory(context)
        // 必须在 `build()` **之前**设进工厂：控制器要靠它接管渲染器集合。
        // ⚠️ 这一句必须是链上的**第一**个：`setEnableDecoderFallback` /
        // `setExtensionRendererMode` 都是父类方法、返回的是 `DefaultRenderersFactory`
        // 的静态类型，放在它们后面就调不到 `setDecoderManager` 了。
        .setDecoderManager(decoderManager)
        // ⚠️ 这一项**默认是 false**（media3 的 `DefaultRenderersFactory` 里
        // `enableDecoderFallback = false`）⇒ 首选解码器建不起来时直接抛
        // `DECODER_INIT_FAILED`，不会试后面的候选。打开后才会逐个回退。
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(extensionMode)
    if (softOnly) {
      renderersFactory.setMediaCodecSelector(SoftwareOnlyCodecSelector)
    }
    val builder = ExoPlayer.Builder(context).setRenderersFactory(renderersFactory)
    // 请求头必须挂在这里（DefaultHttpDataSource），而不是 `MediaItem` 的
    // `MediaItem.LocalConfiguration`—— 那些 header 只对**本地文件**的
    // `setDataSource(path, headers)` 有意义，http(s) 走的是 DataSource 层，
    // 挂错地方等于没挂（这坑很容易踩，因为两个 API 长得极像）。
    if (defaultHttpHeaders.isNotEmpty()) {
      val httpFactory =
        DefaultHttpDataSource.Factory()
          .setDefaultRequestProperties(defaultHttpHeaders)
          // strm 的真实地址常在**另一个主机**上（Emby → 网盘 CDN）。
          // 默认配置只在同协议内跟跨主机跳转，跨协议（http↔https）直接放弃，
          // 对这种链路会直接失败 ⇒ 显式打开。
          .setAllowCrossProtocolRedirects(true)
          // 连接超时别太长：视界流要快速失败并切走，卡住比报错更伤体验。
          .setConnectTimeoutMs(15_000)
          .setReadTimeoutMs(30_000)
      builder.setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory))
    }
    val player = builder
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
          .build(),
        // 第二个参数是「由 ExoPlayer 托管音频焦点」：来电或别的 App 放音乐时，
        // 它会替我们去请求 / 放弃焦点，不用自己 physics 一套 AudioManager 逻辑
        true,
      )
      .build()
      .apply {
        setMediaItem(MediaItem.fromUri(url))
        // 视界流是「刷完就走」，不循环单条
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
        prepare()
      }
    // attach 必须在 `build()` 之后、且在 player 自己的 application 线程上
    // （本池全部方法都在主线程，见类注释）。它会做两件事：接管 trackSelector、
    // 把当前模式应用到渲染器能力上。失败就别登记 —— 假装能用比不能用更糟。
    runCatching { decoderManager.attach(player) }
      .onSuccess { decoders[index] = decoderManager }
    return player
  }

  private fun listenerFor(index: Int) = object : Player.Listener {
    /**
     * 「渲染器产出了第一帧」—— 但**还没上屏**（原因见 [firstFrameRendered]）。
     *
     * 这里只把这件事记下来、并给 [frameOnScreen] 排一个兜底；真正通知调用方「画面好了」
     * 要等 TextureView 回报 [markFrameOnScreen]。
     */
    override fun onRenderedFirstFrame() {
      firstFrameRendered.add(index)
      scheduleRevealFallback(index)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
      // 视频尺寸是决定「铺满还是留边」的一半依据，先记下来再重算画面矩形。
      // 记下来而不是当场用完就丢，是因为 attach 可能发生在这个回调之后。
      videoSizes[index] = videoSize
      applyVideoLayout(index)
    }

    override fun onPlayerError(error: PlaybackException) {
      onError(index, describeError(error), failureKindOf(error))
    }
  }

  // ══════════════════ 画面尺寸 ══════════════════

  /**
   * 视频的显示宽高比（宽 / 高）；拿不到或数值不可信时返回 null。
   *
   * 直接用 `VideoSize.width / height`：media3 在构造它的时候**已经把
   * `unappliedRotationDegrees` 折算进去了**（竖拍视频会得到 1080×1920 而不是
   * 1920×1080 + 旋转 90°），所以这里不需要再自己转一次。
   */
  private fun ratioOf(index: Int): Float? {
    val size = videoSizes[index] ?: return null
    if (size.width <= 0 || size.height <= 0) return null
    // 防御：某些流的 pixelWidthHeightRatio 会是 0 或 NaN，算出来会把画面量成 0 尺寸
    // —— 那也是一片黑，跟绑定丢失长得一样，容易查错方向。
    val ratio = size.width * size.pixelWidthHeightRatio / size.height
    return ratio.takeIf { it.isFinite() && it > 0f }
  }

  /** 这一页的视频是不是竖屏内容（没有尺寸时不表态） */
  fun isPortraitAt(index: Int): Boolean = ratioOf(index)?.let { it <= 1f } ?: false

  /**
   * 按**屏幕当前尺寸**把画面摆好：等比缩放，完整显示，不裁掉任何一个像素。
   *
   * ── 规则：永远 contain，永远不丢画面 ──
   * 按容器宽高算出「刚好放得下」的最大等比矩形，多出来的地方留黑边。
   * 竖屏视频在竖屏上留一点上下边、横屏视频在竖屏上留上下边、竖屏视频转到横屏后
   * 留两侧边 —— 每种组合都能看到完整构图。
   *
   * 之所以不做「铺满」（cover + 裁溢出）：那必然要吃掉画面边缘，而视界流里什么
   * 比例的片子都有。之前按「同向铺满、异向留边」试过一版，实际效果是宽片在竖屏里
   * 只剩中间一条、人物被裁得只剩一张脸；转到横屏后又用旧方向的模式去铺，比例直接崩掉。
   * **只要画面永远是完整的，这两种难看就都不可能发生。**
   *
   * ── 为什么自己算而不是交给 AspectRatioFrameLayout ──
   * 它只有 `aspectRatio` + 一个**全局**的 resizeMode，而 `aspectRatio` 在 match_parent
   * （宽高都是 EXACTLY，本页正是如此）时根本不生效 —— 真正起作用的只剩那个全局开关，
   * 于是横竖屏切换后容器尺寸变了却没人重算，模式就停在旧方向上。
   * 现在尺寸由我们按容器宽高每次重算，容器一变就重算，不存在「停在旧方向」。
   *
   * 触发点有三处：拿到视频尺寸时、页面登记时、容器尺寸变化时（含旋转）。
   */
  private fun applyVideoLayout(index: Int) {
    val texture = surfaces[index] ?: return
    val container = containers[index] ?: return
    val containerW = container.width
    val containerH = container.height
    if (containerW <= 0 || containerH <= 0) return
    val ratio = ratioOf(index) ?: return

    val containerRatio = containerW.toFloat() / containerH.toFloat()
    val width: Int
    val height: Int
    if (ratio > containerRatio) {
      // 视频比容器「更宽」→ 宽度顶满，上下留黑边
      width = containerW
      height = (containerW / ratio).roundToInt()
    } else {
      // 视频比容器「更高」→ 高度顶满，左右留黑边
      height = containerH
      width = (containerH * ratio).roundToInt()
    }
    if (width <= 0 || height <= 0) return

    val lp = (texture.layoutParams as? FrameLayout.LayoutParams)
      ?: FrameLayout.LayoutParams(width, height)
    if (lp.width == width && lp.height == height) return
    lp.width = width
    lp.height = height
    lp.gravity = Gravity.CENTER
    texture.layoutParams = lp
  }

  // ══════════════════ 「画面好了」的判据 ══════════════════

  /**
   * 这一帧真的上屏了：记账，并且**第一次**发生时才通知上层。
   *
   * 去重是必须的 —— `onSurfaceTextureUpdated` 每出一帧都会被调一次（一秒几十次），
   * 而「画面好了」只需要对外说一次。
   */
  private fun markFrameOnScreen(index: Int) {
    revealFallbacks.remove(index)?.let { mainHandler.removeCallbacks(it) }
    // 只有这条路径能写故障判据 —— 它意味着像素真的进到 View 的硬件层里了
    frameOnScreen.add(index)
    notifyRevealed(index)
  }

  /**
   * 告诉上层「这一条的加载态可以结束了」——每个 index 只通知一次。
   *
   * 触发点有两个：真的出帧（[markFrameOnScreen]）、兜底定时器到点。后者**不写**
   * [frameOnScreen]，所以「兜底撤了圈、但其实一帧都没上屏」这种情况仍然会被
   * [detectNoVideoStall] 抓出来，交给兜底链处理。
   */
  private fun notifyRevealed(index: Int) {
    if (!revealNotified.add(index)) return
    // 撤遮罩必须放在「这一帧真的上屏」这一刻，而不是 `onVideoSizeChanged`：
    // 后者只说明拿到了宽高比、矩形即将被改写，那时撤掉就会把这次几何变化露出来 ——
    // 正是用户抱怨的「切条时画面缩一下」。上屏之后矩形一定已经摆好，露出来就是对的。
    unveil(index)
    onFrameVisible(index)
  }

  /**
   * 画面 View 上那层黑遮罩。
   *
   * 从 [surfaces] 里的 TextureView 反查它的兄弟节点，而不是另开一份账本：
   * 遮罩的生命周期与 TextureView 严格一致（同一个 `view_feed_video` 布局里），
   * 多记一份就多一处可能对不上的地方。
   */
  private fun veilOf(index: Int): View? {
    val parent = surfaces[index]?.parent as? ViewGroup ?: return null
    return parent.findViewById(R.id.feedVeil)
  }

  /**
   * 撤掉遮罩：淡出之后置 GONE。
   *
   * 用淡出而不是硬撤，是因为它撤的那一刻画面刚出第一帧，硬切在个别机型上还能被
   * 看成「闪了一下」；这段淡出足够把这一下抹平，又短到不像是刻意做的转场。
   */
  private fun unveil(index: Int) {
    val veil = veilOf(index) ?: return
    // 已经撤过了：别把动画重来一遍（心跳兜底与 onSurfaceTextureUpdated 都可能重复调到这里）
    if (veil.visibility == View.GONE) return
    veil.animate().cancel()
    veil.animate()
      .alpha(0f)
      .setDuration(VEIL_FADE_MS)
      .withEndAction { veil.visibility = View.GONE }
      .start()
  }

  /**
   * 重新盖上遮罩：画面要重新出帧了（SurfaceTexture 重建、实例释放后重建）。
   *
   * 盖上之后这一段里所有的几何重算都在背后发生，用户不会看到「满屏 → contain」
   * 那一下跳变。
   */
  private fun veilUp(index: Int) {
    val veil = veilOf(index) ?: return
    veil.animate().cancel()
    veil.alpha = 1f
    veil.visibility = View.VISIBLE
  }

  /**
   * 给 [index] 排一个兜底：渲染器已经把第一帧交出去了，但 TextureView 迟迟没回报
   * 「我画上去了」——正常情况两者只差一两个 vsync，所以这个定时器几乎从不生效。
   *
   * 留着它是为了不让加载圈卡死：万一这一页这一段确实没走到绘制流程（例如刚切过去、
   * 还在被裁剪），也不能让用户对着一个永远转的圈。
   */
  private fun scheduleRevealFallback(index: Int) {
    if (index in revealNotified || revealFallbacks.containsKey(index)) return
    // 兜底只负责「别让圈永远转」：它**不写** `frameOnScreen`，
    // 所以这一条若其实一帧都没上屏，故障判据仍然成立（见 [revealNotified]）。
    val runnable = Runnable {
      revealFallbacks.remove(index)
      notifyRevealed(index)
    }
    revealFallbacks[index] = runnable
    mainHandler.postDelayed(runnable, REVEAL_FALLBACK_MS)
  }

  /**
   * 把某个 index 的首帧账本连同兜底定时器一起作废。
   *
   * 触发点有三类，共同点是「之前那份『画面已经好了』的结论不再成立」：
   * 实例被释放（[releaseSlot]）、SurfaceTexture 被重建或被销毁、以及就地切软解。
   *
   * @param keepRendererEvidence true = **保留** [firstFrameRendered]。
   *   只有「Surface 重建 / 销毁」该用 true：换一块 SurfaceTexture 不代表渲染器没出过帧 ——
   *   恰恰相反，它出过帧才会在这里。真正的判据是「这个实例有没有解出过帧」，
   *   那是实例级的事实，不该被界面上换一块画布抹掉。
   *   抹掉的后果很具体：旋转后继续播，渲染器证据没了、而 `onRenderedFirstFrame`
   *   未必再回调一次 ⇒ 3 秒后把一条正在正常播放的片子判成「解码器不吐帧」。
   */
  private fun forgetFrameLedgers(index: Int, keepRendererEvidence: Boolean = false) {
    if (!keepRendererEvidence) firstFrameRendered.remove(index)
    frameOnScreen.remove(index)
    // 「已经撤过圈」这条也要一起作废：新实例/新解码模式下画面要重新出帧，
    // 留着它会让加载圈再也不出现（用户对着黑屏，界面上却像已经播起来了）。
    revealNotified.remove(index)
    // 「这条没有画面」的结论同样跟着实例走：换了实例（软解重试 / 重载）就要重新判，
    // 否则第一次的判决会一直压着，软解成功了却还挂着错误卡片。
    // ⚠️ 刻意**不**清 softRetried：那个是「已经给过一次软解机会」的闸门，
    //    清了就等于允许无限重试。
    noVideoReported.remove(index)
    revealFallbacks.remove(index)?.let { mainHandler.removeCallbacks(it) }
    // 画面要重新出帧了 —— 那片空白期间可能伴随一次「满屏 → contain」的几何重算，
    // 遮罩必须提前盖上，否则那一下就会被看见。
    veilUp(index)
  }

  /**
   * 给画面 View 装一次 SurfaceTexture 监听。
   *
   * 为什么非要装：`boundTextures` 这份账本记的是**View 对象引用**，可旋转（或任何
   * 让 View 离开再回到窗口的操作）会让系统**重建底层的 SurfaceTexture** —— View 引用
   * 丝毫未变，账本看起来完全一致，于是自愈逻辑认为「还连着」，而 ExoPlayer 手里那份
   * 旧 Surface 其实早已失效。**症状就是画面永久黑掉，但声音照常、进度条照走。**
   * 只有 SurfaceTexture 的生命周期回调能看见「重建」这件事，所以必须挂在这上面。
   */
  private fun installSurfaceListener(index: Int, texture: TextureView) {
    if (texture.tag === listenerTag) return
    texture.tag = listenerTag
    texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // 新 SurfaceTexture 就位：先把账本抹掉，再让 bind 真的下发一次
        // （账本还在的话 bind 会以为已经绑好，直接 return）
        boundTextures.remove(index)
        // 「已经上屏」这个结论属于**上一块** SurfaceTexture：它连同上面那一帧一起
        // 没了。不抹掉的话，旋转 / 重新 attach 之后加载圈会以为画面早就好了，
        // 撤掉圈露出的就是一片黑 —— 又是一次一模一样的闪烁。
        // ⚠️ 但渲染器出过帧这件事要留着（`keepRendererEvidence`）：它证明解码链路是通的，
        // 抹掉它会把「旋转后继续播放」误判成「解码器不吐帧」。
        forgetFrameLedgers(index, keepRendererEvidence = true)
        players[index]?.let { player -> bind(index, player) }
        applyVideoLayout(index)
      }

      override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // 输出面尺寸变了（旋转、分屏都会走到这里），画面矩形要跟着重算
        applyVideoLayout(index)
      }

      /**
       * 这一帧已经进到 View 的硬件层里了 —— 这才是「画面好了」。
       *
       * 它由 TextureView 的绘制流程在 `updateTexImage()` 之后发出，也就是撤掉加载圈的
       * 那一刻屏幕上确实有东西。每秒会被调很多次，靠 [markFrameOnScreen] 里的去重
       * 保证每个 index 只往上层通知一次。
       */
      override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        markFrameOnScreen(index)
      }

      override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // 返回 true 让系统释放这块 SurfaceTexture。同时必须显式断开 ExoPlayer 与它的
        // 连接 —— 否则它会继续往一块已释放的 Surface 上写。
        if (boundTextures[index] === texture) {
          boundTextures.remove(index)
          players[index]?.let { player -> runCatching { player.clearVideoTextureView(texture) } }
        }
        forgetFrameLedgers(index, keepRendererEvidence = true)
        return true
      }
    }
  }

  // ══════════════════ 接线 ══════════════════

  /**
   * 这个错误属于哪一类 —— 决定调用方走哪条兜底路。
   *
   * ⚠️ `PARSING_CONTAINER_UNSUPPORTED` 归到 [FeedFailureKind.CONTAINER] 而不是解码类：
   * 软解重试走的是「换渲染器」，而 NextLib 的扩展只接管**解码**，解复用仍然是
   * ExoPlayer 自带的 Extractor（AAR 里没有 libavformat）。容器不认识时换解码器不可能
   * 变好 ⇒ 白重建一次再失败，用户看到「转一圈、黑一下、又转一圈」才轮到 mpv。
   *
   * 但它也**不是**「报错完事」：这类失败换**内核**就能过（mpv 的 lavf 是全的），
   * 所以它必须被识别出来、并且直接指向 mpv —— 这正是 [FeedFailureKind] 存在的理由。
   */
  private fun failureKindOf(error: PlaybackException): FeedFailureKind =
    when (error.errorCode) {
      PlaybackException.ERROR_CODE_DECODING_FAILED,
      PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
      PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
      PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
      PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> FeedFailureKind.DECODER

      PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
      PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
      PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> FeedFailureKind.CONTAINER

      else -> FeedFailureKind.OTHER
    }

  private fun describeError(error: PlaybackException): String =
    when {
      // 容器层面就不认识（wmv / asf / rmvb / 某些 avi 变体…）——
      // ExoPlayer 只自带 mp4 / mkv / webm / ts / flv 这一批解复用器。
      // ⚠️ NextLib 的 FFmpeg 扩展**只补解码器（libavcodec），不补解复用器（libavformat）**
      //    ⇒ 容器不认识这条路上软解一点用都没有，只能换内核（mpv），它的 lavf 是全的。
      error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
        "无法播放：这个容器格式内置内核不认识"
      // 有容器、但找不到能解这条视频轨的解码器
      error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
        "无法播放：本机没有能解这个视频编码的解码器"
      error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
        "无法播放：本机没有可用的解码器"
      // 有解码器、但它自报「这个规格超出我的能力」（4K 10bit / 高 profile 常见）
      error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
        "无法播放：规格超出本机解码能力"
      error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "无法播放：网络连不上媒体服务器"
      error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        "无法播放：文件不存在或已被移除"
      error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
        "无法播放：服务器拒绝了这次请求"
      else -> "无法播放：${error.errorCodeName}"
    }

  /**
   * 把画面接上：让 [player] 渲染到 [index] 登记的那块 TextureView 上。
   *
   * 这是本类唯一的「接线」入口，三条路径都在用（页面登记时、翻页时、心跳自愈时）。
   * 账本一致时直接返回，所以重复调用是廉价的 —— 翻页时把窗口内三页都过一遍，
   * 谁掉了都能续上。
   *
   * 上一份绑定一定要显式 clear 掉：`setVideoTextureView` 只认后一个，
   * 旧的输出面不会被自动释放，留着就是一条占着 Surface 的野连接。
   */
  private fun bind(index: Int, player: ExoPlayer) {
    val texture = surfaces[index] ?: return
    if (boundTextures[index] === texture) return
    boundTextures[index]?.let { previous ->
      if (previous !== texture) runCatching { player.clearVideoTextureView(previous) }
    }
    runCatching { player.setVideoTextureView(texture) }
    boundTextures[index] = texture
  }

  /**
   * 页面把它的画面 View 交上来登记。
   *
   * 只有这一页**已经在窗口里**才顺手把画面接上；不在窗口里就只登记、不建实例 ——
   * Pager 预组合的页可能比窗口多，给每一页都留个解码器是没必要的。
   * 等它随翻页进入窗口时，[onPageChanged] 会替它接上。
   */
  fun attach(index: Int, url: String, texture: TextureView) {
    surfaces[index] = texture
    installSurfaceListener(index, texture)

    val container = texture.parent as? View
    if (container != null && containers[index] !== container) {
      containers[index] = container
      // 容器换了尺寸（横竖屏切换、分屏、折叠展开）就重算画面矩形。
      // 只比较实际占用尺寸，避免 setLayoutParams 引发的 requestLayout 打转。
      container.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (r - l != oldR - oldL || b - t != oldB - oldT) applyVideoLayout(index)
      }
    }

    if (inWindow(index)) acquire(index, url)?.let { bind(index, it) }
    // 尺寸可能先于 attach 到达（相邻那条在用户滑过来之前就已经 prepare 完），这里补一次
    applyVideoLayout(index)
  }

  /**
   * 页面被回收了。注意只解绑、**不释放实例** —— 页面只是暂时没显示它，
   * 用户滑回来时还得接着用，释放了那份缓冲就白攒了。
   *
   * 这里注销的是「画面登记」（[surfaces] / [containers]），实例那一半归窗口裁剪管。
   */
  fun detach(index: Int) {
    val texture = surfaces.remove(index) ?: return
    containers.remove(index)
    if (boundTextures[index] === texture) boundTextures.remove(index)
    players[index]?.let { player -> runCatching { player.clearVideoTextureView(texture) } }
  }

  /** 当前正在播的实例；没有则 null */
  fun active(): ExoPlayer? = players[activeIndex]

  /** 当前这条是不是竖屏内容（UI 据此决定「翻转」按钮是否可用） */
  fun activeIsPortrait(): Boolean = isPortraitAt(activeIndex)

  /** 当前活动页的加载态是否已经结束（真出帧或兜底撤圈，见 [revealNotified]） */
  fun activeFrameVisible(): Boolean = activeIndex in revealNotified

  /**
   * 这一条有没有「真的出过画面」的证据（渲染器交付过帧，或那一帧画上过屏）。
   *
   * 用途只有一个：**换内核之前的最后一道否决**。出过画面说明解码链路本身是通的，
   * 此时报出来的失败更可能是偶发（seek 到损坏段、网络抖动导致解析失败）——
   * 换内核既不一定救得了，还会把一条本来能放的片子推给旁路，用户看到的是
   * 「好好的突然换了内核」。
   */
  fun hasPictureEvidence(index: Int): Boolean =
    index in frameOnScreen || index in firstFrameRendered

  // ══════════════════ 解码兜底 ══════════════════

  /**
   * 判一次「有声音、没画面」：返回非空表示**这一条**已经确定出不了画面。
   *
   * ## 为什么需要这个判据
   *
   * 硬解不支持的编码分两种表现，只有一半会被 `onPlayerError` 抓到：
   * · **建不起解码器** → `DECODER_INIT_FAILED`，有回调，走错误卡片；
   * · **建得起来但吐不出帧** → **一声不响**，音频正常播放、进度条正常走、画面全黑。
   *   典型就是 AVI 里的 Xvid / DivX（AVI 容器不放 codec-specific data，MediaCodec
   *   拿不到 VOL 头，于是「解码器在跑、一帧都不出」），以及部分 10bit HEVC。
   *
   * 后者在视界流里的表现是**加载圈永远在转**（[frameOnScreen] 一直等不到），
   * 或者用户切到别的界面才发现「刚才那条只有声音」。
   *
   * ## 判据：时间轴真的在走 + 渲染器一帧都没交付
   *
   * 这两个条件缺一不可，理由是上一版各踩了一次：
   *
   * · **不能看「有没有上屏」**。`onSurfaceTextureUpdated` 只在有帧更新时回调，而
   *   `onSurfaceTextureAvailable/Destroyed` 会把上屏账本清空（旋转、重绑、
   *   `onSurfaceTextureDestroyed` 后再回来）。于是一条**暂停中的正常片子**账本是空的 ——
   *   拿它当判据就会「播得好好的被判死」，正是 v208 的真机反馈。
   *   渲染器侧的 `onRenderedFirstFrame`（[firstFrameRendered]）不会被 Surface 重建抹掉，
   *   它才是「解码器确实解出帧了」的硬证据。
   * · **不能用位置绝对值**。暂停在 5 分钟处、拖完进度停住，位置都大于任何阈值，
   *   但时间轴根本没在走。必须用「连续两次心跳位置都在增加」来证明它在播。
   *
   * 所以判定链是四道闸：渲染器没交过帧 → 正在播（READY + playWhenReady）→
   * 位置持续推进且不是 seek 跳跃 → 推进时长够了。前两道闸保证**绝不误伤已出帧的片子**，
   * 后两道保证「真的在走却没帧」这一种情况仍然会被抓出来。
   *
   * @return 首次判定时返回 [NoVideoStall]；同一个 index 只返回一次（去重见
   *   [noVideoReported]），已出过帧 / 时间轴没在走 / 没有实例都返回 null。
   */
  fun detectNoVideoStall(): NoVideoStall? {
    val idx = activeIndex
    if (idx < 0 || idx in noVideoReported) return null
    val player = players[idx] ?: run {
      resetAdvanceProbe(idx)
      return null
    }

    // ── 闸一：有过「帧真的出来了」的证据 ⇒ 解码链路是通的 ──
    // 两个来源都算：`onRenderedFirstFrame`（渲染器确实解出并交出了一帧，实例级事实）
    // 与 `onSurfaceTextureUpdated`（那一帧确实画到了这面 View 上）。
    // 它一旦成立，就**不可能**是「解码器不吐帧」—— 画面没显示属于 Surface 绑定 / 上屏问题，
    // 那是 `ensureActiveBound` 与首帧账本复位的活，不该换内核。
    // ⚠️ 这一闸是上一版最致命的遗漏：`onSurfaceTextureAvailable/Destroyed` 会把上屏账本
    // 清空（旋转、重绑、暂停），于是「画面正常但在暂停」的片子被当成「零帧」判死。
    if (idx in firstFrameRendered || idx in frameOnScreen) {
      resetAdvanceProbe(idx)
      return null
    }

    // ── 闸二：必须真的在播 ──
    // BUFFERING 是在等数据；playWhenReady=false 是用户自己暂停 / 相邻页没起播。
    if (player.playbackState != Player.STATE_READY || !player.playWhenReady) {
      resetAdvanceProbe(idx)
      return null
    }

    // ── 闸三：位置必须**持续在走**，且是「播放推进」而不是「seek 跳跃」 ──
    // 两次心跳之间正常只推进 ~250ms；一次跳几秒说明发生了 seek，重新起算。
    val pos = player.currentPosition
    val last = probePos[idx]
    probePos[idx] = pos
    if (last == null || pos <= last || pos - last > NO_VIDEO_MAX_TICK_ADVANCE_MS) {
      resetAdvanceProbe(idx, keepPos = true)
      return null
    }
    val now = SystemClock.elapsedRealtime()
    val advancingSince = advanceSince.getOrPut(idx) { now }

    // ── 闸四：推进时长够了，才结合「轨道有没有人认」选阈值 ──
    val videoGroups =
      runCatching { player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO } }
        .getOrNull()
    // 查不到轨道就按「有视频、且被支持」处理：宁可退回长阈值，
    // 也不要因为一次查询失败就宣布「这不含视频」—— 那会把能放的片子判死。
    val hasVideoTrack = videoGroups?.isNotEmpty() ?: true
    val videoSupported = videoGroups?.any { it.isSupported } ?: true
    val neededAdvance =
      if (hasVideoTrack && !videoSupported) {
        NO_VIDEO_UNSUPPORTED_ADVANCE_MS
      } else {
        NO_VIDEO_STALL_ADVANCE_MS
      }
    if (now - advancingSince < neededAdvance) return null

    noVideoReported.add(idx)
    resetAdvanceProbe(idx)
    return NoVideoStall(idx, hasVideoTrack)
  }

  /**
   * 清掉「时间轴在不在走」的探针。
   *
   * @param keepPos true = 保留上一次位置（只重置推进起点）。用在「这次看不出在走」的分支，
   *   否则下一次心跳会因为 `last == null` 又白丢一拍。
   */
  private fun resetAdvanceProbe(index: Int, keepPos: Boolean = false) {
    advanceSince.remove(index)
    if (!keepPos) probePos.remove(index)
  }

  /**
   * 让「当前这条」改用**软件解码**再试一次。
   *
   * ── 为什么不再一律重建播放器 ──
   *
   * 旧实现在这里只置一个标记，然后由调用方 `reloadCurrent` 整个重建：`release` +
   * `build` + `prepare`，用户看到的是「黑一下 → 转圈 → 再起播」。软解救不回来的片子
   * 还要再走一遍同样的代价才轮到 mpv，等于连续黑两次。
   *
   * NextLib 的 `DecoderManager` 允许**就地**把视频解码从平台切到 FFmpeg：它把落选渲染器
   * 的能力压成 0，让 trackSelector 重选一次轨道，播放器实例、缓冲、位置全都还在。
   * 所以现在优先走就地切换，只有控制器没 attach 上时才退回重建。
   *
   * ── 一个必须先挡掉的坑 ──
   *
   * `DecoderMode.FFMPEG` 会把**平台渲染器整个关掉**。如果这个编码不在
   * `FfmpegLibrary` 的 mime 表里（表很小，视频只有 avc/hevc/mpeg/mpeg2/vp8/vp9/av01），
   * 切过去的结果是「一个视频渲染器都没有」⇒ 视频轨被丢掉、**有声无画面**。
   * 那正是我们想修的症状，不能自己再造一个。所以切之前先问一次
   * [FfmpegLibrary.supportsFormat]，不支持就直接判死交给 mpv —— 那一步不可救。
   *
   * @return [FeedSwRetry.IN_PLACE] 已就地切到软解（**不要**重建）；
   *   [FeedSwRetry.REBUILD] 需要调用方重建一次实例；
   *   [FeedSwRetry.NONE] 这条已经没救（或已经试过），只能换内核。
   */
  fun retryInSoftware(): FeedSwRetry {
    val idx = activeIndex
    if (idx < 0) return FeedSwRetry.NONE
    val player = players[idx] ?: return FeedSwRetry.NONE
    // 同一个 index 只放行一次：否则「硬解失败 → 软解也失败」会变成无限重建，
    // 用户看到的是画面一直闪。
    if (!softRetried.add(idx)) return FeedSwRetry.NONE

    val mime = videoMimeOf(player)
    if (mime != null && !ffmpegHandles(mime)) {
      noVideoReported.add(idx)
      return FeedSwRetry.NONE
    }

    val manager = decoders[idx] ?: run {
      forceSoftware.add(idx)
      return FeedSwRetry.REBUILD
    }
    val switched =
      runCatching { manager.selectVideoDecoder(DecoderMode.FFMPEG) }.isSuccess
    if (!switched) {
      forceSoftware.add(idx)
      return FeedSwRetry.REBUILD
    }
    // 换了渲染器 ⇒ 要重新出帧：首帧账本作废、遮罩重新盖上，
    // 否则旧的「已经出过画面」会压着加载态，而画面上什么都没有。
    forgetFrameLedgers(idx)
    return FeedSwRetry.IN_PLACE
  }

  /** 当前选中的视频轨 mime；拿不到就 null（调用方据此选择「乐观地试一次」） */
  private fun videoMimeOf(player: ExoPlayer): String? =
    runCatching {
      player.currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
        ?.getTrackFormat(0)
        ?.sampleMimeType
    }.getOrNull()

  /**
   * NextLib 的 FFmpeg 扩展认不认这个 mime。
   *
   * 这张表是**扩展渲染器的唯一入口**：表里没有的 mime，`FfmpegVideoRenderer.supportsFormat`
   * 直接返回「不支持」，跟 FFmpeg 到底编进了多少解码器无关（打进包里的 libavcodec 是
   * mpv 那份，解码器是全的 —— 缺的只是这张映射表）。
   */
  private fun ffmpegHandles(mime: String): Boolean =
    runCatching { FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime) }
      .getOrDefault(false)

  /**
   * 抄一份 [index] 实例此刻的播放状态。
   *
   * 翻页时靠它**在同一帧内**把 UI 状态填好。相邻那条在用户滑过来之前就已经
   * prepared 好，真实时长、进度、首帧状态全都在手边 —— 不抄这一份的话，UI 只能
   * 先把 `durationSec` 归零（进度条当场从滑块塌成细线），再等 250ms 的心跳把它
   * 填回来，那一缩一放正是用户抱怨的「每切一条就闪一下」。
   *
   * 返回 null 表示这一页还没有实例（刚拉进来、还没进窗口），调用方应据此回到加载态。
   */
  fun snapshotOf(index: Int): FeedPlaybackSnapshot? {
    val player = players[index] ?: return null
    return FeedPlaybackSnapshot(
      durationMs = player.duration.takeIf { it > 0L } ?: 0L,
      positionMs = player.currentPosition.coerceAtLeast(0L),
      playing = player.playWhenReady,
      frameVisible = index in revealNotified,
    )
  }

  /**
   * 该页正在用的 TextureView —— 截图直接从它 getBitmap()。
   *
   * 这是我们自己在布局里声明的那个 View，不是从 PlayerView 内部猜出来的，
   * 所以只要视频在播，它就一定有画面。
   */
  fun textureOf(index: Int): TextureView? = surfaces[index]

  /**
   * 心跳兜底：确认「当前这条」的实例确实还接在屏幕上那块 TextureView 上。
   *
   * 为什么还需要它 —— 页面 View 与播放实例是分开管理的，任一条边被别处重置
   * （页面被 Compose 重建、实例被窗口裁剪后重建），都可能留下「两边都活着、
   * 但中间断了一根线」的状态。上面几处已经把主路径堵住了，这里是最后一道保险：
   * 账本一致时它什么都不做，所以每 250ms 调一次也不花什么钱。
   */
  fun ensureActiveBound() {
    val idx = activeIndex
    if (idx < 0) return
    if (surfaces[idx] == null) return
    players[idx]?.let { bind(idx, it) }
  }

  /**
   * 切换「哪一条在出声出画面」。
   *
   * 非活动实例保持 prepared 但 `playWhenReady=false`，并把音量压到 0：即便底层
   * 误把它们带起来，也不会串音到当前这条里。
   */
  fun setActive(index: Int) {
    activeIndex = index
    applyPlaybackState()
  }

  private fun applyPlaybackState() {
    players.forEach { (idx, player) ->
      val isActive = idx == activeIndex
      if (player.playWhenReady != isActive) player.playWhenReady = isActive
      if (isActive && player.volume != 1f) player.volume = 1f
      if (!isActive && player.volume != 0f) player.volume = 0f
    }
  }

  fun setPlayWhenReady(play: Boolean) {
    players[activeIndex]?.playWhenReady = play
  }

  /**
   * 页面切到 [center]：裁掉窗口外的实例，并把窗口内的补齐、接线、准备好。
   *
   * 这一调用的时机很关键 —— 必须在**新页面还没成为活动页之前**（或者同一帧内），
   * 相邻那条就被 make ready，才有「滑过去当天就有画面」的效果。
   */
  fun onPageChanged(center: Int, urlOf: (Int) -> String?) {
    activeIndex = center
    // ① 裁掉窗口外的实例。只动 players，**不动 surfaces** —— 那些页的 View 可能
    //    还在 Composition 里（Pager 会多预组合一页），它们仍然指着自己的 TextureView，
    //    一旦把它们从「画面登记」里抹掉，这一页就再也没人接得回画面了。
    players.keys.filter { abs(it - center) > 1 }.forEach(::releaseSlot)
    // ② 窗口内三页：补齐实例并把画面接上。刚被裁掉又转回来的页在这里重新接线，
    //    这就是「滑了几下之后某页黑屏」的正解。
    (center - 1..center + 1).forEach { idx ->
      val url = urlOf(idx) ?: return@forEach
      acquire(idx, url)?.let { bind(idx, it) }
      applyVideoLayout(idx)
    }
    applyPlaybackState()
    // ③ 回到「已经播完」的那一页：把它拨回开头重播。
    //    位置必须在这一调里同步改掉（`seekTo` 会立刻更新 currentPosition），
    //    因为紧接着的心跳就要读它 —— 详见 [restartIfEnded]。
    restartIfEnded(center)
  }

  /**
   * 「这一页上一次已经播到片尾」→ 拨回开头重播，返回是否真的重播了。
   *
   * ## 为什么必须有这一手
   * 一条片子放完、自动切到下一条之后，**这一页的实例并没有被销毁**（它还在
   * 「当前页 ±1」的窗口里，停在 `STATE_ENDED`，位置就在片尾）。用户往回滑，
   * 落到的就是这个实例。不重置的话有两层坏处：
   * · 进度条一进来就是满的；
   * · 250ms 心跳读到 `STATE_ENDED`，再走一次「播完 → 切下一条」——
   *   用户看到的就是**滑回去瞬间又被弹走**。
   *
   * ## 为什么判据要「位置优先」
   * 只看 `playbackState == STATE_ENDED` 不够稳：`seekTo` 之后 `currentPosition`
   * 是**同步**变成 0 的，而 `playbackState` 要走内部消息线程，异步才从
   * `STATE_ENDED` 转成 `STATE_BUFFERING`。这一段缝隙里若心跳先跑，看到的就是
   * 「ENDED + 位置 0」，按状态判会再切一次。所以位置已经回到开头的，
   * 一律当作**正在重播**，不算播完。
   *
   * 只对**当前页**调用 —— 邻居页停在哪都无所谓，它们还没上屏。
   */
  fun restartIfEnded(index: Int): Boolean {
    val player = players[index] ?: return false
    val dur = player.duration.takeIf { it > 0L } ?: 0L
    val pos = player.currentPosition.coerceAtLeast(0L)
    // 已经拨回开头（或本来就从头）⇒ 不是「播完态」，什么都不做。
    // 这一条同时保证本方法是幂等的：重复调用不会把刚播到一半的片子倒回去。
    val atStart = pos < RESTART_HEAD_TOLERANCE_MS
    val ended = if (dur > 0L) {
      pos >= dur - END_TOLERANCE_MS
    } else {
      player.playbackState == Player.STATE_ENDED && !atStart
    }
    if (!ended) return false
    player.seekTo(0L)
    // 当前页要真的播起来：`STATE_ENDED` 下 `seekTo` 只是把位置挪回开头，
    // 是否继续播仍取决于 playWhenReady（非活动实例一律 false，见 applyPlaybackState）。
    if (index == activeIndex) player.playWhenReady = true
    return true
  }

  /**
   * 重载当前这条：丢掉现有缓冲，从同一个地址重新开始。
   *
   * 必须**先把 TextureView 的引用摘出来**再 releaseSlot —— 后者会把实例一侧清掉，
   * 之后再想「把画面绑回新实例」就无据可依了。之前的写法正是栽在这里：
   * releaseSlot 之后画面仍然指着那个**已经被 release 的旧实例**，新实例在后台
   * 空转 —— 表现就是「点了重载，画面直接卡死」。
   */
  fun reloadCurrent(url: String) {
    val idx = activeIndex
    if (idx < 0) return
    val texture = surfaces[idx]
    releaseSlot(idx)
    val player = acquire(idx, url) ?: return
    if (texture != null) {
      // surfaces 里那份是页面的登记，releaseSlot 不碰它；这里把接线接回去即可
      boundTextures.remove(idx)
      bind(idx, player)
    }
    applyVideoLayout(idx)
    applyPlaybackState()
  }

  /**
   * 把 [index] 交给**别的内核**（mpv）接管。
   *
   * 两件事一起做，缺一条都会留下「两边都以为自己在负责」的乱账：
   * ① 记进 [skipped]，此后窗口裁剪 / 翻页回补都不会再替它建 ExoPlayer；
   * ② 立刻释放它现有的实例 —— 解码器是系统级稀缺资源，mpv 那边马上要建自己的
   *    软解上下文，这边还攥着不放，低端机上就会出现「切过去也放不出来」。
   *
   * 画面登记（[surfaces]）**不动**：那一页的 View 还在，只是以后由 mpv 的另一层
   * SurfaceView 出画面。
   */
  fun handOffToOtherEngine(index: Int) {
    if (index < 0) return
    skipped.add(index)
    releaseSlot(index)
  }

  /**
   * 释放某个 index 的**实例**。
   *
   * 刻意不碰 [surfaces] / [containers] / [videoSizes] —— 那三份是页面的登记，
   * 只要页面还在 Composition 里就还指着它们；等这一页再进窗口时，
   * [bind] 会拿它把画面接回去。
   */
  private fun releaseSlot(index: Int) {
    val player = players.remove(index) ?: return
    val texture = boundTextures.remove(index)
    // 解码模式控制器绑在这个实例上，实例没了它就没有意义 —— 不 detach 会留着
    // 一份指向已释放 player 的 trackSelector 引用
    decoders.remove(index)?.let { manager -> runCatching { manager.detach() } }
    // 首帧账本跟着实例走：实例都没了，它渲染过、上屏过的那一帧自然也不作数了
    forgetFrameLedgers(index)
    resetAdvanceProbe(index)
  runCatching {
    if (texture != null) player.clearVideoTextureView(texture)
      // 先摘 listener 再 release：listener 闭包捕获了 index 与本池，
      // 留着它等于让一个已释放的实例继续可能回调进来
      listeners.remove(index)?.let { player.removeListener(it) }
      player.stop()
      player.release()
    }
  }

/** 退出页面：全部释放。顺序是先解画面、再摘listener、再 stop、最后 release，反了会有 native 线程踩已释放的句柄 */
  fun releaseAll() {
    players.forEach { (index, player) ->
    runCatching {
        boundTextures[index]?.let { player.clearVideoTextureView(it) }
        listeners.remove(index)?.let { player.removeListener(it) }
        decoders.remove(index)?.let { manager -> runCatching { manager.detach() } }
      player.stop()
        player.release()
      }
    }
    listeners.clear()
    decoders.clear()
    surfaces.clear()
    containers.clear()
    videoSizes.clear()
    boundTextures.clear()
    firstFrameRendered.clear()
    frameOnScreen.clear()
    revealNotified.clear()
    probePos.clear()
    advanceSince.clear()
    revealFallbacks.clear()
    noVideoReported.clear()
    softRetried.clear()
    forceSoftware.clear()
    skipped.clear()
    mainHandler.removeCallbacksAndMessages(null)
    players.clear()
    activeIndex = -1
  }

  /** 占了多少个实例 —— 上限审查用，正常应该是 3 个（上/当前/下） */
  val size: Int get() = players.size
}

/**
 * 某一页实例此刻的播放状态快照。
 *
 * 存在的唯一理由是**翻页时要在同一帧内把 UI 状态填准**：真实值其实就在相邻那个
 * 已经准备好的实例上，没必要让 UI 先回到「未知」再等心跳填回来 —— 那个中间态
 * 就是切条时进度条闪一下的根源。
 */
internal data class FeedPlaybackSnapshot(
  val durationMs: Long,
  val positionMs: Long,
  val playing: Boolean,
  /** 这一帧是否已经**真的画到屏幕上**；加载态据此决定撤不撤 */
  val frameVisible: Boolean,
)

/**
 * 「渲染器交出第一帧」之后最多再等多久，就认定画面已经好了。
 *
 * 只作兜底用：正常路径由 `TextureView.onSurfaceTextureUpdated` 回报，通常只差一两个
 * vsync（几十毫秒）。这个值给得远大于它，是为了「宁可多转一下圈，也不要撤早了露黑屏」
 * 的前提下仍不至于卡死。
 */
private const val REVEAL_FALLBACK_MS = 1200L

/**
 * 出帧后遮罩淡出的时长。
 *
 * 遮罩撤除的那一刻画面刚出第一帧，硬切在个别机型上还能被看成「闪了一下」；
 * 这个长度足够把这一下抹平，又短到不像是刻意做的转场。
 */
private const val VEIL_FADE_MS = 140L

/**
 * 播放位置推进到这个值、画面还一帧都没上屏，就判定「视频轨出不了画面」。
 *
 * 4 秒是权衡：太短会把「网络慢、音频先到、视频还在缓冲」误判成解码失败
 * （视界流直接给人切软解，反而更慢）；太长用户已经对着黑屏听了好几秒声音。
 * 判据里还额外要求 `playbackState == STATE_READY`，所以真实缓冲中不会走到这里。
 */
/**
 * 「时间轴连续推进了多久、且渲染器一帧都没交付」才判定解码器不吐帧。
 *
 * ⚠️ 这里刻意**不用位置绝对值**，而是用心跳之间的推进量累计（见 [ExoPlayerPool.detectNoVideoStall]）。
 * 用绝对值判会误伤三种完全正常的情况：
 * - **暂停**在 5 分钟处：位置 > 阈值，但时间轴根本没在走；
 * - **拖动进度**后停住：同上；
 * - **旋转 / 重绑 SurfaceTexture** 之后暂停：首帧账本刚被清空，新帧要等恢复播放才有。
 * 上一版就是拿绝对位置判的，结果是「播放正常的片子被判死、还去切内核」。
 */
private const val NO_VIDEO_STALL_ADVANCE_MS = 3_000L

/**
 * 视频轨**没有任何渲染器支持**时的推进阈值 —— 比 [NO_VIDEO_STALL_ADVANCE_MS] 短。
 *
 * 这一类的结论在轨道映射那一刻就成立了（`Tracks.Group.isSupported == false`），
 * 不会因为多播几秒而变好，等的只是「起播 / seek 后轨道重选」那段未定态。
 */
private const val NO_VIDEO_UNSUPPORTED_ADVANCE_MS = 1_200L

/** 两次心跳之间不可能推进这么多 —— 超过就说明是 seek，不是播放（见 [ExoPlayerPool.detectNoVideoStall]） */
private const val NO_VIDEO_MAX_TICK_ADVANCE_MS = 3_000L

/**
 * 距片尾多近算「已经播完」。
 *
 * 与心跳里那套提前量同源：视界流的自动切换本来就允许在片尾前 500ms 抢跑（超短视频
 * 收紧到 50ms），所以用户滑回一页时，位置落在片尾前 500ms 内**同样**应按「已播完」
 * 处理 —— 否则他会看到片子卡在最后一帧不动，而不是从头重播。
 */
private const val END_TOLERANCE_MS = 500L

/**
 * 位置小于这个值就算「已经在开头」。
 *
 * 用途只有一个：区分「ENDED 且位置还没被拨回去」与「刚拨回去、状态还没从 ENDED
 * 转出来」这两种情况。后者的 `playbackState` 会滞后几十毫秒，按状态判就会把一次
 * 正常的重播误判成播完、再切走一次（见 [ExoPlayerPool.restartIfEnded]）。
 */
private const val RESTART_HEAD_TOLERANCE_MS = 1_000L

/**
 * 一次失败的**归属** —— 决定兜底链怎么走。
 *
 * 分三类而不是「可恢复 / 不可恢复」两类的理由见 [ExoPlayerPool] 构造函数里 `onError` 的说明。
 */
internal enum class FeedFailureKind {
  /** 解码层：换解码器（就地切 FFmpeg）可能救回来 */
  DECODER,

  /** 解复用层：容器拆不开，换解码器一定没用，只能换内核 */
  CONTAINER,

  /** 网络 / IO / DRM：换什么都没用，如实报错 */
  OTHER,
}

/**
 * [ExoPlayerPool.retryInSoftware] 的结果。
 *
 * 三个状态是必要的：调用方要据此决定「什么都不做」「重建一次」还是「换内核」，
 * 用一个布尔表达不了「已经就地切好了、别再动它」这个中间态。
 */
internal enum class FeedSwRetry {
  /** 这条没救了（或已经试过一次），直接换内核 */
  NONE,

  /** 已经就地切到 FFmpeg 软解，**不要**重建实例 */
  IN_PLACE,

  /** 需要调用方重建一次实例（控制器没接上时的退路） */
  REBUILD,
}

/**
 * 「有声音、没画面」的一次判定结果。
 *
 * @param index 出问题的那一条
 * @param hasVideoTrack 容器里到底有没有视频轨。true = 有轨但解不出（换软解可能救回来），
 *   false = 文件本身就是纯音频（换什么内核都没有画面）
 */
internal data class NoVideoStall(
  val index: Int,
  val hasVideoTrack: Boolean,
)

/**
 * 只用**软件解码器**的选择器 —— 硬解已经被证明「建得起来但吐不出帧」时的第二次机会。
 *
 * 过滤后为空就退回完整列表（原样交给上层）：有的编码在部分机型上压根没有软解
 * （比如某些 AV1 / HEVC 10bit），这时宁可再试一次硬解，也不能直接判死 ——
 * 「没有解码器」这个错误比「可能还是黑屏」更糟，它连一帧都不给。
 *
 * ⚠️ 为什么不是「禁用硬解」的开关：media3 没有这种开关，能改的只有「挑哪个
 * MediaCodec」这一步，所以只能靠选择器表达。
 */
private object SoftwareOnlyCodecSelector : MediaCodecSelector {
  override fun getDecoderInfos(
    mimeType: String,
    requiresSecureDecoder: Boolean,
    requiresTunnelingDecoder: Boolean,
  ): List<MediaCodecInfo> {
    val all =
      MediaCodecSelector.DEFAULT.getDecoderInfos(
        mimeType,
        requiresSecureDecoder,
        requiresTunnelingDecoder,
      )
    val software = all.filter { it.softwareOnly }
    return software.ifEmpty { all }
  }
}
