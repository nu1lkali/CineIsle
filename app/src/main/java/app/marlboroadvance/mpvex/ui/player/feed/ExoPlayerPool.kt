package app.marlboroadvance.mpvex.ui.player.feed

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
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
 * 视频尺寸（只有这里知道）与容器实测宽高（只有 View 知道）。把它统一放在
 * [applyVideoLayout] 里算，旋转（容器尺寸变化）与换片（视频尺寸变化）都只走这一条路。
 *
 * ── 生命周期约定 ──
 * 所有方法都必须在主线程调用：ExoPlayer 要求在自己创建时所绑定的 Looper 线程上操作，
 * 而这个池创建于 Activity 主线程的 Composition 里。页面回收时由 [detach] 解绑画面、
 * 但**不释放**实例（实例去留由 [onPageChanged] 的窗口裁剪决定），这样滑回去还能接着用。
 */
internal class ExoPlayerPool(
  private val context: Context,
  private val onError: (index: Int, reason: String) -> Unit,
  private val onFirstFrameReady: (index: Int) -> Unit,
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
   * 画面的尺寸由我们自己设进 TextureView 的 LayoutParams，依据是**容器的实测宽高**
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
   * index → 是否已经**真正把第一帧画到了屏幕上**。
   *
   * 这份账本单独记，是因为它和 `players` 的存在并不同步：一个实例可能已经
   * `STATE_READY`（解码器建好、缓冲够了），却还没有渲染出任何一帧。加载态必须等
   * 到这一份为真才能撤 —— 早撤一步，用户看到的就是「转圈消失 → 黑屏 → 画面浮现」。
   */
  private val firstFrameRendered = HashSet<Int>()

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
   */
  private fun acquire(index: Int, url: String): ExoPlayer {
    players[index]?.let { return it }
    val player = build(url)
    player.addListener(listenerFor(index))
    players[index] = player
    return player
  }

  private fun build(url: String): ExoPlayer =
    ExoPlayer.Builder(context)
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

  private fun listenerFor(index: Int) = object : Player.Listener {
    /**
     * 「画面好了」的判据用这里，**不是** onPlaybackStateChanged 的 STATE_READY。
     *
     * READY 只说明解码器建好了、缓冲够了，第一帧未必已经画到那块 TextureView 上。
     * 拿 READY 去撤加载态，用户看到的是「转圈消失 → 黑屏 → 画面浮现」——
     * 也就是刚进页面时那一下闪烁。[onRenderedFirstFrame] 才是「帧真的上屏了」。
     */
    override fun onRenderedFirstFrame() {
      firstFrameRendered.add(index)
      onFirstFrameReady(index)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
      // 视频尺寸是决定「铺满还是留边」的一半依据，先记下来再重算画面矩形。
      // 记下来而不是当场用完就丢，是因为 attach 可能发生在这个回调之后。
      videoSizes[index] = videoSize
      applyVideoLayout(index)
    }

    override fun onPlayerError(error: PlaybackException) {
      onError(index, describeError(error))
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
   * 按**屏幕当前实测尺寸**把画面摆好：等比缩放，完整显示，不裁掉任何一个像素。
   *
   * ── 规则：永远 contain，永远不丢画面 ──
   * 按容器实测宽高算出「刚好放得下」的最大等比矩形，多出来的地方留黑边。
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
   * 现在尺寸由我们按容器实测宽高每次重算，容器一变就重算，不存在「停在旧方向」。
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
        players[index]?.let { player -> bind(index, player) }
        applyVideoLayout(index)
      }

      override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // 输出面尺寸变了（旋转、分屏都会走到这里），画面矩形要跟着重算
        applyVideoLayout(index)
      }

      override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // 返回 true 让系统释放这块 SurfaceTexture。同时必须显式断开 ExoPlayer 与它的
        // 连接 —— 否则它会继续往一块已释放的 Surface 上写。
        if (boundTextures[index] === texture) {
          boundTextures.remove(index)
          players[index]?.let { player -> runCatching { player.clearVideoTextureView(texture) } }
        }
        return true
      }

      override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }
  }

  // ══════════════════ 接线 ══════════════════

  private fun describeError(error: PlaybackException): String =
    when {
      error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
        "无法播放：本机没有可用的解码器"
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

    if (inWindow(index)) bind(index, acquire(index, url))
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

  /** 当前活动页是否已经真正把第一帧画出来了（加载态的判据） */
  fun activeHasRenderedFirstFrame(): Boolean = activeIndex in firstFrameRendered

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
      firstFrameRendered = index in firstFrameRendered,
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
      bind(idx, acquire(idx, url))
      applyVideoLayout(idx)
    }
    applyPlaybackState()
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
    val player = acquire(idx, url)
    if (texture != null) {
      // surfaces 里那份是页面的登记，releaseSlot 不碰它；这里把接线接回去即可
      boundTextures.remove(idx)
      bind(idx, player)
    }
    applyVideoLayout(idx)
    applyPlaybackState()
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
    // 首帧账本跟着实例走：实例都没了，它渲染过的那一帧自然也不作数了
    firstFrameRendered.remove(index)
    runCatching {
      if (texture != null) player.clearVideoTextureView(texture)
      player.stop()
      player.release()
    }
  }

  /** 退出页面：全部释放。顺序是先解画面、再 stop、最后 release，反了会有 native 线程踩已释放的句柄 */
  fun releaseAll() {
    players.forEach { (index, player) ->
      runCatching {
        boundTextures[index]?.let { player.clearVideoTextureView(it) }
        player.stop()
        player.release()
      }
    }
    surfaces.clear()
    containers.clear()
    videoSizes.clear()
    boundTextures.clear()
    firstFrameRendered.clear()
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
  /** 首帧是否真的已经上屏；加载态据此决定撤不撤 */
  val firstFrameRendered: Boolean,
)
