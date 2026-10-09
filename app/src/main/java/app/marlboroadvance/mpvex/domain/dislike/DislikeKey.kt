package app.marlboroadvance.mpvex.domain.dislike

/**
 * 「标记为不喜欢」的**去重主键**。
 *
 * ⚠️ 必须带 [serverId]，不能只用 itemId：
 * · 不同 Emby 服务器的 itemId 是**各自独立**的（都是 32 位十六进制），撞车不是理论风险；
 * · 用户完全可能同时登着两台服务器（自建 + 朋友的库），同一条片子两边都有。
 * 只用 itemId 去重会让「A 服务器的标记」莫名其妙地出现在「B 服务器的同号条目」上，
 * 而用户看到的两个条目**根本不是同一个东西**。
 *
 * 附带一提：本地文件播放（无服务器）也用这个键，[serverId] 固定为 [LOCAL]，
 * 这样「按路径去重」和「按 itemId 去重」共用同一套逻辑，不必写两条分支。
 */
data class DislikeKey(
  val serverId: Long,
  val itemId: String,
) {
  companion object {
    /** 本地播放（文件 / 网络流，非 Emby）的保留 serverId。取 -1，与真实服务器 id 不冲突。 */
    const val LOCAL = -1L
  }
}

/**
 * 一条「不喜欢」标记。
 *
 * @param reason 用户填的备注原因，**空串表示没填**（不是 null ——
 *   存储与判空都用空串，避免"没填"有两个可能的表示而漏判）。
 * @param markedAt 标记时刻（毫秒）。**每次重新标记都刷新**（重复标记不新增条目、只更新这一条），
 *   这样管理页的「标记时间」显示的是最后一次表态的时间。
 *   ⚠️ **取消标记不改这个值** —— 它记的是「什么时候标记的」，
 *   而非「什么时候取消的」。想按取消时间排序得另加字段。
 * @param title 冗余存一份片名。管理页要展示标题，而从库里反查一次是网络请求 ——
 *   条目被从库里删掉后就再也查不到了，留个快照至少还能显示它叫什么。
 * @param active **当前是否处于已标记状态**。
 *
 *   这个字段不是多余的：管理页给了「取消标记」和「删除」两个按钮，
 *   如果只有一个布尔量，这两个按钮就是同一件事。
 *   · `active = false` = 取消标记 —— **记录与原因都留着**（下次重新标记能带出上次的理由，
 *     管理页里这行置灰仍可见）；
 *   · 记录整条不在了 = 删除。
 */
data class DislikeMark(
  val key: DislikeKey,
  val title: String,
  val reason: String,
  val markedAt: Long,
  /** 冗余的缩略图 URL，管理页直接显示，省一次网络往返。 */
  val imageUrl: String? = null,
  val active: Boolean = true,
)