package app.marlboroadvance.mpvex.domain.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleMountArbiterTest {
  @Test
  fun `删除标记对所有来源都是硬拒绝`() {
    val state = SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1)
    SubtitleSource.entries.forEach { source ->
      assertEquals(
        source.name,
        SubtitleMountDecision.RejectIgnored,
        SubtitleMountArbiter.decide(
          state,
          SubtitleMountRequest(1, source, select = true, ignored = true),
        ),
      )
    }
  }

  @Test
  fun `旧会话结果不能挂到新媒体`() {
    val state = SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 2)
    assertEquals(
      SubtitleMountDecision.RejectStale,
      SubtitleMountArbiter.decide(
        state,
        SubtitleMountRequest(1, SubtitleSource.AUTOLOAD, select = true, ignored = false),
      ),
    )
  }

  @Test
  fun `高优先级自动来源可以覆盖低优先级选择`() {
    var state = SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1)
    state = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.EMBY_AUTO)
    state = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.ONLINE_LIBRARY)
    state = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.SAVED)
    state = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.BOUND)
    assertEquals(SubtitleSource.BOUND, state.selectionSource)
  }

  @Test
  fun `低优先级自动来源只能挂入列表不能抢选择`() {
    var state = SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1)
    state = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.BOUND)
    assertEquals(
      SubtitleMountDecision.MountOnly,
      SubtitleMountArbiter.decide(
        state,
        SubtitleMountRequest(1, SubtitleSource.EMBY_AUTO, select = true, ignored = false),
      ),
    )
  }

  @Test
  fun `同优先级自动来源先到先得`() {
    var state = SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1)
    state = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.AUTOLOAD)
    state = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.AUTOLOAD)
    assertEquals(SubtitleSource.AUTOLOAD, state.selectionSource)
    assertEquals(
      SubtitleMountDecision.MountOnly,
      SubtitleMountArbiter.decide(
        state,
        SubtitleMountRequest(1, SubtitleSource.AUTOLOAD, select = true, ignored = false),
      ),
    )
  }

  @Test
  fun `用户手动操作锁定本次自动选择`() {
    val state = SubtitleMountArbiter.onUserAction(
      SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1),
    )
    assertEquals(
      SubtitleMountDecision.MountOnly,
      SubtitleMountArbiter.decide(
        state,
        SubtitleMountRequest(1, SubtitleSource.AUTOLOAD, select = true, ignored = false),
      ),
    )
    assertEquals(
      SubtitleMountDecision.RejectSuppressed,
      SubtitleMountArbiter.decide(
        state,
        SubtitleMountRequest(1, SubtitleSource.EMBY_AUTO, select = true, ignored = false),
      ),
    )
    assertEquals(
      SubtitleMountDecision.MountAndSelect,
      SubtitleMountArbiter.decide(
        state,
        SubtitleMountRequest(1, SubtitleSource.MANUAL, select = true, ignored = false),
      ),
    )
  }

  @Test
  fun `关闭字幕后释放选择权允许网络自动字幕选中`() {
    var state = SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1)
    state = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.SAVED)
    state = SubtitleMountArbiter.onSelectionCleared(state)
    assertEquals(null, state.selectionSource)
    assertEquals(
      SubtitleMountDecision.MountAndSelect,
      SubtitleMountArbiter.decide(
        state,
        SubtitleMountRequest(1, SubtitleSource.ONLINE_AUTO_FETCH, select = true, ignored = false),
      ),
    )
  }

  @Test
  fun `未落地的自动加载选择释放后网络字幕可选中`() {
    var state = SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1)
    state = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.AUTOLOAD)
    state = SubtitleMountArbiter.onSelectionCleared(state)
    assertEquals(
      SubtitleMountDecision.MountAndSelect,
      SubtitleMountArbiter.decide(
        state,
        SubtitleMountRequest(1, SubtitleSource.ONLINE_AUTO_FETCH, select = true, ignored = false),
      ),
    )
  }

  @Test
  fun `网络兜底可顶掉未落地的普通自动选择`() {
    listOf(
      SubtitleSource.ONLINE_LIBRARY,
      SubtitleSource.REMEMBERED,
      SubtitleSource.AUTOLOAD,
      SubtitleSource.EMBY_AUTO,
    ).forEach { owner ->
      var state = SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1)
      state = SubtitleMountArbiter.onSelectionAccepted(state, owner)
      assertEquals(
        owner.name,
        SubtitleMountDecision.MountAndSelect,
        SubtitleMountArbiter.decide(
          state,
          SubtitleMountRequest(1, SubtitleSource.ONLINE_AUTO_FETCH, select = true, ignored = false),
        ),
      )
      state = SubtitleMountArbiter.onSelectionAccepted(state, SubtitleSource.ONLINE_AUTO_FETCH)
      assertEquals(SubtitleSource.ONLINE_AUTO_FETCH, state.selectionSource)
    }
  }

  @Test
  fun `网络兜底不能越过显式字幕选择`() {
    listOf(
      SubtitleSource.SAVED,
      SubtitleSource.BOUND,
      SubtitleSource.INTENT,
    ).forEach { owner ->
      val state = SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1)
        .let { SubtitleMountArbiter.onSelectionAccepted(it, owner) }
      assertEquals(
        owner.name,
        SubtitleMountDecision.MountOnly,
        SubtitleMountArbiter.decide(
          state,
          SubtitleMountRequest(1, SubtitleSource.ONLINE_AUTO_FETCH, select = true, ignored = false),
        ),
      )
    }
  }

  @Test
  fun `用户手动选择后网络兜底不能抢字幕`() {
    val state = SubtitleMountArbiter.onUserAction(
      SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1),
    )
    assertEquals(
      SubtitleMountDecision.MountOnly,
      SubtitleMountArbiter.decide(
        state,
        SubtitleMountRequest(1, SubtitleSource.ONLINE_AUTO_FETCH, select = true, ignored = false),
      ),
    )
  }

  @Test
  fun `换片清空旧选择与手动压制`() {
    val old = SubtitleMountArbiter.onUserAction(
      SubtitleMountArbiter.startSession(SubtitleMountState(), sessionId = 1),
    )
    val next = SubtitleMountArbiter.startSession(old, sessionId = 2)
    assertEquals(false, next.userOverride)
    assertEquals(null, next.selectionSource)
    assertEquals(
      SubtitleMountDecision.MountAndSelect,
      SubtitleMountArbiter.decide(
        next,
        SubtitleMountRequest(2, SubtitleSource.EMBY_AUTO, select = true, ignored = false),
      ),
    )
  }
}
