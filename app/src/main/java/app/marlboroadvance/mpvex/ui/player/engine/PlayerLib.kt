package app.marlboroadvance.mpvex.ui.player.engine

import android.graphics.Bitmap
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import app.marlboroadvance.mpvex.ui.player.TrackNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放内核门面（facade）。
 *
 * 全项目对播放内核的调用统一走这里，不再直接碰 [MPVLib]。
 *
 * 历史上这里还挂过一个 GSYVideoPlayer 分支（mpv 的 option/property 三元组被逐条
 * "翻译"成 GSY 的调用），那套翻译层是闪退 / 黑屏 / 拉满音量的根源，已整体删除。
 * 现在门面只转发 mpv，预留的 `kind` 字段保持原样，避免动到界面上几百处调用点。
 */
object PlayerLib {
  /** 当前内核。整个进程内保持一致。 */
  @Volatile
  var kind: EngineKind = EngineKind.MPV

  // ─────────────── 内核就绪门禁（崩溃 #26 的根治手段）───────────────

  /**
   * mpv 内核**是否已经 create 过**。
   *
   * ## 为什么必须有这个门禁
   *
   * AAR 里的 `MPVLib` 每个方法（`attachSurface` / `setPropertyString` / `observeProperty` /
   * `grabThumbnail` …）都是 **native**，它们操作的句柄由 `MPVLib.create()` 分配。
   * 在 `create()` 之前调用它们，句柄是 0 ⇒ JNI 层解引用空指针 ⇒ **SIGSEGV**。
   * 这类崩溃发生在 native 栈里，**JVM 的 `try/catch` 拦不住**，`runCatching` 也一样没用 ——
   * 表现为「点播放 → 整个 app 闪退」，而且拿不到 Java 堆栈。
   *
   * ## 曾经的真实事故
   *
   * `PlayerActivity.onCreate` 一度把内核初始化搬进
   * `lifecycleScope.launch { withContext(IO) { … } ; player.initialize() }`，
   * 于是 `onCreate` 在内核就绪前就返回了。而 `MPVView` 是 `SurfaceView`：
   * `setContentView` 之后首帧 traversal 就会触发 `surfaceCreated` →
   * `MPVLib.attachSurface(surface)`，此时 IO 段（首次进播放页要拷 assets，
   * 几百 ms 到几秒）还没跑完，内核根本没 create ⇒ 必崩。
   * 同一窗口里 `onResume → updateVolume() → viewModel` 也会让 `PlayerViewModel`
   * 构造，其字段初始化里的 `PlayerLib.propInt[…]` 首次调用会触发 native
   * `observeProperty`，同样是死路。
   *
   * 所以**恢复「`onCreate` 返回时内核已就绪」的同步时序是主修复**，
   * 本门禁是第二道防线：任何将来又有人挪动初始化时机的地方，都会被静默挡掉，
   * 表现退化成「值读不到 / 属性写不进去」，而不是整个进程崩掉。
   *
   * 由 [markCoreReady] 在 `player.initialize()` 之后、[markCoreGone] 在 `destroy()` 之前设置。
   */
  @Volatile
  private var coreReady: Boolean = false

  /** 内核 create 成功、句柄可用。由 `PlayerActivity` 在 `player.initialize()` 之后调。 */
  fun markCoreReady() {
    coreReady = true
  }

  /**
   * 内核即将销毁（`destroy()` 之前）时先把门禁关上。
   *
   * 顺序很重要：必须**先**关闸再 `destroy()`，否则 `destroy()` 之后到关闸之间的
   * 任何一次属性读都会打在已释放的句柄上 —— 和没 create 时一样是 native 崩溃。
   */
  fun markCoreGone() {
    coreReady = false
  }

  /** 内核当前是否可安全调用。仅供诊断/测试用。 */
  val isCoreReady: Boolean get() = coreReady

  private val isMpv: Boolean get() = kind == EngineKind.MPV

  /**
   * 真正可以打 native 的条件：**内核是 mpv，且已经 create 过**。
   *
   * 少了 `coreReady` 这一半，`isMpv` 为真就意味着「随便打」，而实际句柄可能还不存在。
   */
  private val canTouchNative: Boolean get() = isMpv && coreReady

  // ─────────────── 属性流（Compose 侧 collectAsState 用） ───────────────

  class Props<T>(
    private val provider: (String) -> StateFlow<T?>,
  ) {
    operator fun get(key: String): StateFlow<T?> = provider(key)
  }

  private fun <T> emptyStateFlow(): MutableStateFlow<T?> = MutableStateFlow(null)

  val propInt =
    Props<Int?> { key ->
      if (canTouchNative) MPVLib.propInt[key] else emptyStateFlow()
    }

  val propFloat =
    Props<Float?> { key ->
      if (canTouchNative) MPVLib.propFloat[key] else emptyStateFlow()
    }

  val propDouble =
    Props<Double?> { key ->
      if (canTouchNative) MPVLib.propDouble[key] else emptyStateFlow()
    }

  val propBoolean =
    Props<Boolean?> { key ->
      if (canTouchNative) MPVLib.propBoolean[key] else emptyStateFlow()
    }

