package app.marlboroadvance.mpvex.domain.emby

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主 / 备 Emby 服务器的自动接管。
 *
 * ## 它解决什么问题
 *
 * 用户可能有两条通往同一套媒体的路：家里的 Emby（主）和一台 VPS / 另一处宽带的 Emby（备）。
 * 主用不了的时候（断电、断网、DDNS 没跟上、路由器重启），希望 App 自己挪到备用上继续用，
 * 主恢复后再挪回来。
 *
 * ## 状态机
 *
 * ```
 *          请求真失败 + 探活也失败
 *   Normal ────────────────────────▶ Degraded(在用备用)
 *      ▲                                    │
 *      └────────────────────────────────────┘
 *           备用那台探活成功（每 20s 一次）
 * ```
 *
 * · [FailoverState.Normal]：在用服务器是主，一切正常；
 * · [FailoverState.Degraded]：已经因为故障挪到备用上，后台每 [RECOVERY_POLL_MS] 探一次主；
 * · [FailoverState.NoBackup]：主不通、但这台又没配备用 —— 停在这里，别再重试；
 * · [FailoverState.Failed]：主不通、备用也探不通（备用那台自己也挂了），同样停下。
 *
 * ## 判据为什么是「真实请求失败 + 一次探活」
 *
 * 只用定时心跳会在没事的时候白打一堆请求；只用请求失败又会把「某个接口偶发超时」
 * 当成整台服务器挂了。所以取两者交集：**先有一条真实请求在重试用尽后仍然失败**，
 * 再补一次 3~6 秒的轻量探活（`System/Info/Public`，不碰媒体、不跟随 302），
 * 两个都失败才算这台不可用。
 *
 * ## 为什么不影响用户手动切换
 *
 * · 用户点某台服务器 = 明确意图：[onUserSelected] 立刻清掉降级态、停掉恢复轮询，
 *   并且**不会**被后台逻辑改回来；
 * · 自动切换只改内存里的「当前服务器」（[switchInMemory]），**不写**
 *   `emby_session/current_server_id` —— 所以重启 App 后仍然回到用户选的那台，
 *   而不是"永远停在上次自动切到的备用上"；
 * · 开关关掉时整套逻辑不参与任何判断，行为与加这个功能之前完全一致。
 *
 * ## 落盘口径
 *
 * 全在独立的 `emby_failover` 偏好里，**不动 Room 表**（也就没有迁移风险）：
 * · `enabled`；
 * · `backup_<主服务器 id>` → 备用服务器 id。每台都可以有一个备用，于是
 *   A→B、B→C 天然构成一条链，[buildChain] 会顺着走，最多 [MAX_CHAIN] 跳。
 */
