package app.marlboroadvance.mpvex.domain.emby

import okhttp3.RequestBody.Companion.toRequestBody

// ════════════════════════════════════════════════════════════════════════
// 合集（Collection / 官方客户端叫 BoxSet）—— 媒体库结构管理
// 从 EmbyClient 原样搬出（重构：god-class 拆分），行为零变化
// ════════════════════════════════════════════════════════════════════════
//
// ⚠️ **本节最容易踩的坑：REST 路径是 `/Collections`，不是 `/BoxSets`**
// `/BoxSets` 是 **Jellyfin** 的命名。Emby 官方 Java 客户端里 BoxSet 走的是
// `CollectionServiceApi` → `/Collections`。照着 Jellyfin 的文档 / 网上教程写必然 404。
// 另一个要记的：**参数名是 `Ids` 不是 `EntryIds`**。
// 口诀 —— BoxSet(Collection) 一律 `Ids`；只有 Playlist 增删条目才用 `EntryIds`。
// 写错参数名服务端不报错，只是**静默不生效**（合集内容不变），极难排查。

object EmbyCollectionsApi {
  /**
   * 列出全部合集。
   *
   * 和播放列表一样，**没有** `GET /Collections` 这种「按类型拿」的专用端点 ——
   * 合集本身就是一种 Item，只能走 `/Users/{id}/Items` + `IncludeItemTypes=BoxSet`。
   *
   * ⚠️ 返回里的 `ChildCount` 是服务端算的合集条目数，可能与实际差一
   * （有条目被删但合集没刷新）。UI 显示它没问题，**拿它做判断条件不行**。
   */
  internal fun getCollections(
    server: EmbyServer,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = EmbyHttpCore.getJson(
    server,
    "/Users/${server.userId}/Items",
    mapOf(
      "StartIndex" to startIndex.toString(),
      "Limit" to limit.toString(),
      "IncludeItemTypes" to "BoxSet",
      "Fields" to "Overview,DateCreated,ChildCount,Genres,ProductionYear",
      "SortBy" to "SortName",
      "SortOrder" to "Ascending",
      "EnableImages" to "true",
      "ImageTypeLimit" to "1",
      "EnableUserData" to "true",
    ),
  )

  /**
   * 取合集里的条目（分页）。
   *
   * 对应 `GET /Collections/{Id}/Items`。
   *
   * 不用通用 `getItems(parentId = ...)` 的理由和播放列表一样：
   * `ParentId` 传播合集 Id 时部分服务端会算成空，老实用专用端点。
   */
  internal fun getCollectionItems(
    server: EmbyServer,
    collectionId: String,
    startIndex: Int = 0,
    limit: Int = 100,
  ): EmbyItemsResult = EmbyHttpCore.getJson(
    server,
    "/Collections/$collectionId/Items",
    mapOf(
      "UserId" to server.userId,
      "StartIndex" to startIndex.toString(),
      "Limit" to limit.toString(),
      "Fields" to EmbyItemFields.CORE,
      "EnableImages" to "true",
      "ImageTypeLimit" to "1",
      "EnableUserData" to "true",
      "EnableTotalRecordCount" to "true",
    ),
  )

  /**
   * 新建合集。`POST /Collections`。
   *
   * ⚠️ **参数是 query 不是 body**（参考客户端里 `localVarPostBody = null`）。
   * 传成 body 的话服务端会当没收到，建出一个**空的没有名字的合集**。
   *
   * @param name 合集名
   * @param itemIds 初始条目（可空 = 建空合集）
   * @param parentId 挂在哪个媒体库下；null 表示用户根
   */
  internal fun createCollection(
    server: EmbyServer,
    name: String,
    itemIds: List<String> = emptyList(),
    parentId: String? = null,
  ): EmbyCollectionCreationResult = EmbyHttpCore.postJson<EmbyCollectionCreationResult>(
    server = server,
    path = "/Collections",
    bodyJson = "{}",
    query = buildMap {
      put("Name", name)
      put("Ids", itemIds.joinToString(",").ifEmpty { null })
      put("ParentId", parentId)
      put("UserId", server.userId)
    },
  )

  /**
   * 往合集追加条目：`POST /Collections/{Id}/Items`。
   *
   * ⚠️ `Ids` 是 **query**（同 [createCollection]）。
   * 返回体是空的，所以调用方拿不到「实际加了几条」——
   * 重复添加是幂等的，UI 只能自己判断要不要刷新。
   */
  internal fun addToCollection(
    server: EmbyServer,
    collectionId: String,
    itemIds: List<String>,
  ): Boolean {
    if (itemIds.isEmpty()) return true
    val req = EmbyHttpCore.authedRequest(
      server,
      "/Collections/$collectionId/Items",
      mapOf("Ids" to itemIds.joinToString(","), "UserId" to server.userId),
    ).post("{}".toRequestBody(EmbyHttpCore.jsonMedia)).build()
    EmbyHttpCore.httpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        throw EmbyApiException(resp.code, resp.body.string().ifEmpty { resp.message })
      }
    }
    return true
  }

  /**
   * 从合集移除条目：`DELETE /Collections/{Id}/Items`。
   *
   * ⚠️ **参数是 `Ids` 且指的是「媒体 Id」，不是合集内的条目 Id**
   * （和 `POST /Playlists/{Id}/Items/Move` 那个 `ItemId` 正好相反，
   * 那边要的是播放列表内的条目 Id —— 两者极易搞混）。
   *
   * 这是真 DELETE（不是 POST 一个 `Ids` 标记），响应体为空。
   */
  internal fun removeFromCollection(
    server: EmbyServer,
    collectionId: String,
    itemIds: List<String>,
  ): Boolean {
    if (itemIds.isEmpty()) return true
    val req = EmbyHttpCore.authedRequest(
      server,
      "/Collections/$collectionId/Items",
      mapOf("Ids" to itemIds.joinToString(","))
        .plus("UserId" to server.userId),
    ).delete().build()
    EmbyHttpCore.httpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        throw EmbyApiException(resp.code, resp.body.string().ifEmpty { resp.message })
      }
    }
    return true
  }
}