  val propString =
    Props<String?> { key ->
      if (canTouchNative) MPVLib.propString[key] else emptyStateFlow()
    }

  val propNode =
    Props<MPVNode?> { key ->
      if (canTouchNative) MPVLib.propNode[key] else emptyStateFlow()
    }

  /** 轨道列表。mpv 侧由 track-list 推导；非 mpv 内核为空。 */
  val gsyTrackNodes = MutableStateFlow<List<TrackNode>>(emptyList())

  // ─────────────── 属性读 ───────────────

  fun getPropertyInt(key: String): Int? = if (canTouchNative) MPVLib.getPropertyInt(key) else null

  fun getPropertyFloat(key: String): Float? = if (canTouchNative) MPVLib.getPropertyFloat(key) else null

  fun getPropertyBoolean(key: String): Boolean? = if (canTouchNative) MPVLib.getPropertyBoolean(key) else null

  fun getPropertyDouble(key: String): Double? = if (canTouchNative) MPVLib.getPropertyDouble(key) else null

  fun getPropertyString(key: String): String? = if (canTouchNative) MPVLib.getPropertyString(key) else null

  /** 读 node 型属性（如 demuxer-cache-state）；非 mpv 内核或内核未就绪恒为 null */
  fun getPropertyNode(key: String): MPVNode? = if (canTouchNative) MPVLib.getPropertyNode(key) else null

  // ─────────────── 属性写 ───────────────

  fun setPropertyInt(
    key: String,
    value: Int,
  ) {
    if (canTouchNative) MPVLib.setPropertyInt(key, value)
  }

  fun setPropertyFloat(
    key: String,
    value: Float,
  ) {
    if (canTouchNative) MPVLib.setPropertyFloat(key, value)
  }

  fun setPropertyBoolean(
    key: String,
    value: Boolean,
  ) {
    if (canTouchNative) MPVLib.setPropertyBoolean(key, value)
  }

  fun setPropertyDouble(
    key: String,
    value: Double,
  ) {
    if (canTouchNative) MPVLib.setPropertyDouble(key, value)
  }

  fun setPropertyString(
    key: String,
    value: String,
  ) {
    if (canTouchNative) MPVLib.setPropertyString(key, value)
  }

  /** mpv 专属的启动选项（sub-font / hwdec / glsl-shaders ...） */
  fun setOptionString(
    key: String,
    value: String,
  ) {
    if (canTouchNative) MPVLib.setOptionString(key, value)
  }

  // ─────────────── 命令 ───────────────

  fun command(vararg args: String) {
    if (canTouchNative) MPVLib.command(*args)
  }

  // ─────────────── 生命周期 / 事件 ───────────────

  /** AAR 的 MpvFormat 是 object + Int 常量（非枚举），format 直接传 Int */
  fun observeProperty(
    name: String,
    format: Int,
  ) {
    if (canTouchNative) MPVLib.observeProperty(name, format)
  }

  fun addObserver(observer: MPVLib.EventObserver) {
    if (canTouchNative) MPVLib.addObserver(observer)
  }

  fun removeObserver(observer: MPVLib.EventObserver) {
    if (canTouchNative) MPVLib.removeObserver(observer)
  }

  /**
   * mpv 是否已经空闲（`idle-active == yes`）。
   *
   * ## 为什么需要它
   *
   * `command("quit")` 是**异步**的：它只是把命令投进 mpv 的命令队列就返回，
   * 此刻 mpv 内部线程（硬解的 UI 线程、音频输出线程）**还在跑**。
   * 紧接着就 `destroy()` 的话，那些线程可能正在访问被销毁的互斥量/队列 ⇒ 原生崩溃。
   *
   * 原来的做法是主线程 `Thread.sleep(100)` 硬等 —— 那是「用固定时间赌它退完了」，
   * 既卡主线程又不可靠（慢机器上 100ms 不够）。
   * 有了这个属性就能**轮询到真正空闲为止**，且带上限，避免死等。
   *
   * 拿不到（GSY 内核 / 读取失败）时返回 true —— 即「视为已空闲」，
   * 宁可少等也不能卡住退出。
   */
  fun isIdleActive(): Boolean {
    if (!canTouchNative) return true
    return runCatching { MPVLib.getPropertyString("idle-active") == "yes" }.getOrDefault(true)
  }

  /**
   * 释放内核。
   *
   * ⚠️ 这里**故意绕过** [canTouchNative] 门禁：`destroy()` 存在的意义就是销毁内核，
   * 而它被调用时（正常退出流程）门禁往往已经被 [markCoreGone] 关上了。
   * 若也按门禁短路，`destroy()` 会变成空操作 ⇒ 内核永不释放 ⇒ 反复进播放页必然泄漏。
   *
   * 安全性由调用方保证：[PlayerActivity.cleanupMPV] 只在 `mpvInitialized == true` 时才走到这里，
   * 也就是确实 create 过。
   */
  fun destroy() {
    if (isMpv) MPVLib.destroy()
  }

  /** 截图 */
  fun grabThumbnail(size: Int): Bitmap? = if (canTouchNative) MPVLib.grabThumbnail(size) else null
}
