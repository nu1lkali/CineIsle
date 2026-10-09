package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem

/**
 * 全库搜索结果的分类页签（清单 4.1）。
 *
 * ## 为什么是「页签」而不是「筛选 chip」
 * 项目里已经有一组 [app.marlboroadvance.mpvex.ui.browser.emby.components.EmbySearchFilter]
 * 筛选 chip 了，两者语义不同、会并存：
 * - **筛选 chip** 决定「**问服务端要什么**」：勾了「电影」就只查电影，改一次要重新发一次请求。
 * - **页签（本文件）** 决定「**已拿到的结果里先看哪一块**」：纯客户端切片，切一下不发请求。
 *
 * 所以它们的联动是：chip 收窄 → 结果集变小 → 页签里对应类型的角标跟着变小；
 * 页签永远不会让 chip 变成未勾选（反过来 chip 也不会自动切页签）。
 *
 * ## 为什么页签不做独立分页
 * 全库搜索是「人物走 `/Persons` + 媒体走 `/Items`」两条流拼的（见 [EmbyViewModel.searchGlobal]），
 * 两者的 offset 空间互不相通。要让每个页签各自翻页，得维护两套游标再按页签归并，
 * 复杂度远大于收益。当前一次取回 [EmbyViewModel.GLOBAL_SEARCH_LIMIT] 条，
 * 页签在这份快照上切片 —— 切页签零延迟、零请求，代价是「单页最多 120 条」这个既有上限。
 * 上限本身有 UI 提示（`EmbyGlobalSearchResult.truncated`），不会静默截断。
 */
enum class EmbySearchTab(
  /** 页签上的短标签（中文，跟 EmbySearchFilter 的用词保持一致） */
  val label: String,
  /**
   * 归到本页签的 Emby `Type` 值；`null` = 不按类型分（[ALL] 与 [OTHER]）。
   *
   * [OTHER] 用 `null` 而不是某个具体类型，是**故意的**：Emby 的 Item 类型远不止这几种
   * （MusicVideo / Video / Recording / Program …），把它们各自单开一个页签没人会点，
   * 但又不能悄悄丢掉 —— 所以统一收进「其他」，保证「全部」里看到的每一条都能在某个页签里找到。
   */
  val type: String?,
) {
  /** 全部结果（不分类，原样展示） */
  ALL("全部", null),

  /** 电影 */
  MOVIE("电影", "Movie"),

  /** 剧集（Series 容器，不是单集） */
  SERIES("剧集", "Series"),

  /** 单集 */
  EPISODE("单集", "Episode"),

  /** 合集 */
  BOXSET("合集", "BoxSet"),

  /** 演员 / 导演等人物（来自 `/Persons`，不是 `/Items`） */
  PERSON("演员", "Person"),

  /** 其余类型（`Type` 为空或不在上面列表里的） */
  OTHER("其他", null),
  ;

  companion object {
    /**
     * 有具体类型值的页签 —— UI 画页签条时按这个顺序展示（不含 ALL / OTHER）。
     *
     * 显式列举而不是 `entries.filter { it.type != null }`：顺序是产品决策
     * （最常找的「电影」排最前），不能被枚举声明顺序悄悄带偏。
     */
    val TYPED: List<EmbySearchTab> = listOf(MOVIE, SERIES, EPISODE, BOXSET, PERSON)

    /**
     * 判断一条结果属于哪个页签。
     *
     * **大小写不敏感**：Emby 的 `Type` 在不同版本 / 不同插件下大小写不一致
     * （见过 `Movie` 也见过 `movie`），用 `equals(ignoreCase = true)` 而不是 `==`。
     *
     * `Type` 为 null / 空白 ⇒ [OTHER]（不是 [ALL]，[ALL] 只在 UI 上代表「不分类」）。
     */
    fun classify(item: EmbyItem): EmbySearchTab {
      val type = item.Type?.trim().orEmpty()
      if (type.isEmpty()) return OTHER
      return TYPED.firstOrNull { it.type.equals(type, ignoreCase = true) } ?: OTHER
    }

    /**
     * 统计每个页签各有多少条，供页签角标用。
     *
     * @return 页签 → 条数。**不包含** [ALL]（它的角标就是总数，由 UI 自己算），
     *   也不包含 [OTHER] —— 「其他」通常没有单独查看的诉求，
     *   但它的条目仍然会出现在 [ALL] 里（不会丢）。
     *
     * ⚠️ **返回的 map 顺序不保证等于 [TYPED] 顺序**（是「结果里遇到的先后」）。
     * UI 要按固定顺序排列页签就别依赖这里的顺序，自己 `TYPED.forEach` 一遍。
     */
    fun countByTab(items: List<EmbyItem>): Map<EmbySearchTab, Int> {
      val counts = LinkedHashMap<EmbySearchTab, Int>(TYPED.size)
      for (item in items) {
        val tab = classify(item)
        if (tab == ALL || tab == OTHER) continue
        counts[tab] = (counts[tab] ?: 0) + 1
      }
      return counts
    }

    /**
     * 按页签切出要展示的列表。
     *
     * [ALL] 原样返回入参（**同一个 List 实例**，不复制）—— UI 侧会把它整个传进
     * `LazyVerticalGrid`，少一次拷贝就是少一次 120 条量级的数组分配。
     * 其余页签用 `filter` 生成新列表。
     */
    fun itemsFor(items: List<EmbyItem>, tab: EmbySearchTab): List<EmbyItem> =
      if (tab == ALL) items else items.filter { classify(it) == tab }

    /**
     * 页签上是否该显示角标。
     *
     * 只有 `count > 0` 才显示：显示一个「0」的角标会让用户以为「这个分类搜了但没有」，
     * 而实际上点进去也是空的，不如一开始就不给这个页签任何存在感的暗示。
     *
     * @param tab 当前被判定的页签。**不参与判断**（选中与否已经体现在 [selected] 里），
     *   保留它只是为了让调用点写成 `shouldShowBadge(tab, counts[tab] ?: 0, tab == selected)`
     *   这样的自我解释形式 —— 少一个参数时很容易把两个实参的顺序传反，而传反了不会报错、
     *   只会让某个页签莫名其妙地不出现。
     * @param selected 当前选中的页签 —— **始终返回 true**，即使它是 0 条。
     *   否则用户能点到的页签会突然消失，点一下就把选中态弄丢了。
     */
    fun shouldShowBadge(tab: EmbySearchTab, count: Int, selected: Boolean): Boolean =
      selected || count > 0
  }
}
