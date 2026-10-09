package app.marlboroadvance.mpvex.ui.player.gsy

import app.marlboroadvance.mpvex.domain.emby.EmbyChapter
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer

/**
 * 一个可跳转的章节。
 *
 * 单独一个类型而不是直接用 `EmbyChapter`，是因为后者带的是 **100 纳秒 ticks**
 * （`.NET TimeSpan`），而播放页要的是**毫秒** —— 中间那个 `/10000` 到处重复写
 * 迟早有人漏掉，漏掉的表现是「跳过去还是 0 秒」，很难查。
 */
data class GsyChapter(
  /** 起点（毫秒） */
  val startMs: Long,
  /** 展示用标题；服务端可能没给（见 [EmbyChapter.Name]） */
  val title: String,
  /** 在列表里的下标，用于回查当前章（[indexOfCurrent]） */
  val index: Int,
)

/**
 * GSY 内核的章节数据源（清单 #19 的另一半）。
 *
 * ## 为什么 GSY 需要这么一整个类，而 mpv 不需要
 *
 * mpv 的章节是 `PlayerLib.propNode["chapter-list"]` —— 一个 StateFlow，
 * mpv 自己在 `loadfile` 时从容器解析好，直接读、直接跳（`setPropertyInt("chapter", i)`）。
 *
 * GSY 三样都没有：**没有章节 property、没有当前章下标、没有 setPropertyInt**。
 * 所以从「数据怎么来」到「怎么跳」整条链都得自己搭，就落在本类里。
 *
 * ## 三条设计取舍
 *
 * 1. **章节按需加载 + 一次性缓存**。开播就把所有条目的章节都拉一遍是浪费 ——
 *    一部剧 50 集，用户只看了第 3 集。所以 [chaptersOf] 在**第一次点开章节面板**时才拉，
 *    拉完缓存在 [cache] 里，切集时只在 [invalidate] 里清掉「新那一集」的部分。
 *    同一集再开面板就是零网络。
 *
 * 2. **拉不到就是没有，不报错**。纯本地文件没有 Emby 条目、服务端没刮削章节、
 *    网络断了 —— 这些都表现为「没有章节」，由 [chaptersOf] 返回空列表。
 *    播放页不该为一个锦上添花的面板弹错误。这条能力边界写在 [EngineFeature.CHAPTERS] 的 KDoc 里。
 *
 * 3. **不做预取、不做后台预热**。真实使用里章节面板是低频入口（看剧时按章跳过的人不多），
 *    为低频入口加预取只会增加启动时的并发请求。宁可第一次开面板等一下。
 */
class GsyChapterRepository(
  private val repository: EmbyRepository,
) {
  /**
   * itemId → 该条目的章节。
   *
   * 用 `HashMap` 而不是 LruCache：一次播放会话里条目数量有界（就是播放队列那么长），
   * 不会无上限增长，所以不需要淘汰策略。
   */
  private val cache = HashMap<String, List<GsyChapter>>()

  /** 正在拉取的 itemId，避免并发重复请求（连点两次面板不该发两次网络） */
  private val inFlight = HashSet<String>()

  /**
   * 取某个条目的章节；没缓存过就去拉。
   *
   * @param server 当前 Emby 服务器；非 Emby 播放传 null
   * @param itemId 当前播放的 Emby ItemId；本地文件传 null
   * @return 章节列表（可能为空 —— **空是正常情况**，不是失败）
   */
  suspend fun chaptersOf(
    server: EmbyServer?,
    itemId: String?,
  ): List<GsyChapter> {
    if (server == null || itemId.isNullOrBlank()) return emptyList()

    cache[itemId]?.let { return it }
    // 连点两次面板时，第二次直接等第一次的结果，不要再发一次请求
    if (!inFlight.add(itemId)) return cache[itemId] ?: emptyList()

    return try {
      val fetched =
        repository.getItemChapters(server, itemId)
          .sortedBy { it.StartPositionTicks }
          .mapIndexed { i, chapter -> chapter.toGsyChapter(i) }
      cache[itemId] = fetched
      fetched
    } finally {
      // 失败也要清 inFlight，否则这一集再也拉不到了（会被一直当成「正在加载」）
      inFlight.remove(itemId)
    }
  }

  /**
   * 只读缓存，不触发网络。
   *
   * 播放中要显示「当前章节」标签时用这个 —— 那个位置每秒都在刷新，
   * 绝不能让它去发网络请求。没缓存就返回 null，标签不显示，仅此而已。
   */
  fun cachedOf(itemId: String?): List<GsyChapter>? {
    if (itemId.isNullOrBlank()) return null
    return cache[itemId]
  }

  /**
   * 切集时调用：清掉**非当前条目**的缓存。
   *
   * 为什么不全清：同一集内反复开关面板、或退出全屏再进来都还要用到，
   * 清了就得重新拉。保留当前这一条、扔掉别的，是内存和请求量之间最省事的平衡点。
   *
   * @param keepItemId 新播的那一条（null 表示纯本地播放，那就没什么可留的）
   */
  fun invalidate(keepItemId: String? = null) {
    if (keepItemId == null) {
      cache.clear()
      return
    }
    val stale = cache.keys.filter { it != keepItemId }
    stale.forEach { cache.remove(it) }
  }

  /** 播放队列整体换掉时（从另一个页面发起新播放）清空全部缓存 */
  fun clear() {
    cache.clear()
    inFlight.clear()
  }

  companion object {
    /**
     * 服务端章节 → 可展示的章节。
     *
     * 标题兜底链：`Name` → 「第 N 章」（中文习惯）→ 空串。
     * 不少刮削器只写时间点不写标题，直接显示空行会让用户以为点坏了。
     */
    fun EmbyChapter.toGsyChapter(index: Int): GsyChapter =
      GsyChapter(
        startMs = startPositionMs,
        title = Name?.takeIf { it.isNotBlank() } ?: "第 ${index + 1} 章",
        index = index,
      )

    /**
     * 当前位置落在哪一章。
     *
     * 二分查找「最后一个 startMs ≤ position 的那一章」——
     * 章节表是按时间升序的（[chaptersOf] 拉回来就 `sortedBy` 过），
     * 所以能直接二分。找不到（播放头在第一章之前）返回 null，标签就不显示。
     */
    fun List<GsyChapter>.indexOfCurrent(positionMs: Long): Int? {
      if (isEmpty()) return null
      var low = 0
      var high = size - 1
      var found = -1
      while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].startMs <= positionMs) {
          found = mid
          low = mid + 1
        } else {
          high = mid - 1
        }
      }
      return if (found < 0) null else found
    }

    /** 当前位置所属章节（拿不到就 null） */
    fun List<GsyChapter>?.currentAt(positionMs: Long): GsyChapter? {
      if (this == null) return null
      val i = indexOfCurrent(positionMs) ?: return null
      return getOrNull(i)
    }
  }
}
