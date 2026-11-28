package app.marlboroadvance.mpvex.domain.subtitle.online

import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleLibraryScope.Anchor
import app.marlboroadvance.mpvex.domain.subtitle.online.OnlineSubtitleLibraryScope.LibraryRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络字幕媒体库排除的纯逻辑测试。
 *
 * 重点固定两条约束：
 * 1. 「按服务器 + 账号 + 库三元组绑定」—— 用户明确要求排除项不能跨服务器 /
 *    跨账号串台。key 格式变更会让已持久化的排除项失效，所以格式也一起钉住。
 * 2. 起播上下文里的媒体库 Id 是权威判据；历史 / 搜索等旧入口才用祖先链与
 *    媒体库根路径兜底，不能把中间目录误当成库。
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

  // ── resolveLibraryId：祖先链上挑「用户设置的那个库」 ──

  @Test
  fun `plain folder first is not mistaken for the library`() {
    // 真机形态：片子 → 番号目录(Folder) → 分类目录(Folder) → 库(CollectionFolder) → 首页(UserView)。
    // 旧实现取「链上第一个 CollectionFolder/UserView」= 库，这一条其实是对的；
    // 但把 UserView（首页）也当候选时，若链序颠倒就会挑中「首页」。这里固定：
    // 链上出现多个候选时，优先取用户在设置里排除过的那一个。
    val chain =
      listOf(
        Anchor("cat", "Folder"),
        Anchor("lib-c", "CollectionFolder"),
        Anchor("views", "UserView"),
      )
    assertEquals("lib-c", OnlineSubtitleLibraryScope.resolveLibraryId(chain, emptySet()))
    assertEquals("lib-c", OnlineSubtitleLibraryScope.resolveLibraryId(chain, setOf("lib-c")))
    assertEquals(
      "views",
      OnlineSubtitleLibraryScope.resolveLibraryId(chain, setOf("views")),
    )
  }

  @Test
  fun `nested media path matches excluded library root`() {
    val libraries =
      listOf(
        LibraryRoot("lib-115", "/media/115"),
        LibraryRoot("lib-movies", "/media/movies"),
      )
    assertEquals(
      "lib-115",
      OnlineSubtitleLibraryScope.resolveLibraryIdByPath(
        "/media/115/folder/video.mkv",
        libraries,
      ),
    )
  }

  @Test
  fun `path match respects directory boundary`() {
    val libraries = listOf(LibraryRoot("lib-115", "/media/115"))
    assertNull(OnlineSubtitleLibraryScope.resolveLibraryIdByPath("/media/1150/video.mkv", libraries))
    assertNull(OnlineSubtitleLibraryScope.resolveLibraryIdByPath(null, libraries))
    assertNull(OnlineSubtitleLibraryScope.resolveLibraryIdByPath("/media/115/video.mkv", listOf(LibraryRoot("x", null))))
  }

  @Test
  fun `windows library paths ignore case`() {
    val libraries = listOf(LibraryRoot("lib-115", "D:\\Media\\115\\"))
    assertEquals(
      "lib-115",
      OnlineSubtitleLibraryScope.resolveLibraryIdByPath("d:/media/115/folder/video.mkv", libraries),
    )
  }

  @Test
  fun `overlapping library roots choose the most specific path`() {
    val libraries =
      listOf(
        LibraryRoot("lib-parent", "/media"),
        LibraryRoot("lib-child", "/media/115"),
      )
    assertEquals(
      "lib-child",
      OnlineSubtitleLibraryScope.resolveLibraryIdByPath("/media/115/video.mkv", libraries),
    )
  }

  @Test
  fun `no collection folder in chain yields null instead of guessing`() {
    // 链上全是普通目录 ⇒ 认不出库，返回 null（调用方放行），绝不拿中间目录去比。
    val chain = listOf(Anchor("cat", "Folder"), Anchor("media", "Folder"))
    assertNull(OnlineSubtitleLibraryScope.resolveLibraryId(chain, setOf("lib-c")))
  }

  @Test
  fun `empty chain yields null`() {
    assertNull(OnlineSubtitleLibraryScope.resolveLibraryId(emptyList(), setOf("lib-c")))
  }
}
