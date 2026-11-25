package app.marlboroadvance.mpvex.privacy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 启动阶段的唯一状态机。
 *
 * ## 为什么需要它
 *
 * 只弹一个对话框是不够的：真正要控制的是**初始化顺序**。
 * `Application.onCreate` 里那一长串初始化（崩溃上报、原生库、媒体扫描、
 * 凭据升级、字幕迁移…）在用户同意之前一条都不能跑。所以这里把启动切成两段：
 *
 * - **必要段**：Koin 容器、崩溃页处理器、启动标记。不涉及个人信息，照常执行。
 * - **同意后段**：由 [App] 以 lambda 形式交给本类托管，只在授权有效时执行一次。
 *
 * ```
 * APP_START
 *    ↓ 读本地记录
 *    ├── 已同意当前版本 → INITIALIZING → MAIN
 *    └── 未同意/已过期   → PRIVACY_REQUIRED →（用户勾选并同意）→ 保存记录 → INITIALIZING → MAIN
 * ```
 *
 * ## 为什么用 object 而不是 Koin single
 *
 * 它在 Koin 里也该是单例，但 `Application.onCreate` 必须在**任何 UI 出现之前**
 * 读它，而且读的时候 Koin 容器刚建好、还没有任何东西解析过它。做成 object
 * 省掉一次「容器是否可用」的判断，也不会出现两个实例各自记着不同的状态。
 *
 * ## 进程恢复
 *
 * 状态全在 SharedPreferences 里，本类只缓存一次判定结果。
 * 进程被杀后重启会重新读盘，因此「同意过就不再弹」不依赖任何内存变量。
 */
object AppStartupCoordinator {

  /** 启动门禁的三种状态。 */
  enum class GateState {
    /** 需要展示隐私确认界面。 */
    REQUIRED,

    /** 已获授权，可以进入主界面。 */
    GRANTED,
  }

  private val _gateState = MutableStateFlow(GateState.REQUIRED)

  /** 供 Compose 观察；初值取 [REQUIRED]，在 [start] 里同步修正。 */
  val gateState: StateFlow<GateState> = _gateState.asStateFlow()

  private var repository: PrivacyConsentRepository? = null
  private var deferredInit: (() -> Unit)? = null

  /** 同意后段是否已经跑过。撤回再重新同意时不重复执行。 */
  private var deferredDone = false

  /**
   * 由 `App.onCreate` 调用，判定门禁并注册同意后初始化。
   *
   * @param context 任意 Context，内部只取 applicationContext
   * @param deferred 同意后才允许执行的初始化
   */
  fun start(context: Context, deferred: () -> Unit) {
    val repo = PrivacyConsentRepository(context)
    repository = repo
    deferredInit = deferred

    val granted = PrivacyGateManager.isGranted(repo.read())
    _gateState.value = if (granted) GateState.GRANTED else GateState.REQUIRED
    if (granted) runDeferred()
  }

  /** 用户在确认页点击「同意并继续」。写记录 → 放行 → 跑同意后段。 */
  fun onConsentGranted() {
    val repo = repository ?: return
    repo.save(
      privacyPolicyVersion = PrivacyGateManager.PRIVACY_POLICY_VERSION,
      userAgreementVersion = PrivacyGateManager.USER_AGREEMENT_VERSION,
    )
    _gateState.value = GateState.GRANTED
    runDeferred()
  }

  /**
   * 撤回授权（设置页入口）。
   *
   * 只切状态并清记录：主界面会立刻被替换成确认页，需要授权的处理随之停止。
   * 已经在本进程里初始化过的组件（例如崩溃上报 SDK）无法反初始化，
   * 这一点在设置页文案里已明确告知用户「重启后完全生效」。
   */
  fun onConsentWithdrawn() {
    repository?.revoke()
    _gateState.value = GateState.REQUIRED
  }

  /** 当前记录快照，供设置页展示版本与同意时间。 */
  fun record(): ConsentRecord = repository?.read() ?: ConsentRecord.EMPTY

  /** 是否已授权。给非 Compose 的入口（播放页等）做同步判断。 */
  fun isGranted(): Boolean = _gateState.value == GateState.GRANTED

  private fun runDeferred() {
    if (deferredDone) return
    deferredDone = true
    deferredInit?.invoke()
  }
}
