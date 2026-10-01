package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
  val onScreenshot: () -> Unit = {},
  val onSubtitle: () -> Unit = {},
  val onAudioTrack: () -> Unit = {},
  val onPictureInPicture: () -> Unit = {},
  val onCast: () -> Unit = {},
)

/**
 * CineIsle 的 GSY 播放器控件。
 *
 * 另外补了两处官方链路断掉时的兜底（都不是改官方逻辑）：
 *  · 拆掉 GSY 挂在 `thumb` 上的点击监听 —— 本项目的 `thumb` 是空的全透明 `match_parent` 层，
 *    被官方置为 VISIBLE 时会吃掉整屏触摸（见 [disableThumbTouch]）；
 *  · 单击画面呼出控件条 —— 官方链路在 `mHideKey && mShowVKey` 时会被整条 `return` 掉（见 [onTouch]）。
 *
 * 主要职责如下，除此之外一行官方逻辑都不改（状态机、手势、全屏、缓冲动画全部按 GSY 自己的走）：
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
 *    | 画中画 / 投屏 / 切换内核 | 交给 Activity（Activity 才拿得到窗口、DLNA 与另一套内核） |
 */
class CineIsleGsyPlayer : StandardGSYVideoPlayer {
  constructor(context: Context) : super(context) {
    disableThumbTouch()
  }

  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
    disableThumbTouch()
  }

  constructor(context: Context, fullFlag: Boolean) : super(context, fullFlag) {
    disableThumbTouch()
  }

  /**
   * 拆掉 GSY 挂在 `thumb`（封面层）上的点击监听。
   *
   * 官方 `GSYVideoControlView.init()` 里有 `mThumbImageViewLayout.setOnClickListener(this)`，
   * 把它变成可点击的 —— 官方布局里那层放着封面图、点击是「点封面起播」，没问题。
   *
   * 但本项目的 `thumb` 是**空的全透明 `match_parent` 层**（见 gsy_player_cineisle.xml），
   * 而 GSY 在 `changeUiToNormal()` / `changeUiToCompleteShow()` 都会把它置为 **VISIBLE** ——
   * 于是一整层看不见的可点击视图盖在画面上，把整屏触摸全部吃掉，
   * 表现就是「控件自动隐藏后，点屏幕呼不出来」。
   *
   * 让这一层彻底退出触摸分发即可，GSY 依旧照常显隐它（反正它本来就没内容）。
   * 放在构造器体里而不是 `init()` 里：构造器体一定晚于 `super` 里的 `init()`，
   * 不会踩到「构造期间访问子类成员」的坑。
   */
  private fun disableThumbTouch() {
    findViewById<View>(R.id.thumb)?.apply {
      setOnClickListener(null)
      isClickable = false
      isLongClickable = false
      isFocusable = false
    }
  }

  /** 循环开关：GSY 只把它存进私有字段 `mLooping`，没有 getter，所以本地留一份用于刷图标 */
  private var loopingState = false

  /** 静音开关：同上（`GSYVideoManager` 里有 `isNeedMute()`，但全屏实例是另一个对象，本地留存更稳） */
  private var mutedState = false

  /** 当前滤镜：用于按钮激活态 */
  private var filterState: GsyFilterKind = GsyFilterKind.NONE

  /** 是否已挂上外挂字幕：用于字幕键的激活态 */
  private var subtitleActive = false

  /**
   * 正在画中画小窗里。
   *
   * 系统是把**整个 Activity**（连同我们这套顶栏 / 底栏 / 按钮条）等比缩进小窗的，
   * 不特殊处理的话那些控件会一起缩进去糊成一片。为 true 时 [setViewShowState]
   * 会把除画面之外的一切控件压成 GONE。
   */
  private var pipMode = false

  /** 播放队列是否可切换（≥ 2 个视频才有意义）—— 决定正中两侧的上/下一集要不要出现 */
  private var playlistNavigationEnabled = false

  /** 正中播放键最近一次的可见性：上/下一集要跟着它一起显隐 */
  private var startButtonVisibility = View.GONE

  /** 官方扩展点：返回本项目的播放器布局（保留 GSY 状态机需要的全部 id） */
  override fun getLayoutId(): Int = R.layout.gsy_player_cineisle

  /**
   * 右下角那个键的图标。
   *
   * 它同时承担「全屏」和「横竖屏切换」，而这两件事在手机上本来就是同一件
   * （全屏 = 横屏全屏），所以两个状态用**同一个旋转图标**，
   * 不再像官方那样在「放大/缩小」两个图标之间切 —— 那反而看不出它能转屏。
   */
  override fun getEnlargeImageRes(): Int = R.drawable.gsy_ic_rotate

  /** 同上：全屏态也用同图标，语义是「点我转回竖屏」 */
  override fun getShrinkImageRes(): Int = R.drawable.gsy_ic_rotate

  /**
   * GSY 里所有控件显隐的唯一出口（7 个 `changeUiToXxx()` 最后都收口到这里）。
   *
   * 父类实现只做 `view.setVisibility(visibility)`；这里额外：
   *  · 顶/底容器显隐时，把对应的渐变遮罩一起带上 —— 遮罩不是 GSY 认的控件，不会自己跟着动；
   *  · 正中播放键被显示时，按当前播放状态同步图标；
   *  · 上/下一集跟着正中播放键一起显隐（GSY 不知道它们的存在）；
   *  · 画中画小窗里把除画面之外的一切都压成 GONE（见 [pipMode]）。
   */
  override fun setViewShowState(view: View?, visibility: Int) {
    if (view == null) return

    // 画中画小窗里只留画面：除了渲染视图本身，其余控件一律压掉
    //（见 [pipMode] / [setPipMode]）。
    val effective =
      if (pipMode && view.id != R.id.surface_container) View.GONE else visibility

    super.setViewShowState(view, effective)

    when (view.id) {
      R.id.layout_top -> findViewById<View>(R.id.gsy_scrim_top)?.visibility = effective
      R.id.layout_bottom -> findViewById<View>(R.id.gsy_scrim_bottom)?.visibility = effective
      R.id.start -> {
        if (effective == View.VISIBLE) syncStartButtonIcon(view)
        startButtonVisibility = effective
        applyNavigationVisibility()
        // ±10s 键不在 GSY 的 id 表里，跟着正中播放键一起显隐
        findViewById<View>(R.id.gsy_btn_rewind10)?.visibility = effective
        findViewById<View>(R.id.gsy_btn_forward10)?.visibility = effective
      }
    }
  }

  /**
   * 进出画中画小窗。
   *
   * 进：把控件全部收起（后续显隐由 [setViewShowState] 统一拦掉）。
   * 出：手工还原一次 —— GSY 的状态机并不知道我们中途把控件压掉了，
   * 光把 [pipMode] 置回 false 会出现「回到了大屏，但控件一直是隐藏的、点一下才出来」。
   */
  fun setPipMode(enabled: Boolean) {
    if (pipMode == enabled) return
    pipMode = enabled

    if (enabled) {
      PIP_HIDDEN_IDS.forEach { findViewById<View>(it)?.visibility = View.GONE }
      return
    }

    // 还原成「控件条正在显示」的样子，再把自动隐藏计时器重新起一遍
    findViewById<View>(R.id.layout_top)?.visibility = View.VISIBLE
    findViewById<View>(R.id.layout_bottom)?.visibility = View.VISIBLE
    findViewById<View>(R.id.gsy_scrim_top)?.visibility = View.VISIBLE
    findViewById<View>(R.id.gsy_scrim_bottom)?.visibility = View.VISIBLE
    findViewById<View>(R.id.gsy_btn_rewind10)?.visibility = View.VISIBLE
    findViewById<View>(R.id.gsy_btn_forward10)?.visibility = View.VISIBLE
    findViewById<View>(R.id.start)?.let { button ->
      syncStartButtonIcon(button)
      button.visibility = View.VISIBLE
    }
    startButtonVisibility = View.VISIBLE
    applyNavigationVisibility()
    startDismissControlViewTimer()
  }

  // ────────────────────────────────────────────────────────────────────────
  // 「点画面呼出控件条」的兜底
  // ────────────────────────────────────────────────────────────────────────

  /** 按下的落点，用来区分「轻点呼出控件」和「滑动调亮度/音量/进度」 */
  private var touchDownX = 0f
  private var touchDownY = 0f

  /** 按下那一刻控件是不是藏着的 —— 只有「想呼出」的那一下才需要兜底 */
  private var controlsHiddenOnDown = false

  /** 判定「轻点」的位移阈值（超过就当滑动，交给 GSY 自己的手势逻辑） */
  private val touchSlop: Int by lazy { ViewConfiguration.get(context).scaledTouchSlop }

  /**
   * 单击画面呼出控件条的兜底。
   *
   * GSY 官方的呼出链路是
   * `surface_container.onTouch → GestureDetector.onSingleTapConfirmed → onClickUiToggle()`，
   * 这条链路上有个断点会把它整条吃掉 —— `onTouch` 的 ACTION_UP 分支里：
   *
   * ```java
   * if (mHideKey && mShowVKey) {
   *     return true;   // ← 提前返回，下面的 gestureDetector.onTouchEvent(event) 不执行
   * }
   * ```
   *
   * 一旦成立，单击回调永远不会触发，点屏幕就再也呼不出控件。
   *
   * 这里**不跟官方那条链路抢活**：只在「按下时控件是藏着的」且「轻点没滑动」的情况下，
   * 等一小会儿再看一眼 —— 控件要是还没出来，才由我们按当前播放状态直接显示。
   * 因为做了「按下时是藏的」这个前置判断，用户主动点一下**收起**控件的那次不会被兜底又翻出来。
   */
  override fun onTouch(v: View?, event: MotionEvent?): Boolean {
    if (v != null && event != null && v.id == R.id.surface_container) {
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          touchDownX = event.x
          touchDownY = event.y
          controlsHiddenOnDown = isControlsHidden()
        }

        MotionEvent.ACTION_UP -> {
          val moved = abs(event.x - touchDownX) + abs(event.y - touchDownY)
          if (controlsHiddenOnDown && moved <= touchSlop) {
            // 官方单击回调要等双击判定（约 300ms），所以这里排在它后面
            postDelayed({ if (isControlsHidden()) showControlsNow() }, CONTROL_FALLBACK_DELAY_MS)
          }
        }
      }
    }
    return super.onTouch(v, event)
  }

  /** 控件条（底栏）当前是不是藏着的 */
  private fun isControlsHidden(): Boolean =
    findViewById<View>(R.id.layout_bottom)?.visibility != View.VISIBLE

  /** 按当前播放状态直接把控件条显示出来（只覆盖确定的三个状态，其余交给 GSY） */
  private fun showControlsNow() {
    when (currentState) {
      GSYVideoView.CURRENT_STATE_PLAYING -> changeUiToPlayingShow()
      GSYVideoView.CURRENT_STATE_PAUSE -> changeUiToPauseShow()
      GSYVideoView.CURRENT_STATE_AUTO_COMPLETE -> changeUiToCompleteShow()
      else -> Unit
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
      // 标题插在「返回键 + 网速指示器」之后：竖屏横屏下网速都贴着返回键，
      // 标题吃满中间剩下的宽度（gsy_net_speed 不在 GSY 的 id 表里，搬动不影响它）
      val netSpeed = findViewById<View>(R.id.gsy_net_speed)
      val insertIndex = if (netSpeed != null) 2 else 1
      topBar.addView(
        title,
        insertIndex,
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
      toast("播放速度已调整为 ${trimZero(next)}x")
    }

    findViewById<ImageView>(R.id.gsy_btn_loop)?.setOnClickListener {
      keepControlsAlive()
      // 官方 setLooping(boolean)
      loopingState = !loopingState
      setLooping(loopingState)
      syncGlassButtonStates()
      toast(if (loopingState) "已开启循环播放" else "已关闭循环播放")
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
      toast("画面显示比例：${next.title}")
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
        toast("已应用滤镜：${next.title}")
      }
    }

    findViewById<ImageView>(R.id.gsy_btn_mute)?.setOnClickListener {
      keepControlsAlive()
      // 官方 GSYVideoManager.instance().setNeedMute(boolean)
      mutedState = !mutedState
      GSYVideoManager.instance().setNeedMute(mutedState)
      syncGlassButtonStates()
      toast(if (mutedState) "已开启静音" else "已取消静音")
    }

    findViewById<ImageView>(R.id.gsy_btn_prev)?.setOnClickListener {
      keepControlsAlive()
      actions.onPrevious()
    }

    findViewById<ImageView>(R.id.gsy_btn_next)?.setOnClickListener {
      keepControlsAlive()
      actions.onNext()
    }

    // 后退 / 前进 10 秒：走 GSYVideoManager 的 seekTo（当前接管播放的实例，
    // 全屏克隆实例也归它管，与画中画遥控键同一套路）
    findViewById<ImageView>(R.id.gsy_btn_rewind10)?.setOnClickListener {
      keepControlsAlive()
      val manager = GSYVideoManager.instance()
      manager.seekTo((manager.getCurrentPosition() - SEEK_STEP_MS).coerceAtLeast(0L))
    }

    findViewById<ImageView>(R.id.gsy_btn_forward10)?.setOnClickListener {
      keepControlsAlive()
      val manager = GSYVideoManager.instance()
      val target = manager.getCurrentPosition() + SEEK_STEP_MS
      val duration = manager.getDuration()
      manager.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
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

    /** 单击画面后等多久再去检查控件有没有被官方逻辑呼出来（要排在 GestureDetector 的双击判定之后） */
    private const val CONTROL_FALLBACK_DELAY_MS = 400L

    /** 比例按钮的三档循环 */
    private val ASPECT_CYCLE = listOf(GsyShowKind.DEFAULT, GsyShowKind.STRETCH, GsyShowKind.CROP)

    /**
     * 画中画小窗里要收起来的控件。
     *
     * 画面本身（`surface_container`）不在其中 —— 小窗里就只剩它。
     * 其余是顶/底栏、遮罩、进度线、正中播放键、上下一集、缓冲圈、锁屏键。
     */
    private val PIP_HIDDEN_IDS =
      intArrayOf(
        R.id.layout_top,
        R.id.layout_bottom,
        R.id.gsy_scrim_top,
        R.id.gsy_scrim_bottom,
        R.id.bottom_progressbar,
        R.id.start,
        R.id.gsy_btn_prev,
        R.id.gsy_btn_next,
        R.id.gsy_btn_rewind10,
        R.id.gsy_btn_forward10,
        R.id.loading,
        R.id.lock_screen,
      )

    /** ±10s 键的步长（毫秒） */
    private const val SEEK_STEP_MS = 10_000L

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
