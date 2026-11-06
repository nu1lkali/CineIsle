package app.marlboroadvance.mpvex.domain.emby

/**
 * 本地「最近播放」账本。
 *
 * ## 为什么需要它（服务器实测）
 *
 * 先纠正一个常见误判：`/Users/{id}/Items/Resume` **确实按最后观看时间降序**排 ——
 * 实测首页前 8 条的 `LastPlayedDate` 依次是 11:54 / 11:53 / 11:45 / 11:42 / 09:57 /
 * 08:40 / 前一天 10:27 / 10:26（UTC），严格递减；`SortBy=DatePlayed` 降序的结果与不传
 * 完全一致（因为默认就是它），传升序则是它的逆序。
 *
 * ⚠️ 但 `UserData.LastPlayedDate` **只在单条 `GET /Items/{id}` 上返回**，
 * `Items/Resume` 与 `Items?Ids=` 这两个批量接口都不带这个字段 —— 光看批量响应会误以为
 * 「服务端没有播放时间」。
 *
 * 真正出问题的是**写入端**：条目只有在该次播放把 `LastPlayedDate` 刷新了，才会被顶到前面。
 * 我们的上报一直只更新了播放位置（所以详情页有进度），时间戳没跟着走 ⇒ 条目留在原来的位次上。
 * 实测就有一条卡在第 21 位（`LastPlayedDate` 是两周前），而首页只要 20 条，差一位就被切掉。
 * 这也解释了为什么「别的播放器（Yamby）放过的片会到最前、我们放过的不会」：它用
 * `PlaybackInfo` 拿服务端签发的会话再上报，我们用的是自生成的 UUID（v226）。
 *
 * ## 这份账本的作用
 *
 * 治本要靠写端（改用 `PlaybackInfo` 的会话），但服务端那边的行为不是我们能保证的
 * （不同版本对「什么时候记时间戳」的判定并不一致）。所以再加一道客户端兜底：
 * 起播时在本地记一笔，首页取数时把**最近播放、却不在服务端那批结果里**的条目用一次
 * `Ids=` 批量请求补回来，并按本地时间排到最前（见 `EmbyViewModel.mergeRecentPlays`）。
 * 这样即使服务端的时间戳没刷新，刚播的那条也一定在第一屏。
 *
 * ## 存储形态
 *
 * `id:时间戳,id:时间戳,...`（时间戳 = `System.currentTimeMillis()`）。
 * Emby 的条目 Id 是 32 位十六进制、不含 `,` 与 `:`，所以直接切分是安全的，
 * 不需要 JSON —— 与 `BrowserPreferences` 里那几个「逗号分隔小串」保持同一路数。
 */
object EmbyRecentPlays {
  /** 账本保留条数。够覆盖「这几天看过的」，又不至于无限长 */
  const val MAX_ENTRIES = 40

  private const val ITEM_SEP = ','
  private const val TS_SEP = ':'

  /** 解析成 (条目 Id, 记录时间) 列表；坏段静默丢弃，不抛异常 */
  fun parse(raw: String): List<Pair<String, Long>> {
    if (raw.isBlank()) return emptyList()
    return raw.split(ITEM_SEP).mapNotNull { seg ->
      val cut = seg.indexOf(TS_SEP)
      if (cut <= 0) return@mapNotNull null
      val id = seg.substring(0, cut)
      // 时间戳解析失败就当成 0：条目本身还有用（总比丢掉强），只是排最后
      val ts = seg.substring(cut + 1).toLongOrNull() ?: 0L
      if (id.isBlank()) null else id to ts
    }
  }

  fun serialize(entries: List<Pair<String, Long>>): String =
    entries.joinToString(ITEM_SEP.toString()) { "${it.first}$TS_SEP${it.second}" }

  /**
   * 记一笔：同 Id 去重（挪到最前）、按时间倒序、超过 [MAX_ENTRIES] 截断。
   *
   * 重复播放同一条不会让账本变长，只是把它的时间戳刷新 —— 否则连看三集就把
   * 前两集挤出账本了。
   */
  fun record(raw: String, itemId: String, now: Long): String {
    if (itemId.isBlank()) return raw
    val kept = parse(raw).filterNot { it.first == itemId }
    return serialize((listOf(itemId to now) + kept).take(MAX_ENTRIES))
  }

  /** 最近播放过的 Id，最近的在前 */
  fun recentIds(raw: String, limit: Int): List<String> =
    parse(raw)
      .sortedByDescending { it.second }
      .map { it.first }
      .distinct()
      .take(limit)
}
