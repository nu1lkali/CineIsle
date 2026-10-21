package app.marlboroadvance.mpvex.domain.emby

import okhttp3.RequestBody.Companion.toRequestBody

// ════════════════════════════════════════════════════════════════════════
// Emby 服务端播放列表（清单 1.5 拆分第 2 刀，从 EmbyClient.kt 独立出来）
// 零行为变更：EmbyClient 里保留了同名的转发函数，全项目 62 处
// `EmbyClient.xxx` 调用与全部 UI 调用一行都不用改。
// 下面这段 KDoc 原本就写在 EmbyClient 里，一并搬过来了 —— 它解释的是
// 「为什么这些方法长这样」，跟着代码走才不会失联。
// ════════════════════════════════════════════════════════════════════════

/**
 * Emby 服务端播放列表的读写。
 *
 * 依赖 [EmbyClient] 的内部工具（`authedRequest` / `execString` / `execJson` /
 * `postJson` / `getJson` / `deleteJson`）与 `ITEM_FIELDS`。
 *
 * ⚠️ 它们虽然已经是 `internal`（同模块可见），但**在另一个 object 里仍必须写全限定名**
 * `EmbyClient.getJson(...)` —— `internal` 只放宽可见性，不会把成员导入到当前作用域。
 * 同包不等于同作用域，这是拆 `object` 单例时最容易忘的一处。
 */
object EmbyPlaylists {
  // ════════════════════════════════════════════════════════════════════════
  // 5.5 播放列表（Emby 服务端）
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ 与项目里**已有的本地播放列表**（Room 的 PlaylistEntity / PlaylistDao）完全是两回事：
  // 这里的 playlist 存在 Emby 服务器上，多端共享、网页端也能看。
  // 两者刻意不互相同步 —— 本地的是 M3U 导入 + 本地路径，Emby 的是 ItemId，两者无法可靠映射。

