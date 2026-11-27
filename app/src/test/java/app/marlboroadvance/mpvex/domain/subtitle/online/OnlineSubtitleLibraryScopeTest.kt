package app.marlboroadvance.mpvex.domain.subtitle.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络字幕媒体库排除的纯逻辑测试。
 *
 * 重点固定「按服务器 + 账号 + 库三元组绑定」这条约束 —— 用户明确要求排除项
 * 不能跨服务器 / 跨账号串台。key 格式变更会让已持久化的排除项失效，所以这里
 * 把格式也一起钉住。
 */
class OnlineSubtitleLibraryScopeTest {
  @Test
  fun `key binds server user and library`() {
    val key = OnlineSubtitleLibraryScope.key(serverId = 7, userId = "u-1", libraryId = "lib-3")
    assertTrue(key.contains("7"))
    assertTrue(key.contains("u-1"))
    assertTrue(key.contains("lib-3"))
  }

  @Test
  fun `same library id on another server does not collide`() {
    // 两台服务器的库 Id 恰好相同（Emby 库 Id 只在单服内唯一）——不能串台。
    val excluded = setOf(OnlineSubtitleLibraryScope.key(1, "u", "lib-x"))
    assertTrue(OnlineSubtitleLibraryScope.isExcluded(excluded, 1, "u", "lib-x"))
    assertFalse(OnlineSubtitleLibraryScope.isExcluded(excluded, 2, "u", "lib-x"))
  }

  @Test
  fun `same library id under another user does not collide`() {
    // 同一服务器换账号，可见库可能不同——也不能串台。
    val excluded = setOf(OnlineSubtitleLibraryScope.key(1, "alice", "lib-x"))
    assertTrue(OnlineSubtitleLibraryScope.isExcluded(excluded, 1, "alice", "lib-x"))
    assertFalse(OnlineSubtitleLibraryScope.isExcluded(excluded, 1, "bob", "lib-x"))
  }

  @Test
  fun `default empty set means all libraries allowed`() {
    assertFalse(OnlineSubtitleLibraryScope.isExcluded(emptySet(), 1, "u", "lib-x"))
  }

  @Test
  fun `unknown library id is allowed`() {
    // 认不出库（null / 空白）时放行，不让配置把自动下载弄丢。
    val excluded = setOf(OnlineSubtitleLibraryScope.key(1, "u", "lib-x"))
    assertFalse(OnlineSubtitleLibraryScope.isExcluded(excluded, 1, "u", null))
    assertFalse(OnlineSubtitleLibraryScope.isExcluded(excluded, 1, "u", ""))
  }
}
