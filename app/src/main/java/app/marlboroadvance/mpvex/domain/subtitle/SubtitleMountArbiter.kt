package app.marlboroadvance.mpvex.domain.subtitle

/** 字幕来源同时代表它在「选择权」里的优先级。数值越大越优先。 */
enum class SubtitleSource(val priority: Int, val automatic: Boolean) {
  MANUAL(100, false),
  INTENT(90, true),
  BOUND(80, true),
  SAVED(70, true),
  ONLINE_LIBRARY(60, true),
  // 网络兜底最后才触发；触发后必须能顶掉普通自动来源留下的未落地占用。
  ONLINE_AUTO_FETCH(65, true),
  REMEMBERED(55, true),
  AUTOLOAD(50, true),
  EMBY_AUTO(40, true),
  SAVED_EXTERNAL(0, true),
  SAVE_DIRECTORY(0, true),
}

/** 一次挂载请求；删除标记与播放会话在进入任何 mpv 命令前统一裁决。 */
data class SubtitleMountRequest(
  val sessionId: Long,
  val source: SubtitleSource,
  val select: Boolean,
  val ignored: Boolean,
)

sealed interface SubtitleMountDecision {
  data object MountAndSelect : SubtitleMountDecision
  data object MountOnly : SubtitleMountDecision
  data object RejectIgnored : SubtitleMountDecision
  data object RejectStale : SubtitleMountDecision
  data object RejectSuppressed : SubtitleMountDecision
}

/** 当前播放会话的字幕选择状态。 */
data class SubtitleMountState(
  val sessionId: Long = 0L,
  val selectionSource: SubtitleSource? = null,
  val userOverride: Boolean = false,
)

/**
 * 本地、网络与 Emby 字幕共用的挂载/选择仲裁。
 *
 * 这里不碰 Android、mpv 或磁盘，只固定四条不变量：
 * 1. tombstone 是硬拒绝，任何来源都不能绕过；
 * 2. 旧会话的迟到结果不能挂到新媒体；
 * 3. 用户本次手动操作后，自动来源只能挂入列表，不能抢 sid；
 * 4. 自动来源按固定优先级竞争，同优先级先到先得。
 */
object SubtitleMountArbiter {
  fun startSession(previous: SubtitleMountState, sessionId: Long): SubtitleMountState =
    SubtitleMountState(sessionId = sessionId)

  fun decide(state: SubtitleMountState, request: SubtitleMountRequest): SubtitleMountDecision =
    when {
      request.sessionId != state.sessionId -> SubtitleMountDecision.RejectStale
      request.ignored -> SubtitleMountDecision.RejectIgnored
      request.source == SubtitleSource.EMBY_AUTO && state.userOverride ->
        SubtitleMountDecision.RejectSuppressed
      !request.select -> SubtitleMountDecision.MountOnly
      request.source.automatic && state.userOverride -> SubtitleMountDecision.MountOnly
      else -> {
        val owner = state.selectionSource
        val lowerPriority = owner != null && owner.priority > request.source.priority
        val sameAutomaticOwner = owner != null && owner.automatic && request.source.automatic &&
          owner.priority == request.source.priority
        if (lowerPriority || sameAutomaticOwner) {
          SubtitleMountDecision.MountOnly
        } else {
          SubtitleMountDecision.MountAndSelect
        }
      }
    }

  fun onSelectionAccepted(state: SubtitleMountState, source: SubtitleSource): SubtitleMountState =
    when {
      source == SubtitleSource.MANUAL -> state.copy(selectionSource = source, userOverride = true)
      !source.automatic -> state
      else -> {
        val owner = state.selectionSource
        if (owner == null || source.priority > owner.priority) {
          state.copy(selectionSource = source)
        } else {
          state
        }
      }
    }

  /** 存档明确为「无字幕」时只清选择权，不当成某个来源占着 sid。 */
  fun onSelectionCleared(state: SubtitleMountState): SubtitleMountState =
    state.copy(selectionSource = null)

  fun onUserAction(state: SubtitleMountState): SubtitleMountState =
    state.copy(selectionSource = SubtitleSource.MANUAL, userOverride = true)

  /** 仅用于用户明确点「自动选择」时解除本次手动压制。 */
  fun clearUserOverride(state: SubtitleMountState): SubtitleMountState =
    if (state.userOverride) SubtitleMountState(sessionId = state.sessionId) else state
}