  /**
   * 列出当前用户可见的全部播放列表。
   *
   * ⚠️ **Emby 没有 `GET /Playlists` 这个端点**。播放列表本身就是一种 Item，
   * 所以只能走 `/Users/{userId}/Items` 并用 `IncludeItemTypes=Playlist` 把它筛出来。
   *
   * 顺带说明为什么不加 `BoxSet`（合集）：两者在 Emby 里都是「非媒体类型的 Item」，
   * 但 BoxSet 的条目是剧集/电影（用户预期是「合集」而非「播放列表」），
   * 混进来会让列表页的语义变浑。合集需求归到「媒体库结构管理」那批（1.5 虚拟库）去做。
   *
   * ## ⚠️ 客户端必须自己过滤（这条踩过，用户真机报的 500）
   *
   * 现象：播放列表列表页点任意一项进去，必回
   * `500 Unable to cast object of type 'MediaBrowser.Controller.Entities.CollectionFolder'
   * to type 'MediaBrowser.Controller.Playlists.Playlist'`。
   *
   * 拆开看这句话就明白了一切：服务端把传进去的 id 对应的实体**强转成 Playlist**，
   * 而那个实体其实是**媒体库文件夹（CollectionFolder）**。
   * 也就是说客户端把「一个媒体库 Id」当成了「播放列表 Id」。
   *
   * 根因是 `IncludeItemTypes=Playlist` **被服务端忽略了**。不是我们拼错了参数名，
   * 是部分第三方兼容实现压根不处理这个过滤条件，于是
   * `/Users/{id}/Items` 原样返回了该用户根目录下的**直接子项** ——
   * 那正好就是一堆媒体库（CollectionFolder）。
   *
   * 服务端的过滤是「建议」，客户端的过滤才是「保证」。所以这里无条件按 [EmbyItem.Type]
   * 过一遍，宁可列表空也不能把一个打不开的 Id 交给下一层。
   * 详见 [filterPlaylists]。
   */
  fun getPlaylists(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult {
    val params = mapOf(
      "StartIndex" to startIndex.toString(),
      "Limit" to limit.toString(),
      "IncludeItemTypes" to "Playlist",
      "Fields" to "Overview,DateCreated,MediaSourceCount,ChildCount",
      "SortBy" to "SortName",
      "SortOrder" to "Ascending",
      "EnableImages" to "true",
      "ImageTypeLimit" to "1",
      "EnableUserData" to "true",
      "EnableTotalRecordCount" to "true",
    )
    // ⚠️ `getJson<T>` 的 `T` 必须**显式写出来**：它是 inline reified 泛型，
    // 原来能推断是因为这个函数直接 `= EmbyClient.getJson(...)` 作为返回值，
    // 有期望类型兜着。现在多了一步 `val raw = ...`，赋值语句右侧没有期望类型，
    // 不写就报「Cannot infer type for type parameter 'T'」。
    val raw: EmbyItemsResult =
      EmbyClient.getJson(server, "/Users/${server.userId}/Items", params)

    val kept = filterPlaylists(raw.Items)
    if (kept.isNotEmpty()) {
      return EmbyItemsResult(Items = kept, TotalRecordCount = kept.size)
    }

    // 服务端把 IncludeItemTypes 当空气处理了（见上方 KDoc）。换一条**反向**的路：
    // 明确排除所有媒体类型，剩下「非媒体」的那批里再按 Type 挑。
    // ExcludeItemTypes 与 IncludeItemTypes 是同一族参数，但下来不少实现
    // 只对 Exclude 生效 —— 这一招是纯兜底，最坏结果是多一次请求、列表仍然是空的。
    if (raw.Items.isEmpty()) return raw

    val fallback: EmbyItemsResult =
      EmbyClient.getJson(
        server,
        "/Users/${server.userId}/Items",
        params + mapOf(
          "IncludeItemTypes" to "",
          "ExcludeItemTypes" to NON_MEDIA_EXCLUDE,
          "StartIndex" to "0",
        ),
      )
    val keptByExclude = filterPlaylists(fallback.Items)
    return EmbyItemsResult(
      Items = keptByExclude,
      TotalRecordCount = keptByExclude.size,
    )
  }

  /**
   * 只留下**真的是播放列表**的条目。
   *
   * 刻意只认 [EmbyItem.Type] 精确等于 `"Playlist"`：
   * - `Type == null` 也丢掉。Emby 的 `BaseItemDto.Type` 是必填字段，
   *   拿不到类型就说明这条数据不正常，让它混进列表只会重演 500。
   * - 不做「白名单之外全放行」—— 那等于没过滤。
   *
   * 抽成纯函数是为了能跑 JVM 单测（见 `EmbyPlaylistsFilterTest`）：
   * 这个 bug 的本质是「没过滤」，用测试钉死比下次再踩一遍便宜得多。
   */
  fun filterPlaylists(items: List<EmbyItem>): List<EmbyItem> =
    items.filter { it.Type == TYPE_PLAYLIST }

  /** Emby 里播放列表的 `Type` 取值。 */
  const val TYPE_PLAYLIST = "Playlist"

  /**
   * 兜底查询用的排除列表：把「有媒体实体的类型」全部排掉。
   *
   * 只在服务端忽略 [TYPE_PLAYLIST] 过滤时用一次，不影响正常路径。
   * 故意**不**排除 `CollectionFolder` / `BoxSet` / `Playlist` 本身 ——
   * 它们才是我们要找的那批。
   */
  private const val NON_MEDIA_EXCLUDE =
    "Movie,Series,Episode,Video,Audio,Book,Photo,MusicVideo,Recording," +
      "TvChannel,Program,VideoChannel,LiveTvChannel"

  /**
   * 取播放列表里的条目（分页）。
   *
   * 对应 `GET /Playlists/{Id}/Items`。
   *
   * 为什么不用 `getItems(parentId = ...)` 走通用查询：
   * `ParentId` 传播放列表 Id 时，部分服务端（含部分第三方兼容实现）会把结果算成空，
   * 所以这里老老实实用播放列表专用端点。
   */
  fun getPlaylistItems(
    server: EmbyServer,
    playlistId: String,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = EmbyClient.getJson(
    server,
    "/Playlists/$playlistId/Items",
    mapOf(
      "UserId" to server.userId,
      "StartIndex" to startIndex.toString(),
      "Limit" to limit.toString(),
      // `MediaItemId` 必须显式索取：它不在 [EmbyClient.ITEM_FIELDS] 里，
      // 而没有它就分不清「播放列表里的条目」和「媒体本身」——
      // 前者的 `Id` 拿去请求媒体详情会得到 500（见 EmbyItem.MediaItemId 的注释）。
      // 服务端不认这个字段名时按未知字段忽略，不会报错。
      "Fields" to EmbyClient.ITEM_FIELDS + ",MediaItemId",
      "EnableImages" to "true",
      "ImageTypeLimit" to "1",
      "EnableUserData" to "true",
      "EnableTotalRecordCount" to "true",
    ),
  )

  /**
   * 新建播放列表。
   *
   * 对应 `POST /Playlists`。
   *
   * ⚠️ **name / ids / mediaType 三个参数是 query 而不是 body**（Emby 的历史设计），
   * 写成 body 会得到一个空播放列表。这是很容易踩的坑。
   *
   * @param itemIds 首批加入的条目 Id；为空则建空列表
   * @return 新列表的 Id 与实际加入条数
   */
  fun createPlaylist(
    server: EmbyServer,
    name: String,
    itemIds: List<String> = emptyList(),
  ): EmbyPlaylistCreationResult {
    val req = EmbyClient.authedRequest(
      server,
      "/Playlists",
      mapOf(
        "name" to name,
        "ids" to itemIds.takeIf { it.isNotEmpty() }?.joinToString(","),
      ),
    ).post(ByteArray(0).toRequestBody(null)).build()
    return EmbyClient.execJson(req)
  }

  /**
   * 往播放列表里追加条目。对应 `POST /Playlists/{Id}/Items`。
   *
   * ⚠️ `ids` 同样是 **query 参数**，不是 body。
   */
  fun addToPlaylist(
    server: EmbyServer,
    playlistId: String,
    itemIds: List<String>,
  ): EmbyPlaylistAddResult {
    if (itemIds.isEmpty()) return EmbyPlaylistAddResult(playlistId, 0)
    val req = EmbyClient.authedRequest(
      server,
      "/Playlists/$playlistId/Items",
      mapOf("ids" to itemIds.joinToString(","), "userId" to server.userId),
    ).post(ByteArray(0).toRequestBody(null)).build()
    return EmbyClient.execJson(req)
  }

  /**
   * 从播放列表里移除条目。
   *
   * 用 `DELETE /Playlists/{Id}/Items?EntryIds=...`（新式端点）而不是
   * `POST /Playlists/{Id}/Items/Delete`（旧式）—— 后者已标记废弃。
   *
   * ⚠️ `EntryIds` 拼错成 `Ids` 的话服务端会当没传，返回 400 且信息含糊。
   */
  fun removeFromPlaylist(
    server: EmbyServer,
    playlistId: String,
    entryIds: List<String>,
  ) {
    if (entryIds.isEmpty()) return
    val req = EmbyClient.authedRequest(
      server,
      "/Playlists/$playlistId/Items",
      mapOf("EntryIds" to entryIds.joinToString(",")),
    ).delete().build()
    EmbyClient.execString(req)
  }

  /**
   * 调整播放列表内某个条目的位置。
   *
   * 对应 `POST /Playlists/{Id}/Items/{ItemId}/Move/{NewIndex}`。
   *
   * ⚠️ `ItemId` 在这里是**播放列表内的条目 Id**（Emby 返回的 PlaylistItem 条目自己的 Id），
   * 不是媒体 Id —— 传媒体 Id 会 404。位置从 0 开始。
   */
  fun movePlaylistItem(
    server: EmbyServer,
    playlistId: String,
    entryId: String,
    newIndex: Int,
  ) {
    val req = EmbyClient.authedRequest(server, "/Playlists/$playlistId/Items/$entryId/Move/$newIndex")
      .post(ByteArray(0).toRequestBody(null)).build()
    EmbyClient.execString(req)
  }

  /**
   * 删除播放列表**本身**。
   *
   * ⚠️ Emby 没有「删除播放列表」专用端点 —— 播放列表就是一条 Item，
   * 所以走 `DELETE /Items/{Id}`（等价于在网页端删播放列表）。
   *
   * 与本地播放列表的 `PlaylistDao.deleteById`（Room 级联删条目）语义一致：
   * 都是连同条目一起消失，且不可恢复。UI 上必须二次确认。
   */
  fun deletePlaylist(server: EmbyServer, playlistId: String) {
    val req = EmbyClient.authedRequest(server, "/Items/$playlistId").delete().build()
    EmbyClient.execString(req)
  }
}
