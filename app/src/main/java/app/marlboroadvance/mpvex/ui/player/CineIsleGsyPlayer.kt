package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.GsyFilterKind
import app.marlboroadvance.mpvex.preferences.GsyPreferences
import app.marlboroadvance.mpvex.preferences.GsyRenderKind
import app.marlboroadvance.mpvex.preferences.GsyShowKind
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.render.effect.BlackAndWhiteEffect
import com.shuyu.gsyvideoplayer.render.effect.ContrastEffect
import com.shuyu.gsyvideoplayer.render.effect.GreyScaleEffect
import com.shuyu.gsyvideoplayer.render.effect.InvertColorsEffect
import com.shuyu.gsyvideoplayer.render.effect.NoEffect
import com.shuyu.gsyvideoplayer.render.effect.PosterizeEffect
import com.shuyu.gsyvideoplayer.render.effect.SaturationEffect
import com.shuyu.gsyvideoplayer.render.effect.SepiaEffect
import com.shuyu.gsyvideoplayer.render.effect.TemperatureEffect
import com.shuyu.gsyvideoplayer.render.effect.VignetteEffect
import com.shuyu.gsyvideoplayer.render.view.GSYVideoGLView
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import kotlin.math.abs

/**
 * 底栏 / 顶栏那些「GSY 不认识的按钮」被点之后要做什么。
 *
 * 单独收成一个类而不是继续往 [CineIsleGsyPlayer.bindGlassControls] 上加参数：
 * 按钮已经到 11 个，再铺成 11 个 lambda 参数调用处就没法读了。
 * 这个类的实例由 Activity 构造 —— 只有它知道播放队列、当前地址、偏好这些上下文。
 */
class GsyGlassActions(
  /** 需要重建播放页才生效的操作（换渲染载体），交给 Activity 带进度重建 */
  val onRecreateNeeded: (String) -> Unit = {},
  val onPrevious: () -> Unit = {},
  val onNext: () -> Unit = {},
  val onSwitchEngine: () -> Unit = {},
  /** 横竖屏切换（竖屏 ↔ 横屏全屏） */
  val onOrientation: () -> Unit = {},
  val onScreenshot: () -> Unit = {},
  val onSubtitle: () -> Unit = {},
  val onAudioTrack: () -> Unit = {},
  val onPictureInPicture: () -> Unit = {},
  val onCast: () -> Unit = {},
)

/**
 * CineIsle 的 GSY 播放器控件。
 *
 * 四层职责，除此之外一行官方逻辑都不改（状态机、手势、全屏、缓冲动画全部按 GSY 自己的走）：
 *
 * 1. `getLayoutId()` 换成 [R.layout.gsy_player_cineisle] —— 官方文档里换布局的唯一入口；
 * 2. `setViewShowState()` 兜住官方的控件显隐出口，做三件官方不管的事：
 *    同步正中播放/暂停键的图标（官方默认布局用的是 `ENPlayView`，会自己画图标，我们换成了
 *    普通 ImageView，所以得自己同步）、让顶/底渐变遮罩跟着官方容器一起淡入淡出、
 *    以及让「上一集 / 下一集」跟着正中播放键一起显隐（它们不在 GSY 的 id 表里）；
 * 3. [applyTitlePlacement] 决定媒体标题放哪儿：竖屏在底栏独占一行居中，
 *    横屏搬到顶栏、和返回键同一行（横屏横向空间富裕，标题不该再占底下一条）。
 * 4. [bindGlassControls] 接管自控按钮条 —— 它们**不在 GSY 的 id 表里**，官方不认识、
 *    也不会碰，点击行为必须自己接。每一个按钮都落到一个 GSY 官方能力上：
 *
 *    | 按钮 | 官方能力 |
 *    |---|---|
 *    | 倍速 | `setSpeed(float, soundTouch)` |
 *    | 循环 | `setLooping(boolean)` |
 *    | 比例 | `GSYVideoType.setShowType()` + `changeTextureViewShowType()`（官方 demo 的运行时切法） |
 *    | 渲染 | `GSYVideoType.setRenderType()` —— 渲染载体在 View 构造时就定死，只能重建播放页 |
 *    | 滤镜 | `setEffectFilter()` —— 官方限制：必须 GLSURFACE 渲染才有效 |
 *    | 静音 | `GSYVideoManager.instance().setNeedMute(boolean)` |
 *    | 上一集/下一集 | `setUp(url, cache, title)` + `startPlayLogic()`（GSY 官方「一个 View 连播多个视频」的用法） |
 *    | 截图 | `saveFrame(File, GSYVideoShotSaveListener)` |
 *    | 字幕 | `setSubtitleSource(GSYSubtitleSource)`（官方字幕子系统，见 GSYSubtitleController） |
 *    | 音轨 | 见 Activity：IJK 内核的 `getTrackInfo()/selectTrack()` |
 *    | 画中画 / 投屏 / 横竖屏 / 切换内核 | 交给 Activity（Activity 才拿得到窗口、DLNA 与另一套内核） |
 */
