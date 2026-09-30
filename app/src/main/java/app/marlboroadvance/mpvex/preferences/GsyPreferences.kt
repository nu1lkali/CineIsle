package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum

/*
 * GSYVideoPlayer 的独立配置。
 *
 * 与 mpv 的 [PlayerPreferences] 严格隔离：
 *  - 所有 key 一律 `gsy_` 前缀，同一个 PreferenceStore（SharedPreferences）里也不会撞车；
 *  - 这里出现的项只影响 GSY 播放页（GsyPlayerActivity / CineIsleGsyPlayer），
 *    改它们不会动到 mpv 播放页的任何行为，反之亦然；
 *  - 对应关系全部按 GSY 官方文档（skills/02-gsy-option-builder、04-kernel-switch、
 *    07-render-and-effects、12-custom-view）里的 setter 一一对照，不自行发明。
 */

/** 播放内核：PlayerFactory.setPlayManager 的取值来源 */
enum class GsyKernelKind(val title: String) {
  IJK("IJK（ffmpeg，格式兼容最好）"),
  SYSTEM("System（系统 MediaPlayer，体积最小）"),
  EXO("ExoPlayer / media3（HLS · DASH）"),
}

/** 渲染载体：GSYVideoType.setRenderType 的取值 */
enum class GsyRenderKind(val title: String, val value: Int) {
  TEXTURE("TextureView（默认，动画兼容最好）", 0),
  SURFACE("SurfaceView（性能最好）", 1),
  GL("GLSurfaceView（支持 GL 滤镜）", 2),
}

/** 显示比例：GSYVideoType.setShowType 的取值 */
enum class GsyShowKind(val title: String, val value: Int) {
  DEFAULT("自适应（按视频原比例）", 0),
  R16_9("16:9", 1),
  R4_3("4:3", 2),
  R18_9("18:9 全面屏", 6),
  CROP("全屏裁剪", 4),
  STRETCH("全屏拉伸", -4),
}

/**
 * GL 滤镜：从 GSY 官方内置的 `render/effect` 集合里挑的一组常用效果。
 *
 * 官方硬约束：**只有 GLSURFACE 渲染下 setEffectFilter 才有效**（见 skills/07-render-and-effects），
 * 所以选中的滤镜只在 [GsyRenderKind.GL] 下能看见画面变化 —— 播放页的滤镜按钮会自动把渲染方式带过去。
 */
enum class GsyFilterKind(val title: String) {
  NONE("原画（无滤镜）"),
  GREYSCALE("黑白（灰度）"),
  BLACK_WHITE("黑白（强对比）"),
  SEPIA("怀旧"),
  INVERT("反色"),
  CONTRAST("高对比"),
  SATURATION("高饱和"),
  WARM("暖色"),
  COOL("冷色"),
  VIGNETTE("暗角"),
  POSTERIZE("色阶压缩"),
}

