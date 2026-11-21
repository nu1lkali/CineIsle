package app.marlboroadvance.mpvex.domain.emby

// ════════════════════════════════════════════════════════════════════════
// EmbyItemFields —— /Items 系列接口的 Fields 取值（从 EmbyClient 原样搬出）
// ════════════════════════════════════════════════════════════════════════
//
// 单独成文件而不是塞进 EmbyHttpCore：这些是**接口契约**（要哪些字段），
// 与「怎么发请求」无关；后续按域拆 Items / Facets / Collections 时都要引用，
// 放在传输层会让「业务常量依赖 HTTP 层」的方向颠倒。

object EmbyItemFields {
  /** 请求 Emby 时统一附加的扩展字段 */
  const val CORE =
    "BasicSyncInfo,MediaSourceCount,Overview,Genres,People,Studios,Taglines,MediaSources," +
      // Size 是「按文件大小排序」的数据来源，Emby 默认不返回，必须显式索取
      "Tags,SortName,ProductionLocations,Path,Size"

  /**
   * [CORE] + **本地排序要用的那几个键**：年份 / 评分 / 添加时间 / 首播日期。
   *
   * ⚠️ 这几个字段 Emby **默认不回传**（不带 Fields 的 `/Items` 只回
   * Name / Id / Type / UserData / ImageTags，其余全是 null），而 [CORE] 里也没有它们。
   * 于是「按年份 / 评分 / 最新添加排序」时每条的键都是 null，稳定排序退化为**原顺序** ——
   * 表现就是「排序只有名称（SortName 在 CORE 里）和随机有效，其余点了没反应」。
   *
   * 凡是**要在客户端本地排序**的列表（收藏页、演员 / 导演作品页）都必须用这一份。
   */
  const val WITH_SORT_KEYS = "$CORE,ProductionYear,CommunityRating,DateCreated,PremiereDate"

  /**
   * 拉照片（Photo）列表时索要的扩展字段。
   *
   * 只用 OpenAPI 文档列出的合法 Fields 名（Overview/Genres/Taglines/SortName/
   * ProductionLocations/Path）—— 服务端对不认识的字段名有可能直接 400，
   * 不放没把握的值进去。
   *
   * ⚠️ **宽高 / MIME / EXIF（CameraMake 等）不在这个串里，也不该加**：
   * OpenAPI 的 Fields 可选值清单里根本没有它们 —— 这些字段由服务端在库扫描时
   * 从图片 EXIF 写进条目实体，对 Photo 条目**自动返回**，与 Fields 无关。
   * 见 [app.marlboroadvance.mpvex.domain.emby.EmbyModels] 里 EmbyItem 的照片字段段。
   */
  const val PHOTO = "Overview,Genres,Taglines,SortName,ProductionLocations,Path"

  /**
   * 取「整份条目原文」时索要的字段（演职员合并回写用）。
   *
   * 要点是**必须包含 `People`**：不带的话返回体里没有 People，
   * [app.marlboroadvance.mpvex.domain.emby.rewriteItemPeople] 会认为这条不用改从而静默跳过。
   * 其余是编辑元数据时用户可能已经改过的字段，一并要回来，
   * 免得整包回写时把它们从「有值」变成「缺字段」。
   */
  const val RAW =
    "Overview,OriginalTitle,SortName,Genres,Tags,Studios,ProductionLocations,People," +
      "OfficialRating,CommunityRating,PremiereDate,ProductionYear,Path,ProviderIds,Taglines"

  /**
   * 祖先链请求要的字段。
   *
   * 祖先链只用得上「这一层叫什么、是什么类型、父级是谁」，所以清单刻意很短 ——
   * 祖先可能有十几层，多要 Fields 只是白拉数据。
   *
   * ⚠️ 不要删 `SortName` / `OriginalTitle`：名称类字段在不同服务端版本上
   * 填充情况不同，少要一项就可能在某一层退回空名字。
   */
  internal val ANCESTOR_FIELDS: String = listOf(
    "SortName",
    "OriginalTitle",
    "ParentId",
    "Path",
  ).joinToString(",")

  /**
   * 详情页要的字段清单（基础版）。
   *
   * 抽成常量而不是写在 [EmbyItemsApi.getItem] 里：它有两个使用者 —— 正常路径和
   * 「ExternalUrls 不被接受时的降级重试」，两处必须逐字一致。
   */
  internal val ITEM_FIELDS_CORE: String = listOf(
    "Overview",
    "OriginalTitle",
    "SortName",
    "Genres",
    "Tags",
    "Studios",
    "ProductionLocations",
    "People",
    "OfficialRating",
    "CommunityRating",
    "PremiereDate",
    "ProductionYear",
    "MediaSources",
    "MediaStreams",
    // 详情页「宣传语」一行 + 「外部链接」一行的数据来源，官方 ItemFields 里确实有这两个值
    "Taglines",
    "ProviderIds",
  ).joinToString(",")

  /**
   * [ITEM_FIELDS_CORE] + `ExternalUrls`（详情页「外部链接」一行的**首选**数据源）。
   *
   * 为什么要单列一个、还要在失败时重试：`ExternalUrls` 在 Emby SDK 的 `ItemFields`
   * 枚举里是有的（`BaseItemDto.ExternalUrls` 是标准字段），但它**没被写进官方 OpenAPI
   * 那段 `Fields` 可选值描述**（那段描述本身也不全，例如 `Size` / `Chapters` 都不在里面）。
   * 服务端对不认识的字段名**可能直接 400**，而详情页一旦 400 就整页打不开 ——
   * 这个代价（整页挂掉）远大于收益（少一个降级路径）。
   * 所以：带上它、失败就退回 [ITEM_FIELDS_CORE] 重来一次。
   *
   * 拿到 `ExternalUrls` 的价值：它的 `Url` 是**服务端/元数据插件拼好的完整地址**
   * （如 `http://<host>:<port>/?redirect=JavBus:ACZD-218`），与 Emby Web / Yamby
   * 打开的是同一条链接；拿 `ProviderIds` 自己拼容易拼错（见 `EmbyDetailScreen`）。
   */
  internal val ITEM_FIELDS_WITH_EXTERNAL_URLS: String =
    // SiblingIds：单集详情页要拿「同季其它集」。它是标准 BaseItemDto 字段，
    // 但同样不一定被老服务端认 —— 所以放在这个「失败就降级」的清单里，
    // 而不是塞进 [ITEM_FIELDS_CORE]（那会把降级路径一起弄坏）。
    //
    // ParentId：详情页的「打开所在文件夹」（`EmbyDetailScreen` → `MediaInfoSection`）
    // 要拿这一条**直接父目录**的 Id 才能压栈库页下钻。`BaseItemDto.ParentId` 是标准字段，
    // 但同样属于「不索取就不返回」的那一类 —— 不写进 Fields 时 `EmbyItem.ParentId` 恒为 null。
    // 归到这条降级清单而不是 `ITEM_FIELDS_CORE`：代价一样，收益是拿不到时按钮不出现、
    // 整页照样能开。
    "$ITEM_FIELDS_CORE,ExternalUrls,SiblingIds,ParentId"
}