class CineIsleGsyPlayer : StandardGSYVideoPlayer {
  constructor(context: Context) : super(context)

  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

  constructor(context: Context, fullFlag: Boolean) : super(context, fullFlag)

  /** 循环开关：GSY 只把它存进私有字段 `mLooping`，没有 getter，所以本地留一份用于刷图标 */
  private var loopingState = false

  /** 静音开关：同上（`GSYVideoManager` 里有 `isNeedMute()`，但全屏实例是另一个对象，本地留存更稳） */
  private var mutedState = false

  /** 当前滤镜：用于按钮激活态 */
  private var filterState: GsyFilterKind = GsyFilterKind.NONE

  /** 是否已挂上外挂字幕：用于字幕键的激活态 */
  private var subtitleActive = false

  /** 播放队列是否可切换（≥ 2 个视频才有意义）—— 决定正中两侧的上/下一集要不要出现 */
  private var playlistNavigationEnabled = false

  /** 正中播放键最近一次的可见性：上/下一集要跟着它一起显隐 */
  private var startButtonVisibility = View.GONE

  /** 官方扩展点：返回本项目的播放器布局（保留 GSY 状态机需要的全部 id） */
  override fun getLayoutId(): Int = R.layout.gsy_player_cineisle

  /** 全屏键图标（GSY 在切全屏时会自己来取，这里换成 CineIsle 的图标） */
  override fun getEnlargeImageRes(): Int = R.drawable.gsy_ic_fullscreen

  /** 退出全屏键图标 */
  override fun getShrinkImageRes(): Int = R.drawable.gsy_ic_fullscreen_exit

  /**
   * GSY 里所有控件显隐的唯一出口（7 个 `changeUiToXxx()` 最后都收口到这里）。
   *
   * 父类实现只做 `view.setVisibility(visibility)`；这里额外：
   *  · 顶/底容器显隐时，把对应的渐变遮罩一起带上 —— 遮罩不是 GSY 认的控件，不会自己跟着动；
   *  · 正中播放键被显示时，按当前播放状态同步图标；
   *  · 上/下一集跟着正中播放键一起显隐（GSY 不知道它们的存在）。
   */
  override fun setViewShowState(view: View?, visibility: Int) {
    super.setViewShowState(view, visibility)
    if (view == null) return

    when (view.id) {
      R.id.layout_top -> findViewById<View>(R.id.gsy_scrim_top)?.visibility = visibility
      R.id.layout_bottom -> findViewById<View>(R.id.gsy_scrim_bottom)?.visibility = visibility
      R.id.start -> {
        if (visibility == View.VISIBLE) syncStartButtonIcon(view)
        startButtonVisibility = visibility
        applyNavigationVisibility()
      }
    }
  }

