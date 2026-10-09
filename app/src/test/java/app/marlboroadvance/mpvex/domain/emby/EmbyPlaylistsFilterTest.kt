package app.marlboroadvance.mpvex.domain.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmbyPlaylists.filterPlaylists] 的单测。
 *
 * ## 为什么这个测试值得存在
 *
 * 用户真机报的 500 是：
 * ```
 * Unable to cast object of type 'MediaBrowser.Controller.Entities.CollectionFolder'
 * to type 'MediaBrowser.Controller.Playlists.Playlist'
 * ```
 * 翻译成人话：客户端把**一个媒体库的 Id** 当成了播放列表 Id 发给了
 * `/Playlists/{id}/Items`。而客户端之所以会拿到媒体库的 Id，是因为
 * 服务端**忽略了** `IncludeItemTypes=Playlist`，`/Users/{id}/Items`
 * 原样返回了该用户根目录下的直接子项 —— 那正好就是一堆媒体库。
 *
 * 这个 bug 的本质是「**没做客户端过滤**」，而「没做」这种事没有任何编译期或运行期
 * 报错，只能靠测试钉死。这组用例就是那根钉子：谁再把过滤去掉，这里立刻红。
 *
 * 跑法：`:app:testStandardDebugUnitTest`
 */
class EmbyPlaylistsFilterTest {

  private fun item(id: String, type: String?) = EmbyItem(Id = id, Name = "条目 $id", Type = type)

  @Test
  fun `服务端忽略了 IncludeItemTypes 时媒体库不会漏进播放列表`() {
    // 复现真机场景：/Users/{id}/Items 原样返回了媒体库 + 真播放列表混在一起
    val raw = listOf(
      item("lib-movies", "CollectionFolder"),
      item("lib-series", "CollectionFolder"),
      item("pl-1", "Playlist"),
      item("pl-2", "Playlist"),
    )

    val kept = EmbyPlaylists.filterPlaylists(raw)

    assertEquals(listOf("pl-1", "pl-2"), kept.mapNotNull { it.Id })
  }

  @Test
  fun `只有 CollectionFolder 时结果为空而不是把库 Id 交出去`() {
    // 这是 500 最直接的触发条件：一条真播放列表都没有，
    // 列表页就该显示「还没有播放列表」，而不是点进去报强转失败
    val raw = listOf(
      item("lib-movies", "CollectionFolder"),
      item("lib-tv", "CollectionFolder"),
    )

    assertTrue(EmbyPlaylists.filterPlaylists(raw).isEmpty())
  }

  @Test
  fun `Type 为 null 的条目也丢掉`() {
    // BaseItemDto.Type 是必填字段。拿不到类型的条目说明数据不正常，
    // 放进来只会重演 500 —— 所以这里选择「宁可列表空」
    val raw = listOf(
      item("unknown", null),
      item("pl-ok", "Playlist"),
    )

    val kept = EmbyPlaylists.filterPlaylists(raw)

    assertEquals(1, kept.size)
    assertEquals("pl-ok", kept.first().Id)
  }

  @Test
  fun `其他非播放列表类型一律不通过`() {
    // 合集（BoxSet）是另一种「非媒体 Item」，但它的条目是剧集/电影，
    // 语义上不是播放列表，也不该出现在这个列表页
    val raw = listOf(
      item("bs-1", "BoxSet"),
      item("pl-1", "Playlist"),
    )

    val kept = EmbyPlaylists.filterPlaylists(raw)

    assertEquals(listOf("pl-1"), kept.mapNotNull { it.Id })
  }

  @Test
  fun `过滤保持原有顺序`() {
    // 服务端已按 SortName 排好序。过滤不能打乱它 ——
    // 列表页是直接按返回顺序渲染的，顺序乱了用户会以为刷新坏了
    val raw = listOf(
      item("pl-c", "Playlist"),
      item("lib-x", "CollectionFolder"),
      item("pl-a", "Playlist"),
      item("pl-b", "Playlist"),
    )

    val kept = EmbyPlaylists.filterPlaylists(raw)

    assertEquals(listOf("pl-c", "pl-a", "pl-b"), kept.mapNotNull { it.Id })
  }

  @Test
  fun `空输入返回空输出`() {
    assertTrue(EmbyPlaylists.filterPlaylists(emptyList()).isEmpty())
  }
}