class GsyPreferences(
  preferenceStore: PreferenceStore,
) {
  // ── 画面 / 内核 ────────────────────────────────────────────────
  /** 播放内核（切完下次 setUp 生效） */
  val kernel = preferenceStore.getEnum("gsy_kernel", GsyKernelKind.IJK)

  /** 渲染载体（TEXTURE / SURFACE / GL；GL 才能用滤镜） */
  val renderKind = preferenceStore.getEnum("gsy_render_kind", GsyRenderKind.TEXTURE)

  /** 显示比例 */
  val showKind = preferenceStore.getEnum("gsy_show_kind", GsyShowKind.DEFAULT)

  /** GL 滤镜（需要 GLSURFACE 渲染；官方 setEffectFilter） */
  val filter = preferenceStore.getEnum("gsy_filter", GsyFilterKind.NONE)

  /** 硬解码（IJK 的 mediacodec 通道） */
  val hardwareDecode = preferenceStore.getBoolean("gsy_hardware_decode", false)

  /** 硬解失败自动回退软解（官方 enableSmartMediaCodec） */
  val smartFallback = preferenceStore.getBoolean("gsy_smart_fallback", true)

  // ── 播放行为 ──────────────────────────────────────────────────
  /** 循环播放 */
  val looping = preferenceStore.getBoolean("gsy_looping", false)

  /** 起播倍速 */
  val defaultSpeed = preferenceStore.getFloat("gsy_default_speed", 1f)

  /** 变速不变调（soundTouch；System 内核不支持） */
  val soundTouch = preferenceStore.getBoolean("gsy_sound_touch", false)

  /** 边播边缓存（ProxyCacheManager） */
  val cacheWithPlay = preferenceStore.getBoolean("gsy_cache_with_play", false)

  /** 续播：带 position 进入时从上次位置开始 */
  val resumePosition = preferenceStore.getBoolean("gsy_resume_position", true)

  /** 播完自动退出播放页 */
  val closeAfterEnd = preferenceStore.getBoolean("gsy_close_after_end", false)

  /** 进入即静音 */
  val muteOnStart = preferenceStore.getBoolean("gsy_mute_on_start", false)

  /** 强制解封装器（Exo 用；空 = 不设置）。官方 setOverrideExtension */
  val overrideExtension = preferenceStore.getString("gsy_override_extension", "")

  // ── 手势 ──────────────────────────────────────────────────────
  /** 非全屏是否响应 GSY 的触摸手势（左亮度 / 右音量 / 横滑 seek） */
  val touchGesture = preferenceStore.getBoolean("gsy_touch_gesture", true)

  /** 全屏是否响应同样的手势 */
  val touchGestureFull = preferenceStore.getBoolean("gsy_touch_gesture_full", true)

  /** 手势 seek 灵敏度（官方 setSeekRatio，越大滑一点跳越多） */
  val seekRatio = preferenceStore.getFloat("gsy_seek_ratio", 1f)

  /** 移动网络下是否提示 */
  val wifiTip = preferenceStore.getBoolean("gsy_wifi_tip", true)

  // ── 控件 ──────────────────────────────────────────────────────
  /** 控件无操作后自动隐藏的毫秒数 */
  val dismissControlTimeMs = preferenceStore.getInt("gsy_dismiss_control_time", 4000)

  /** 全屏时显示屏幕锁 */
  val needLockFull = preferenceStore.getBoolean("gsy_need_lock_full", true)

  /** 拖动 seekbar 时把目标时间显示在「已播」位置（官方 setShowDragProgressTextOnSeekBar） */
  val showDragProgressText = preferenceStore.getBoolean("gsy_show_drag_progress_text", true)

  /** 暂停时保留最后一帧（官方 setShowPauseCover） */
  val showPauseCover = preferenceStore.getBoolean("gsy_show_pause_cover", true)

  /** 默认倍速快捷键的档位 */
  val speedPresets =
    preferenceStore.getStringSet(
      "gsy_speed_presets",
      setOf("0.5", "0.75", "1.0", "1.25", "1.5", "2.0"),
    )

  // ── 全屏 / 旋转 ───────────────────────────────────────────────
  /** 全屏时锁横屏（官方 setLockLand） */
  val lockLand = preferenceStore.getBoolean("gsy_lock_land", false)

  /** 只允许横屏方向变化（官方 setOnlyRotateLand） */
  val onlyRotateLand = preferenceStore.getBoolean("gsy_only_rotate_land", false)

  /** 全屏切换动画 */
  val showFullAnimation = preferenceStore.getBoolean("gsy_show_full_animation", true)

  /** 全屏时隐藏系统状态栏 */
  val fullHideStatusBar = preferenceStore.getBoolean("gsy_full_hide_status_bar", false)

  /** 全屏时隐藏系统虚拟按键 */
  val hideKey = preferenceStore.getBoolean("gsy_hide_key", true)

  /** 竖屏视频自动竖屏全屏（官方 setAutoFullWithSize） */
  val autoFullWithSize = preferenceStore.getBoolean("gsy_auto_full_with_size", true)

  /**
   * 播放页内是否**跟随系统「自动旋转」开关**。
   *
   * 官方 [com.shuyu.gsyvideoplayer.utils.OrientationUtils] 的默认值是 `true`，语义是：
   * 一旦系统「自动旋转」被关掉，GSY 就彻底忽略重力感应 —— 用户转动手机播放页纹丝不动，
   * 看上去就是「切不了横屏」。本项目默认 `false`：播放页内始终响应重力感应，
   * 与 mpv 播放页的行为保持一致（mpv 侧是 `PlayerOrientation.Free` = SCREEN_ORIENTATION_SENSOR）。
   *
   * 想严格跟随系统的用户可以在 GSY 设置里打开它。
   */
  val rotateWithSystem = preferenceStore.getBoolean("gsy_rotate_with_system", false)

  // ── 界面（安全区） ────────────────────────────────────────────
  /** 播放页是否显示系统状态栏；关闭后顶栏会顶到屏幕上沿（内容仍会自动避开挖孔） */
  val showSystemStatusBar = preferenceStore.getBoolean("gsy_show_system_status_bar", true)

  /** 播放页是否显示系统导航栏 */
  val showSystemNavigationBar = preferenceStore.getBoolean("gsy_show_system_navigation_bar", true)
}