class EmbyFailoverController(
  context: Context,
  private val scope: CoroutineScope,
  private val currentServerProvider: () -> EmbyServer?,
  /** 只切内存里的当前服务器，不覆盖用户的持久化选择（见类注释） */
  private val switchInMemory: (EmbyServer) -> Unit,
  private val loadServers: suspend () -> List<EmbyServer>,
) {

  /** 对外暴露的接管状态，UI 直接照着画 */
  sealed interface FailoverState {
    /** 开关关着，这套逻辑完全不参与 */
    data object Off : FailoverState

    /** 在用服务器就是主，正常 */
    data object Normal : FailoverState

    /** 已因故障切到备用；[from] 是等它恢复的那台 */
    data class Degraded(val from: EmbyServer, val to: EmbyServer) : FailoverState

    /** 主不通，而这台没配备用 */
    data class NoBackup(val from: EmbyServer) : FailoverState

    /** 主不通，备用也探不通 */
    data class Failed(val from: EmbyServer) : FailoverState
  }

  private val prefs: SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /**
   * 「主服务器 id → 备用服务器 id」映射的**可观察**副本。
   *
   * ⚠️ 别让 UI 直接调 [backupIdFor] 来取数：那只是读一遍 SharedPreferences，
   * 写进去不会触发重组 —— 用户设完备用回到列表，看到的还是「未设置」，
   * 得退出重进才有（v146 踩过）。UI 一律 collect 这个 flow。
   */
  private val _backups = MutableStateFlow(readBackups())
  val backups: StateFlow<Map<Long, Long>> = _backups.asStateFlow()

  private fun readBackups(): Map<Long, Long> =
    buildMap {
      prefs.all.forEach { (k, v) ->
        if (k.startsWith(BACKUP_PREFIX) && v is Long && v > 0) {
          k.removePrefix(BACKUP_PREFIX).toLongOrNull()?.let { put(it, v) }
        }
      }
    }

  private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
  val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

  private val _state = MutableStateFlow<FailoverState>(
    if (_enabled.value) FailoverState.Normal else FailoverState.Off,
  )
  val state: StateFlow<FailoverState> = _state.asStateFlow()

  /**
   * 自动切换（切去备用 / 切回主）成功时发一次。
   *
   * UI 必须据此**重新拉数据**：两台服务器各自一套 Item Id，沿用旧列表点进去必然 404。
   * 用 SharedFlow 而不是让 UI 监听 [state] 的变化，是因为「切回主」和「切到备用」
   * 在 [FailoverState] 上分别表现为 Degraded / Normal，Normal 也是初始态，
   * 只看状态分辨不出「刚回来」，容易漏刷新或重复刷新。
   */
  private val _autoSwitched = MutableSharedFlow<EmbyServer>(extraBufferCapacity = 4)
  val autoSwitched: SharedFlow<EmbyServer> = _autoSwitched.asSharedFlow()

  /** 因故障降级时记下「原主服务器 id」；null = 当前不是降级态 */
  @Volatile
  private var degradedFromId: Long? = null

  /** 正在切换 / 正在探测，避免同一次故障触发多条并发链 */
  @Volatile
  private var busy = false

  /** 上次触发失败上报的时间：一次故障会连着报好几条，这里做 5 秒合流 */
  @Volatile
  private var lastFailureAt = 0L

  /** 刚切过就从「失败」里恢复：给新服务器一点时间，别一上来又判死 */
  @Volatile
  private var lastSwitchAt = 0L

  private var recoveryJob: Job? = null

  // ──────────────────────────── 配置读写 ────────────────────────────

  fun setEnabled(on: Boolean) {
    prefs.edit().putBoolean(KEY_ENABLED, on).apply()
    _enabled.value = on
    if (on) {
      _state.value = FailoverState.Normal
    } else {
      stopRecovery()
      degradedFromId = null
      _state.value = FailoverState.Off
    }
  }

  /** 某台服务器配的备用（null = 没配） */
  fun backupIdFor(primaryId: Long): Long? = _backups.value[primaryId]?.takeIf { it > 0 }

  /**
   * 设置 / 取消某台的备用。
   *
   * 会顺手清掉指向自己的脏数据（A 备 B、B 备 A 这种环），否则 [buildChain] 要额外兜底。
   */
  fun setBackup(primaryId: Long, backupId: Long?) {
    val editor = prefs.edit()
    if (backupId == null || backupId == primaryId) {
      editor.remove(keyBackup(primaryId))
    } else {
      editor.putLong(keyBackup(primaryId), backupId)
      // 反向指向自己的一律拆掉：互为备用会让链路在原地打转
      if (prefs.getLong(keyBackup(backupId), -1L) == primaryId) {
        editor.remove(keyBackup(backupId))
      }
    }
    editor.apply()
    // 只有这条赋值能让界面刷新（见 [_backups] 的说明）
    _backups.value = readBackups()
  }

  /** 服务器被删除时清掉与它相关的映射，避免留一堆指向不存在的 id */
  fun forgetServer(serverId: Long) {
    val editor = prefs.edit().remove(keyBackup(serverId))
    prefs.all.forEach { (k, v) ->
      if (k.startsWith(BACKUP_PREFIX) && v is Long && v == serverId) editor.remove(k)
    }
    editor.apply()
    _backups.value = readBackups()
    if (degradedFromId == serverId) {
      stopRecovery()
      degradedFromId = null
      _state.value = if (_enabled.value) FailoverState.Normal else FailoverState.Off
    }
  }

  // ──────────────────────────── 事件入口 ────────────────────────────

  /**
   * 用户手动选了某台服务器。
   *
   * 「手动」是最高优先级：清降级态、停恢复轮询，之后不再自动改回来 ——
   * 用户既然亲手点了，就说明他知道自己在选什么。
   */
  fun onUserSelected(server: EmbyServer) {
    stopRecovery()
    degradedFromId = null
    lastFailureAt = 0L
    lastSwitchAt = System.currentTimeMillis()
    if (_enabled.value) _state.value = FailoverState.Normal
    Log.i(TAG, "用户手动切到「${server.name}」，自动接管暂停")
  }

  /**
   * 一条真实请求在重试用尽后仍然失败了（[EmbyHttp.onTransientFailure] 上报）。
   *
   * 只把它当「线索」：真正的判据是随后的探活。合流窗口内的重复上报直接丢掉。
   */
  fun onRequestFailure(host: String) {
    if (!_enabled.value || busy) return
    val now = System.currentTimeMillis()
    // 刚切完的那 30 秒不判死：新服务器可能只是还在建立连接
    if (now - lastSwitchAt < SETTLE_MS) return
    if (now - lastFailureAt < FAILURE_MERGE_MS) return
    val current = currentServerProvider() ?: return
    if (!current.host.equals(host, ignoreCase = true)) return
    lastFailureAt = now

    scope.launch { probeAndFailoverIfDead(current) }
  }

  /**
   * 后台恢复轮询用：主服务器一恢复就切回去。
   *
   * 只在 [FailoverState.Degraded] 下有效 —— 用户手动切过之后 [degradedFromId] 已经是 null，
   * 这里自然什么都不做。
   */
  private fun startRecoveryPoll() {
    recoveryJob?.cancel()
    recoveryJob =
      scope.launch {
        while (isActive) {
          delay(RECOVERY_POLL_MS)
          val fromId = degradedFromId ?: break
          // ⚠️ 这里不能写 `?: run { ...; break }` —— break 不能跨 lambda 边界（inline 也不行）
          val primary = runCatching { loadServers() }.getOrDefault(emptyList())
            .firstOrNull { it.id == fromId }
          if (primary == null) {
            // 那台服务器已经被删了：退出降级态
            degradedFromId = null
            _state.value = if (_enabled.value) FailoverState.Normal else FailoverState.Off
            break
          }
          val alive = withContext(Dispatchers.IO) { EmbyClient.probeAlive(primary) }
          if (!alive) continue
          Log.i(TAG, "主服务器「${primary.name}」已恢复，切回")
          switchInMemory(primary)
          degradedFromId = null
          lastSwitchAt = System.currentTimeMillis()
          _state.value = FailoverState.Normal
          _autoSwitched.tryEmit(primary)
          break
        }
      }
  }

  private fun stopRecovery() {
    recoveryJob?.cancel()
    recoveryJob = null
  }

  // ──────────────────────────── 切换实现 ────────────────────────────

  /** 探活确认当前这台真的不可用，才继续往下走 */
  private suspend fun probeAndFailoverIfDead(dead: EmbyServer) {
    if (busy) return
    busy = true
    try {
      val alive = withContext(Dispatchers.IO) { EmbyClient.probeAlive(dead) }
      if (alive) {
        // 只是那一个请求的问题（超时 / 偶发 5xx）：不动服务器
        Log.i(TAG, "「${dead.name}」单次请求失败，但探活正常，不切换")
        return
      }
      failover(dead)
    } finally {
      busy = false
    }
  }

  private suspend fun failover(dead: EmbyServer) {
    val servers = runCatching { loadServers() }.getOrDefault(emptyList())
    val chain = buildChain(dead, servers)
    if (chain.isEmpty()) {
      Log.w(TAG, "「${dead.name}」不可用，且没有配置备用服务器")
      _state.value = FailoverState.NoBackup(dead)
      return
    }
    val target = chain.firstOrNull { withContext(Dispatchers.IO) { EmbyClient.probeAlive(it) } }
    if (target == null) {
      Log.w(TAG, "「${dead.name}」不可用，备用（${chain.joinToString { it.name }}）也探不通")
      _state.value = FailoverState.Failed(dead)
      return
    }
    Log.w(TAG, "「${dead.name}」不可用，切到备用「${target.name}」")
    degradedFromId = dead.id
    lastSwitchAt = System.currentTimeMillis()
    switchInMemory(target)
    _state.value = FailoverState.Degraded(from = dead, to = target)
    startRecoveryPoll()
    // 通知 UI 重拉：新服务器的 Item Id 与旧服务器不通用
    _autoSwitched.tryEmit(target)
  }

  /**
   * 顺着「备用」关系往下走，得到一条候选链（不含自己）。
   *
   * 跳过：自己、已经出现在链上的（防环）、登录信息都没了的（切过去也只会 401）。
   */
  private fun buildChain(start: EmbyServer, servers: List<EmbyServer>): List<EmbyServer> {
    val byId = servers.associateBy { it.id }
    val out = mutableListOf<EmbyServer>()
    val seen = mutableSetOf(start.id)
    var cursor: EmbyServer? = start
    while (out.size < MAX_CHAIN) {
      val nextId = cursor?.let { backupIdFor(it.id) } ?: break
      if (!seen.add(nextId)) break
      val next = byId[nextId] ?: break
      // token 或密码总得有一个，否则切过去也进不去
      if (next.isLoggedIn || next.password.isNotBlank()) out.add(next)
      cursor = next
    }
    return out
  }

  companion object {
    private const val TAG = "EmbyFailover"
    private const val PREFS_NAME = "emby_failover"
    private const val KEY_ENABLED = "enabled"
    private const val BACKUP_PREFIX = "backup_"

    /** 一次故障会连着报到好几条失败，5 秒内的后续上报直接合流 */
    private const val FAILURE_MERGE_MS = 5_000L

    /** 刚切完的 30 秒内不再判死（新服务器还在建连接） */
    private const val SETTLE_MS = 30_000L

    /** 降级后多久探一次主服务器 */
    private const val RECOVERY_POLL_MS = 20_000L

    /** 备用链最多走几跳 */
    private const val MAX_CHAIN = 3

    private fun keyBackup(primaryId: Long) = "$BACKUP_PREFIX$primaryId"
  }
}
