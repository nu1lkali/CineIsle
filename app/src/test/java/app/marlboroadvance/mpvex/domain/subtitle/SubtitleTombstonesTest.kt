package app.marlboroadvance.mpvex.domain.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 删除字幕的键隔离、过滤与偏好序列化回归；全部为无 Android 依赖的纯 JVM 测试。 */
class SubtitleTombstonesTest {
  @Test
  fun `外挂路径做词法归一且拒绝临时 fd 与相对路径`() {
    assertEquals(
      "/storage/emulated/0/Movies/a file.srt",
      SubtitleTombstones.normalizeAbsolutePath(
        "file:///storage/emulated/0/Movies/tmp/../a%20file.srt",
      ),
    )
    assertEquals(
      "/storage/emulated/0/Movies/a.srt",
      SubtitleTombstones.normalizeAbsolutePath("\\storage\\emulated\\0\\Movies\\.\\a.srt"),
    )
    assertEquals("/", SubtitleTombstones.normalizeAbsolutePath("/tmp/.."))
    assertNull(SubtitleTombstones.normalizeAbsolutePath("fd://42"))
    assertNull(SubtitleTombstones.normalizeAbsolutePath("content://media/external/subtitles/1"))
    assertNull(SubtitleTombstones.normalizeAbsolutePath("subtitles/a.srt"))
  }

  @Test
  fun `外挂字幕只忽略匹配的路径或稳定来源 URI`() {
    val tombstone = SubtitleTombstones.external(
      "/storage/emulated/0/Movies/a.srt",
      "content://media/external/subtitles/1",
      createdAt = 10L,
    )!!
    assertTrue(tombstone.matches("/storage/emulated/0/Movies/x/../a.srt", null))
    assertTrue(tombstone.matches("fd://99", "content://media/external/subtitles/1"))
    assertFalse(tombstone.matches("/storage/emulated/0/Movies/b.srt", null))
    assertFalse(tombstone.matches("fd://99", "content://media/external/subtitles/2"))
    assertNull(SubtitleTombstones.external("fd://42", "fd://43"))
  }

  @Test
  fun `在线字幕按服务器条目与 origin 隔离`() {
    val tombstone = SubtitleTombstones.online(
      serverKey = "7",
      itemId = "item-a",
      originId = "provider:one",
      originUri = "https://example.test/one.srt",
      storagePath = "/data/user/0/app/files/online_subtitles/shared.srt",
    )!!
    assertTrue(
      tombstone.matches(
        SubtitleTombstones.onlineKey("7", "item-a", "provider:one", null, null)!!,
      ),
    )
    assertTrue(
      tombstone.matches(
        SubtitleTombstones.onlineKey("7", "item-a", null, "https://example.test/one.srt", null)!!,
      ),
    )
    assertFalse(
      tombstone.matches(
        SubtitleTombstones.onlineKey("8", "item-a", "provider:one", null, null)!!,
      ),
    )
    assertFalse(
      tombstone.matches(
        SubtitleTombstones.onlineKey("7", "item-b", "provider:one", null, null)!!,
      ),
    )
  }

  @Test
  fun `在线字幕共享存储路径不会误伤不同 origin`() {
    val tombstone = SubtitleTombstones.online(
      serverKey = "7",
      itemId = "item-a",
      originId = "provider:one",
      originUri = null,
      storagePath = "/data/files/online_subtitles/shared.srt",
    )!!
    val otherOrigin = SubtitleTombstones.onlineKey(
      "7",
      "item-a",
      "provider:two",
      null,
      "/data/files/online_subtitles/shared.srt",
    )!!
    assertFalse(tombstone.matches(otherOrigin))
  }

  @Test
  fun `旧在线记录只在稳定 origin 缺失时按存储路径回退`() {
    val tombstone = SubtitleTombstones.online(
      serverKey = "7",
      itemId = "item-a",
      originId = null,
      originUri = null,
      storagePath = "7-item-a/shared.srt",
    )!!
    assertTrue(
      tombstone.matches(
        SubtitleTombstones.onlineKey("7", "item-a", null, null, "7-item-a/x/../shared.srt")!!,
      ),
    )
    assertFalse(
      tombstone.matches(
        SubtitleTombstones.onlineKey("7", "item-a", "provider:one", null, null)!!,
      ),
    )
  }

  @Test
  fun `Emby 键严格隔离服务器条目媒体源与流索引`() {
    val tombstone = SubtitleTombstones.emby("1", "item", null, 3)!!
    assertTrue(tombstone.matches(SubtitleTombstones.embyKey("1", "item", "", 3)!!))
    assertFalse(tombstone.matches(SubtitleTombstones.embyKey("2", "item", null, 3)!!))
    assertFalse(tombstone.matches(SubtitleTombstones.embyKey("1", "other", null, 3)!!))
    assertFalse(tombstone.matches(SubtitleTombstones.embyKey("1", "item", "ms-1", 3)!!))
    assertFalse(tombstone.matches(SubtitleTombstones.embyKey("1", "item", null, 4)!!))
    assertNull(SubtitleTombstones.emby("", "item", null, 3))
    assertNull(SubtitleTombstones.emby("1", "item", null, -1))
  }

  @Test
  fun `通用过滤同时覆盖路径 URI 与隔离键`() {
    val tombstones = listOf(
      SubtitleTombstones.external("/subtitles/deleted.srt", null)!!,
      SubtitleTombstones.external(null, "content://sub/1")!!,
      SubtitleTombstones.online("7", "item", "origin-a", null, null)!!,
      SubtitleTombstones.emby("1", "item", "ms", 2)!!,
    )
    val paths = listOf("/subtitles/deleted.srt", "/subtitles/keep.srt")
    assertEquals(
      listOf("/subtitles/keep.srt"),
      SubtitleTombstones.filterExternal(paths, tombstones, { it }),
    )
    val uris = listOf("content://sub/1", "content://sub/2")
    assertEquals(
      listOf("content://sub/2"),
      SubtitleTombstones.filterExternal(uris, tombstones, { null }, { it }),
    )
    val online = listOf("origin-a", "origin-b").map {
      SubtitleTombstones.onlineKey("7", "item", it, null, null)!!
    }
    assertEquals(
      listOf("origin-b"),
      SubtitleTombstones.filterOnline(online, tombstones) { it }.map { it.originId },
    )
    val emby = listOf(2, 3).map {
      SubtitleTombstones.embyKey("1", "item", "ms", it)!!
    }
    assertEquals(
      listOf(3),
      SubtitleTombstones.filterEmby(emby, tombstones) { it }.map { it.streamIndex },
    )
  }

  @Test
  fun `序列化往返保留特殊字符且坏行不会毁掉整份账本`() {
    val original = listOf(
      SubtitleTombstones.external(
        "/subtitles/a\tb.srt",
        "content://sub/1?x=1&y=2",
        createdAt = 100L,
      )!!,
      SubtitleTombstones.online(
        "server\t7",
        "item\n8",
        "origin:9",
        "https://example.test/a.srt?x=1&y=2",
        "/data/files/a b.srt",
        createdAt = 200L,
      )!!,
      SubtitleTombstones.emby("1", "item", "ms\t1", 3, createdAt = 300L)!!,
    )
    val encoded = SubtitleTombstones.serialize(original)
    val withBadLines = "bad line\n\t1\tE\tnot-a-time\tx\ty\n" + encoded + "\t1\tB\t1\titem\t\t-2\n"
    assertEquals(original, SubtitleTombstones.parse(withBadLines))
  }
}
