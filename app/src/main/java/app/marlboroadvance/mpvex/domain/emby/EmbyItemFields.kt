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
}