  private fun syncStartButtonIcon(button: View) {
    val state = currentState
    val isPlaying =
      state == GSYVideoView.CURRENT_STATE_PLAYING ||
        state == GSYVideoView.CURRENT_STATE_PLAYING_BUFFERING_START
    val icon = if (isPlaying) R.drawable.gsy_ic_pause else R.drawable.gsy_ic_play

    val image = button as? ImageView ?: return
    if (image.tag != icon) {
      image.setImageResource(icon)
      image.tag = icon
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 播放队列：上一集 / 下一集
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 告诉控件「这份播放队列还能不能往前/往后走」。
   *
   * 两个都为 false（单视频播放）时，正中两侧的上/下一集整块不出现 ——
   * 和 mpv 播放页「只有一条播放队列才显示上下一集」的规则一致。
   */
  fun setPlaylistNavigation(
    hasPrevious: Boolean,
    hasNext: Boolean,
  ) {
    playlistNavigationEnabled = hasPrevious || hasNext
    findViewById<View>(R.id.gsy_btn_prev)?.alpha = if (hasPrevious) 1f else INACTIVE_ALPHA
    findViewById<View>(R.id.gsy_btn_next)?.alpha = if (hasNext) 1f else INACTIVE_ALPHA
    applyNavigationVisibility()
  }

  /** 上/下一集只在「有队列」且「控件条正在显示」时出现（跟着正中播放键走） */
  private fun applyNavigationVisibility() {
    val visibility =
      if (playlistNavigationEnabled && startButtonVisibility == View.VISIBLE) {
        View.VISIBLE
      } else {
        View.GONE
      }
    findViewById<View>(R.id.gsy_btn_prev)?.visibility = visibility
    findViewById<View>(R.id.gsy_btn_next)?.visibility = visibility
  }

  // ────────────────────────────────────────────────────────────────────────
  // 标题摆放：竖屏在底栏，横屏在顶栏（与返回键同一行）
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 决定媒体标题放哪一行。
   *
   * GSY 只会给 `title` 这个 TextView `setText`，从不管它在哪儿（它不在
   * `setViewShowState` 的名单里），所以可以按屏幕方向在两个容器之间搬：
   *
   *  · 竖屏：放回 [R.id.layout_bottom] 最上面，独占一行居中 ——
   *    底部按钮多，标题在上面单独一条不会跟按钮抢宽度；
   *  · 横屏：插到 [R.id.layout_top] 的返回键右侧，吃满中间剩下的宽度 ——
   *    横屏本来横向空间富裕，标题挪上去后底部就只剩按钮条和进度条，更清爽。
   *
   * 顶栏里那个 `gsy_top_spacer`（weight=1 的弹性占位）在横屏时要收起来，
   * 否则它会和标题平分空间，标题会提前被省略号截断。
   *
   * 注意标题是**同一个 View 对象**，`getTitleTextView()` 拿到的引用始终有效，
   * 搬走之后 GSY 的 `setText` 照常生效。
   */
  fun applyTitlePlacement(landscape: Boolean) {
    val title = findViewById<TextView>(R.id.title) ?: return
    val topBar = findViewById<LinearLayout>(R.id.layout_top) ?: return
    val bottomBar = findViewById<LinearLayout>(R.id.layout_bottom) ?: return
    val spacer = findViewById<View>(R.id.gsy_top_spacer)
    val margin = { dp: Int -> (dp * resources.displayMetrics.density).toInt() }

    (title.parent as? ViewGroup)?.removeView(title)

    if (landscape) {
      spacer?.visibility = View.GONE
      title.gravity = Gravity.CENTER_VERTICAL or Gravity.START
      topBar.addView(
        title,
        1,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
          marginStart = margin(8)
          marginEnd = margin(8)
        },
      )
    } else {
      spacer?.visibility = View.VISIBLE
      title.gravity = Gravity.CENTER
      bottomBar.addView(
        title,
        0,
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
          marginStart = margin(14)
          marginEnd = margin(14)
          bottomMargin = margin(6)
        },
      )
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 自控按钮条：官方不认这些按钮，行为全部在这里接
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 绑定底栏两行按钮 + 正中两侧的上/下一集 + 顶栏的动作键。
   *
   * 这个控件在全屏时会由 GSY 通过反射克隆出**第二个实例**（`startWindowFullscreen`），
   * 克隆出来的实例不带任何点击监听，所以 Activity 在拿到全屏实例后必须再调一次本方法。
   *
   * 顶栏的「收藏」键不在这里 —— 它是 ComposeView（复用 mpv 播放页那份弹跳/星光动效），
   * 由 Activity 自己 `setContent`，与这排 View 无关。
   */
  fun bindGlassControls(
    prefs: GsyPreferences,
    actions: GsyGlassActions,
  ) {
    loopingState = prefs.looping.get()
    mutedState = prefs.muteOnStart.get()
    filterState = prefs.filter.get()

    findViewById<ImageView>(R.id.gsy_btn_speed)?.setOnClickListener {
      keepControlsAlive()
      // 官方 setSpeed(float, soundTouch)：播放中调用立即生效
      val presets = prefs.speedPresets.get().mapNotNull { it.toFloatOrNull() }.sorted()
      if (presets.isEmpty()) return@setOnClickListener
      val current = getSpeed()
      val index = presets.indexOfFirst { abs(it - current) < 0.001f }
      val next = presets[(index + 1) % presets.size]
      setSpeed(next, prefs.soundTouch.get())
      syncGlassButtonStates()
      toast("倍速 ${trimZero(next)}x")
    }

    findViewById<ImageView>(R.id.gsy_btn_loop)?.setOnClickListener {
      keepControlsAlive()
      // 官方 setLooping(boolean)
      loopingState = !loopingState
      setLooping(loopingState)
      syncGlassButtonStates()
      toast(if (loopingState) "循环播放：开" else "循环播放：关")
    }

    findViewById<ImageView>(R.id.gsy_btn_aspect)?.setOnClickListener {
      keepControlsAlive()
      // 官方 GSYVideoType.setShowType + changeTextureViewShowType（demo 的运行时切法，不用重启）
      val current = prefs.showKind.get()
      val index = ASPECT_CYCLE.indexOf(current).coerceAtLeast(0)
      val next = ASPECT_CYCLE[(index + 1) % ASPECT_CYCLE.size]
      GSYVideoType.setShowType(next.value)
      prefs.showKind.set(next)
      changeTextureViewShowType()
      getRenderProxy()?.requestLayout()
      toast("显示比例：${next.title}")
    }

    findViewById<ImageView>(R.id.gsy_btn_render)?.setOnClickListener {
      keepControlsAlive()
      // 官方 GSYVideoType.setRenderType：全局静态值，在 GSYRenderView.initRenderView() 时被读取。
      // 也就是说「渲染载体在 View 建出来那一刻就定死了」，只能改完偏好重建播放页。
      val kinds = GsyRenderKind.entries
      val next = kinds[(kinds.indexOf(prefs.renderKind.get()) + 1) % kinds.size]
      prefs.renderKind.set(next)
      actions.onRecreateNeeded("渲染方式：${next.title}")
    }

    findViewById<ImageView>(R.id.gsy_btn_filter)?.setOnClickListener {
      keepControlsAlive()
      val kinds = GsyFilterKind.entries
      val next = kinds[(kinds.indexOf(prefs.filter.get()) + 1) % kinds.size]
      prefs.filter.set(next)
      filterState = next
      if (GSYVideoType.getRenderType() != GSYVideoType.GLSURFACE) {
        // 官方硬约束：setEffectFilter 只在 GLSURFACE 下有效 —— 顺手把渲染方式带过去并重建
        prefs.renderKind.set(GsyRenderKind.GL)
        actions.onRecreateNeeded("滤镜需要 GLSurfaceView 渲染，正在切换：${next.title}")
      } else {
        setEffectFilter(next.toShader())
        syncGlassButtonStates()
        toast("滤镜：${next.title}")
      }
    }

    findViewById<ImageView>(R.id.gsy_btn_mute)?.setOnClickListener {
      keepControlsAlive()
      // 官方 GSYVideoManager.instance().setNeedMute(boolean)
      mutedState = !mutedState
      GSYVideoManager.instance().setNeedMute(mutedState)
      syncGlassButtonStates()
      toast(if (mutedState) "已静音" else "已取消静音")
    }

    findViewById<ImageView>(R.id.gsy_btn_prev)?.setOnClickListener {
      keepControlsAlive()
      actions.onPrevious()
    }

    findViewById<ImageView>(R.id.gsy_btn_next)?.setOnClickListener {
      keepControlsAlive()
      actions.onNext()
    }

    findViewById<ImageView>(R.id.gsy_btn_screenshot)?.setOnClickListener {
      keepControlsAlive()
      actions.onScreenshot()
    }

    findViewById<ImageView>(R.id.gsy_btn_subtitle)?.setOnClickListener {
      keepControlsAlive()
      actions.onSubtitle()
    }

    findViewById<ImageView>(R.id.gsy_btn_audio_track)?.setOnClickListener {
      keepControlsAlive()
      actions.onAudioTrack()
    }

    findViewById<ImageView>(R.id.gsy_btn_pip)?.setOnClickListener {
      keepControlsAlive()
      actions.onPictureInPicture()
    }

    findViewById<ImageView>(R.id.gsy_btn_cast)?.setOnClickListener {
      keepControlsAlive()
      actions.onCast()
    }

    findViewById<ImageView>(R.id.gsy_btn_orientation)?.setOnClickListener {
      keepControlsAlive()
      actions.onOrientation()
    }

    findViewById<ImageView>(R.id.gsy_btn_engine)?.setOnClickListener {
      keepControlsAlive()
      actions.onSwitchEngine()
    }

    syncGlassButtonStates()
  }

  /** 字幕键的激活态：挂上外挂字幕后点亮，一眼能看出「字幕开着」 */
  fun setSubtitleActive(active: Boolean) {
    subtitleActive = active
    syncGlassButtonStates()
  }

  /** 把「开着」的按钮点亮、关着的压暗 —— 一眼能看出哪些能力正在生效 */
  private fun syncGlassButtonStates() {
    dim(findViewById<View>(R.id.gsy_btn_speed), abs(getSpeed() - 1f) > 0.001f)
    dim(findViewById<View>(R.id.gsy_btn_loop), loopingState)
    dim(findViewById<View>(R.id.gsy_btn_filter), filterState != GsyFilterKind.NONE)
    dim(findViewById<View>(R.id.gsy_btn_subtitle), subtitleActive)
    findViewById<ImageView>(R.id.gsy_btn_mute)?.let { button ->
      button.setImageResource(
        if (mutedState) R.drawable.gsy_ic_volume_off else R.drawable.gsy_ic_volume_on,
      )
      button.contentDescription =
        context.getString(if (mutedState) R.string.gsy_control_unmute else R.string.gsy_control_mute)
    }
  }

  private fun dim(view: View?, active: Boolean) {
    view?.alpha = if (active) 1f else INACTIVE_ALPHA
  }

  /**
   * 点按钮同样算「有操作」，得把官方的控件自动隐藏计时重置掉，
   * 否则连点两下按钮控件条就淡出了（GSY 只在点击画面时才重置）。
   */
  private fun keepControlsAlive() {
    startDismissControlViewTimer()
  }

  private fun toast(message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }

  companion object {
    /** 未激活按钮的透明度（保留玻璃底，只压图标亮度） */
    private const val INACTIVE_ALPHA = 0.62f

    /** 比例按钮的三档循环 */
    private val ASPECT_CYCLE = listOf(GsyShowKind.DEFAULT, GsyShowKind.STRETCH, GsyShowKind.CROP)

    private fun trimZero(value: Float): String =
      if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
  }
}

/**
 * 偏好里的滤镜档位 → GSY 官方内置 Shader。
 *
 * 参数取值按官方 effect 源码的语义：
 *  · [ContrastEffect] 1.5f（1 = 原样）
 *  · [SaturationEffect] 0.5f（0 = 原样，正数 = 更饱和）
 *  · [TemperatureEffect] 0.85f / 0.15f（0.5 = 原样，越大越暖）
 *  · [VignetteEffect] 0.6f
 */
internal fun GsyFilterKind.toShader(): GSYVideoGLView.ShaderInterface =
  when (this) {
    GsyFilterKind.NONE -> NoEffect()
    GsyFilterKind.GREYSCALE -> GreyScaleEffect()
    GsyFilterKind.BLACK_WHITE -> BlackAndWhiteEffect()
    GsyFilterKind.SEPIA -> SepiaEffect()
    GsyFilterKind.INVERT -> InvertColorsEffect()
    GsyFilterKind.CONTRAST -> ContrastEffect(1.5f)
    GsyFilterKind.SATURATION -> SaturationEffect(0.5f)
    GsyFilterKind.WARM -> TemperatureEffect(0.85f)
    GsyFilterKind.COOL -> TemperatureEffect(0.15f)
    GsyFilterKind.VIGNETTE -> VignetteEffect(0.6f)
    GsyFilterKind.POSTERIZE -> PosterizeEffect()
  }
