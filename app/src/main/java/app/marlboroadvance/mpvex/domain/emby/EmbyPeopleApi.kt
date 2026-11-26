package app.marlboroadvance.mpvex.domain.emby


// ════════════════════════════════════════════════════════════════════════
// 演员 / 人物：列表、详情、收藏态、作品数统计
// 从 EmbyClient 原样搬出（重构：god-class 拆分），行为零变化
// ════════════════════════════════════════════════════════════════════════
//
// ⚠️ 这一块的核心坑是「作品数」：`/Persons` 的 Fields 白名单里**没有 ChildCount**，
// Person 的 `ChildCount` 恒为 null，所以演员卡片角标只能由客户端自己数 ——
// 见 getPersonWorkCounts / getPersonWorkCountsAdaptive 两档策略。
//
// ⚠️ 收藏态有两条来源：`EnableUserData` 回传的 `UserData.IsFavorite`，
// 以及部分服务端在 Fields 过滤下丢字段时的兜底解析（readFavoriteFromPersonList）。

object EmbyPeopleApi {
  /**
   * 某媒体库里出现过的演员 / 导演 / 编剧。
   *
   * PersonTypes 用 | 分隔，和 Genres 一样；不传会把幕后工种全拉回来，列表会很长。
   */
  fun getPersons(
    server: EmbyServer,
    parentId: String? = null,
    personTypes: List<String> = listOf("Actor", "Director", "Writer"),
  ): List<EmbyIdName> = EmbyItemsApi.idNameList(
    server,
    EmbyPaths.PERSONS,
    parentId,
    mapOf("PersonTypes" to personTypes.joinToString("|")),
  )

  /**
   * 某媒体库里出现过的演员（PersonTypes=Actor）。
   *
   * 用于媒体库「演员」分类：返回**完整 EmbyItem**（带 [EmbyItem.ImageTags] 头像），
   * 复用现有媒体卡片渲染；强制 [EmbyItem.Type]="Person"，点击时走「该演员作品」路由。
   */
  fun getActors(
    server: EmbyServer,
    parentId: String? = null,
  ): List<EmbyItem> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      q["PersonTypes"] = "Actor"
      // 这里本来想直接要 ChildCount —— Person 在该字段里确实是「参与的作品数」，
      // 但 /Persons 端点的 Fields 白名单里没有它（官方文档的选项是 Budget / Chapters /
      // Genres / MediaStreams / People … ），传了会被服务端忽略，Person 的 ChildCount
      // 于是恒为 null，演员卡片左下角的角标永远画不出来。
      // 真正的数据来源是 [getPersonWorkCounts]（客户端自己数）。这行留着，万一某个
      // 服务端认它就能省掉那次统计。
      q["Fields"] = "ChildCount"
      // EnableUserData：让服务端回传 UserData.IsFavorite。演员长按收藏、收藏页「演员」tab
      // 都靠这个字段判断收藏状态，没有它 Person 的 IsFavorite 恒为空。
      q["EnableUserData"] = "true"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      EmbyHttpCore.getJson<EmbyItemsResult>(server, EmbyPaths.PERSONS, q)
        .Items
        .mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          val name = item.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          item.copy(Type = "Person")
        }
    }.onFailure {
      EmbyLog.warn("getPersons 失败：${it.message} —— 演员/导演筛选会是空的", it)
    }.getOrDefault(emptyList())

  /**
   * 拉**全服务器**的人员清单（演员 / 导演 / 编剧），给「演职员合并」扫重复用。
   *
   * 与 [getActors] 的三点差别：
   *  1. **不限媒体库**（`ParentId` 不传）：一个人在不同库里各有一份，合并必须跨库看；
   *  2. **工种可配**：重复的不只有演员，导演 / 编剧一样会裂，默认三种都要；
   *  3. **自己翻页**：`/Persons` 一次能返回多少条由服务端决定（部分版本会截断 `Limit`），
   *     所以用 `StartIndex` 逐页拉，并以**服务端给的 `TotalRecordCount`** 作为终止条件，
   *     而不是「本页返回数 < 请求数」—— 后者在服务端截断 Limit 时会在第一页就退出、漏掉大半。
   *
   * `Fields=ProviderIds` 是**尝试性**的：`/Persons` 的 Fields 白名单很窄
   * （[getActors] 已证实它会静默忽略 `ChildCount`）。能拿到就是「同名但其实是两个人」的
   * 关键反证；拿不到也不影响流程（退化为按名字判断）。
   */
  fun getPersonsDetailed(
    server: EmbyServer,
    personTypes: List<String> = listOf("Actor", "Director", "Writer"),
    maxItems: Int = EmbyClient.PERSON_SCAN_MAX,
  ): List<EmbyItem> = runCatching {
    val out = ArrayList<EmbyItem>()
    // 按 Id 去重：服务端若忽略 StartIndex 反复给同一批，没有这层会死循环
    val seen = HashSet<String>()
    var start = 0
    while (out.size < maxItems) {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      q["PersonTypes"] = personTypes.joinToString("|")
      q["Fields"] = "ProviderIds"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      q["StartIndex"] = start.toString()
      q["Limit"] = EmbyClient.PERSON_PAGE_SIZE.toString()
      val page = EmbyHttpCore.getJson<EmbyItemsResult>(server, EmbyPaths.PERSONS, q)
      if (page.Items.isEmpty()) break
      var fresh = 0
      page.Items.forEach { item ->
        val id = item.Id ?: return@forEach
        if (!seen.add(id)) return@forEach
        val name = item.Name?.takeIf { it.isNotBlank() } ?: return@forEach
        fresh++
        out.add(item.copy(Type = "Person", Name = name))
      }
      // 一条新的都没有 = 服务端在重复给同一批，收手（这也是防死循环的硬闸门）
      if (fresh == 0) break
      start += page.Items.size
      if (page.TotalRecordCount > 0 && start >= page.TotalRecordCount) break
    }
    out.take(maxItems)
  }.onFailure {
    EmbyLog.warn("scanAllPersons 失败：${it.message} —— 演职员合并的候选名单会是空的", it)
  }.getOrDefault(emptyList())

  /**
   * 一个人**参与了多少部作品** —— 用 `PersonIds` 查询的 `TotalRecordCount` 直接拿。
   *
   * 为什么不复用 [getPersonWorkCounts]：那个方法是把**整个库逐页拉下来自己数**，
   * 几千条的库要几十个请求，而且超过 1 万条时会整体放弃返回空表。
   * 这里只要一个数字，问服务端一次就够了（`Limit=1` 只取一条，响应很小）。
   *
   * `IsFolder=false` 与「作品数」的口径保持一致：只数**本身能播**的条目，
   * 免得一季、一个合集被算成一部作品。
   */
  fun getPersonWorkCount(server: EmbyServer, personId: String): Int =
    runCatching {
      EmbyItemsApi.getItems(
        server = server,
        personIds = listOf(personId),
        recursive = true,
        startIndex = 0,
        limit = 1,
        isFolder = false,
        fields = "People",
      ).TotalRecordCount
    }.onFailure {
      EmbyLog.warn("getPersonWorkCount($personId) 失败：${it.message} —— 该演员角标按0 处理", it)
    }.getOrDefault(0)

  /**
   * 收藏的演员列表（收藏页「演员」tab 用）。
   *
   * **为什么不用 `Filters=IsFavorite` 直接让服务端筛**：`/Persons` 这条端点对 Filters
   * 的支持参差不齐（官方 Emby 认，部分第三方兼容服务端会静默忽略，忽略后返回的是
   * 全量演员的第一页 —— 收藏的人可能根本不在里面）。而 `/Persons` 全量拉一遍是
   * 媒体库「演员」分类已经在用的成熟路径，这里复用它再在客户端按
   * `UserData.IsFavorite` 过滤，各服务端表现一致。
   *
   * 调用方（EmbyViewModel）按服务器缓存结果，切换 影片/演员 tab 不重复发请求。
   */
  fun getFavoritePersons(server: EmbyServer): List<EmbyItem> =
    getActors(server, null).filter { it.UserData?.IsFavorite == true }

  /**
   * 按 Id 取单个人员（演员 / 导演），带 UserData.IsFavorite。演员作品页的收藏红心用。
   *
   * ⚠️ 参数形状按 Emby Web 实际请求校正：
   *  · `/Persons` 的 `Ids` 与 `Recursive` **不能同时传** —— `Ids+Recursive` 会让服务端
   *    先展开整个库再过滤（本项目在 `getItems` 上已经踩过并规避，见那里的注释），
   *    第三方服务端上几千条的库能直接拖到超时；
   *  · `Ids` 本身在部分服务端的 `/Persons` 上**不被支持**（Emby Web 自己也不用它，
   *    人员列表走 `PersonTypes` / `IsFavorite` 过滤），被忽略时这条查询会退化成
   *    「未过滤的人员第一页」—— 演员大多不在第一页，于是红心永远读不到状态；
   *  · 补 `PersonTypes=Actor,Director,Writer`：部分服务端不传它就返回空；
   *  · `/Users/{uid}/Items/{id}` 是 Emby Web 人员详情页的同款端点，显式带
   *    `EnableUserData`，防服务端在该路径上默认不回传 UserData。
   *
   * 三条路径的结果**合并**而非早退：谁带 UserData 用谁（IsFavorite 是红心的唯一数据源），
   * 都不带就返回任意一个非空结果（至少保证条目可点、状态按「未收藏」兜底）。
   */
  fun getPersonById(server: EmbyServer, personId: String): EmbyItem? {
    val candidates = ArrayList<EmbyItem>()

    // 路径 1：/Persons + Ids（关掉 Recursive，见上方说明）
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      q["Ids"] = personId
      q["Recursive"] = "false"
      q["PersonTypes"] = "Actor,Director,Writer"
      q["SortBy"] = "SortName"
      q["EnableUserData"] = "true"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      EmbyHttpCore.getJson<EmbyItemsResult>(server, EmbyPaths.PERSONS, q)
        .Items
        .firstOrNull { it.Id == personId }
        ?.copy(Type = "Person")
    }.onFailure {
      EmbyLog.warn("getPersonById 路径1(/Persons?Ids) 对 $personId 失败：${it.message}", it)
    }.getOrNull()?.let { candidates += it }

    // 路径 2：/Users/{uid}/Items/{id}（Emby Web 人员详情页同款端点；轻量 Fields + EnableUserData）
    runCatching {
      EmbyHttpCore.getJson<EmbyItem>(
        server,
        EmbyPaths.userItem(server.userId, personId),
        mapOf(
          "EnableUserData" to "true",
          "Fields" to "BasicSyncInfo,CanDelete,CanDownload,PrimaryImageAspectRatio",
        ),
      )
        .takeIf { it.Id == personId }
        ?.let { item -> item.copy(Type = item.Type ?: "Person") }
    }.onFailure {
      EmbyLog.warn("getPersonById 路径2(/Users/{uid}/Items/{id}) 对 $personId 失败：${it.message}", it)
    }.getOrNull()?.let { candidates += it }

    // 路径 3：原有兜底（getItem 的重 fields 组合）
    runCatching { EmbyItemsApi.getItem(server, personId) }
      .getOrNull()
      ?.takeIf { it.Id == personId }
      ?.let { person -> person.copy(Type = person.Type ?: "Person") }
      ?.let { candidates += it }

    if (candidates.isEmpty()) {
      EmbyLog.warn("getPersonById($personId) 三条路径全部失败 —— 红心按「未收藏」兜底，仍可点击")
      return null
    }
    return candidates.firstOrNull { it.UserData != null } ?: candidates.first()
  }

  /**
   * 读单个人员的**收藏真值**（true=已收藏 / false=未收藏 / null=读不到，无法确认）。
   *
   * ⚠️ **两条路径的先后顺序在 2026-10-21 被对调过**，这是个踩出来的坑，别再调回去：
   * 旧顺序是「单条轻量读 → 收藏列表扫描」，而单条读走的 `/Users/{uid}/Items/{id}`
   * 在用户的这台服务端上**对相当一部分人员会卡满 30 秒然后回 HTTP 500**
   * （如 1114461 / 1083309 / 1333172 / 1132405 全是 500，而 1031021、1081006 又 40ms 正常）。
   * 后果有两个：① 进演员页要先干等 30s+ 才轮到下一步；② 单条读抛异常后 `runCatching`
   * 返回 null，才轮到列表法 —— 而列表法其实 **54ms** 就能给出真值。
   * 更致命的是：演员作品页把「读收藏状态」挂在 [getPersonById] 的非空返回值后面，
   * 而 [getPersonById] 的三条路径（`/Persons?Ids` 不支持 → 空；`/Users/{uid}/Items/{id}` → 500）
   * 在这台机上对演员**全部失败**返回 null，于是列表法**连执行的机会都没有** ——
   * 这就是「导演作品页红心正常、演员作品页红心永远置灰」的根因（导演刚好走通了单条读）。
   *
   * 为什么敢把列表法放在第一步：
   *  · `/Persons?UserId=<guid>&IsFavorite=true&EnableUserData=true` 54ms 返回，带 UserData；
   *  · `POST .../FavoriteItems/<id>` 收藏一个人之后**立刻**再查，列表马上多出这个人
   *    （不存在「按 URL 缓存、回读拿到旧值」的问题 —— 旧代码担心的缓存坑在这条 URL 上不成立）；
   *  · `DELETE` 取消收藏后同样立刻消失。
   * 所以它可以同时承担「进页面取初值」和「收藏后回读确认」两个职责。
   *
   * 为什么不能只靠 [getPersonById]：演员红心历史上最顽固的表现是「收藏明明成功、
   * 回读却说没收藏，红心被回滚成灰色，怎么点都点不亮」。成因对上过两条：
   *  1. 服务端（或中间层）按 URL 缓存响应 —— 加载时读一次 `/Persons?Ids=X`，
   *     收藏成功后**同一个 URL** 再读还是缓存里的旧 UserData，于是把成功误判成失败；
   *  2. `/Persons` 的 `Ids` 参数在部分服务端上不被支持（见 [getPersonById] 注释）。
   */
  fun getPersonFavoriteState(server: EmbyServer, personId: String): Boolean? {
    // 1) 收藏列表法（Emby Web 收藏页同款查询；最快、真值新鲜）—— 见上方注释，别调换顺序
    readFavoriteFromPersonList(server, personId)?.let { return it }
    // 2) 单条轻量读兜底：列表法拿不到结论时才走。部分人员在这台服务端上会 30s 后 500，
    //    失败就放弃（返回 null = 无法确认），不再让红心为它干等。
    return runCatching {
      EmbyHttpCore.getJson<EmbyItem>(
        server,
        EmbyPaths.userItem(server.userId, personId),
        mapOf("EnableUserData" to "true"),
      )
        .takeIf { it.Id == personId }
        ?.UserData?.IsFavorite
    }.onFailure {
      EmbyLog.warn("getPersonFavoriteState 单条读 $personId 失败：${it.message}", it)
    }.getOrNull()
  }

  /**
   * 翻 `/Persons?IsFavorite=true` 找这个人：true=已收藏 / false=不在收藏列表里 / null=下不了结论。
   *
   * [isFavoriteFilterHonored] 只在「人找到了但没带 UserData」这一个歧义分支上被调用 ——
   * 那种情况下「出现在列表里」到底是"它被过滤出来的"还是"服务端忽略过滤返回了全量人员"，
   * 必须靠总数对比才能定，否则会把所有人的红心都点亮成已收藏。
   */
  internal fun readFavoriteFromPersonList(server: EmbyServer, personId: String): Boolean? =
    runCatching {
      var start = 0
      val limit = 100
      var favTotal = 0
      var found: EmbyItem? = null
      while (found == null) {
        val q = LinkedHashMap<String, String?>()
        q["UserId"] = server.userId
        q["IsFavorite"] = "true"
        q["Recursive"] = "true"
        q["SortBy"] = "SortName"
        q["SortOrder"] = "Ascending"
        q["EnableUserData"] = "true"
        q["EnableTotalRecordCount"] = "true"
        q["StartIndex"] = start.toString()
        q["Limit"] = limit.toString()
        val page = EmbyHttpCore.getJson<EmbyItemsResult>(server, EmbyPaths.PERSONS, q)
        favTotal = page.TotalRecordCount
        found = page.Items.firstOrNull { it.Id == personId }
        if (found == null) {
          if (page.Items.isEmpty()) break
          // 终止条件两条腿走路：TotalRecordCount 说了算（服务端若忽略 Limit、按自己的
          // 默认上限分页时，「页不满」不代表拉完，会漏掉后面页里的人）；拿不到总数
          // （服务端不回传）才退化看页是否不满
          val total = page.TotalRecordCount.takeIf { it > 0 }
          if (total != null) {
            if (start + page.Items.size >= total) break
          } else if (page.Items.size < limit) {
            break
          }
          start += page.Items.size
          // 兜底防无限翻页：收藏的人员不可能有这个量级
          if (start >= 10_000) break
        }
      }
      val hit = found
      val hitFavorite = hit?.UserData?.IsFavorite
      when {
        // 翻完没找到 → 未收藏（能确认）
        hit == null -> false
        // 找到了且自带 UserData.IsFavorite → 直接信它（EnableUserData 生效时最准）
        hitFavorite != null -> hitFavorite
        // 找到了但拿不到 UserData.IsFavorite → 只有确认过滤真生效了，才敢把「在列表里」当成已收藏
        isFavoriteFilterHonored(server, favTotal) -> true
        else -> null
      }
    }.onFailure {
      EmbyLog.warn("readFavoriteFromPersonList($personId) 失败：${it.message}", it)
    }.getOrNull()

  /**
   * 判断这台服务端的 `/Persons` 是否真的认 `IsFavorite=true` 过滤：
   * 「收藏人员总数」明显少于「全部人员总数」就是生效了。
   *
   * 代价只是一次 `Limit=1` 的轻量请求，而且只在 [readFavoriteFromPersonList]
   * 撞上「找得到人、却没有 UserData」这个罕见分支时才发。
   */
  internal fun isFavoriteFilterHonored(server: EmbyServer, favTotal: Int): Boolean {
    if (favTotal <= 0) return false
    val q = LinkedHashMap<String, String?>()
    q["UserId"] = server.userId
    q["Recursive"] = "true"
    q["EnableTotalRecordCount"] = "true"
    q["Limit"] = "1"
    val allTotal = runCatching {
      EmbyHttpCore.getJson<EmbyItemsResult>(server, EmbyPaths.PERSONS, q).TotalRecordCount
    }.getOrNull() ?: return false
    return allTotal > favTotal
  }

  /**
   * 数每个演员在该媒体库里参与了多少部作品：PersonId → 条目数。
   *
   * **为什么不直接读 `ChildCount`**：`/Persons` 端点的 `Fields` 参数并不支持
   * `ChildCount`（官方文档列出的可选项是 Budget / Chapters / Genres / MediaStreams /
   * People … ，没有它），传了也会被服务端忽略，Person 的 `ChildCount` 于是恒为
   * null —— 演员卡片左下角那个作品数角标就永远画不出来。所以自己数一遍。
   *
   * 口径与「点进这位演员」那一页保持一致（同一个库 + 递归 + 只要非容器条目），
   * 角标上的数字因此和点进去看到的条数对得上。
   *
   * 代价是把整个库逐页拉一遍，所以只索取 `People`、把 MediaSources 这类大字段去掉；
   * 几千条的库通常几次请求就够。调用方应放在后台，不要挡首屏。
   */
  fun getPersonWorkCounts(server: EmbyServer, parentId: String?): Map<String, Int> =
    runCatching {
      val counts = HashMap<String, Int>()
      // 按 Id 去重：服务端若忽略 StartIndex 反复返回同一批，没有这层就会把同一部数好几次
      val seen = HashSet<String>()
      var start = 0
      while (true) {
        val page = EmbyItemsApi.getItems(
          server = server,
          parentId = parentId,
          startIndex = start,
          limit = EmbyClient.COUNT_PAGE_SIZE,
          recursive = true,
          // 只要「本身能播」的条目：把 Folder / Season / BoxSet 这些容器挡掉，
          // 否则一季、一个合集都会被算成一部作品，数字虚高
          isFolder = false,
          fields = "People",
        )
        var fresh = 0
        page.Items.forEach itemLoop@{ item ->
          val id = item.Id
          if (id != null && !seen.add(id)) return@itemLoop
          fresh++
          item.People?.forEach { person ->
            person.Id?.let { pid -> counts[pid] = (counts[pid] ?: 0) + 1 }
          }
        }
        // 库太大就整体放弃：与其拉几十 MB 响应，不如让这几个角标先空着。
        // 只看第一页拿到的总数就够判断，不必真拉完
        if (start == 0 && page.TotalRecordCount > EmbyClient.COUNT_MAX_ITEMS) {
          android.util.Log.d(
            "EmbyClient",
            "库规模 ${page.TotalRecordCount} 超过统计上限 $EmbyClient.COUNT_MAX_ITEMS，跳过演员作品数统计",
          )
          return@runCatching emptyMap()
        }
        // 本页一条新的都没有 = 服务端在重复给同一批，收手
        if (fresh == 0 || page.Items.size < EmbyClient.COUNT_PAGE_SIZE) break
        start += EmbyClient.COUNT_PAGE_SIZE
        if (start >= EmbyClient.COUNT_MAX_ITEMS) break
      }
      counts
    }.onFailure {
      EmbyLog.warn("getPersonWorkCounts 失败：${it.message} —— 演员角标会留空", it)
    }.getOrDefault(emptyMap())

  /**
   * 演员作品数角标 —— 按库规模自动挑一条路（[getPersonWorkCounts] 全库扫描太贵，
   * 逐个问又不敢全信，两头都要的话这里统一收口）。
   *
   * 策略分两档，用**第一条请求的 TotalRecordCount** 判断：
   *
   * - **小库**（≤ [EmbyClient.COUNT_FULL_SCAN_MAX]）：走 [getPersonWorkCounts] 全库扫描。
   *   几千条 = 2~6 个请求就把**所有**演员的角标一次算齐，最划算。
   * - **大库**：逐个 PersonId 问服务端要 `TotalRecordCount`（[getPersonWorkCount]，
   *   `Limit=1` 响应极小），但**只查前 [EmbyClient.ADAPTIVE_PER_PERSON_LIMIT] 个**。
   *   角标是次要信息，为它把大库拉穿不划算 —— 查不到的留空即可。
   *
   * @param personIds 这一轮要画角标的演员（通常是当前列表那一批）
   */
  fun getPersonWorkCountsAdaptive(
    server: EmbyServer,
    parentId: String?,
    personIds: List<String>,
  ): Map<String, Int> {
    val targets = personIds.filter { it.isNotBlank() }.distinct()
    if (targets.isEmpty()) return emptyMap()

    val total = runCatching {
      EmbyItemsApi.getItems(
        server = server,
        parentId = parentId,
        startIndex = 0,
        limit = 1,
        recursive = true,
        isFolder = false,
        fields = "",
      ).TotalRecordCount
    }.getOrDefault(0)

    if (total in 1..EmbyClient.COUNT_FULL_SCAN_MAX) {
      val all = getPersonWorkCounts(server, parentId)
      if (all.isNotEmpty()) return all
    }

    val out = HashMap<String, Int>(targets.size)
    for (pid in targets.take(EmbyClient.ADAPTIVE_PER_PERSON_LIMIT)) {
      val n = getPersonWorkCount(server, pid)
      if (n > 0) out[pid] = n
    }
    return out
  }

  /**
   * 按名字搜演员。
   *
   * 必须走 `/Persons`：`/Users/{id}/Items` 这条拿媒体的端点**不返回 Person**，
   * 所以即便传了 `IncludeItemTypes=Person`，[getItems] 也永远只能拿到空列表 ——
   * 表现就是「勾选『演员』筛选后什么都搜不到」。
   *
   * @param parentId 限定某个媒体库；null 表示跨库（全库搜索用）
   */
  fun searchPersons(
    server: EmbyServer,
    term: String,
    parentId: String? = null,
    limit: Int = 60,
  ): List<EmbyItem> =
    runCatching {
      val q = LinkedHashMap<String, String?>()
      q["UserId"] = server.userId
      parentId?.let { q["ParentId"] = it }
      q["Recursive"] = "true"
      q["SortBy"] = "SortName"
      q["SearchTerm"] = term
      q["Limit"] = limit.toString()
      // 与 [getActors] 同样的情况：Fields 里的 ChildCount 不被 /Persons 支持，
      // 搜索结果那张卡片的作品数同样得靠调用方另行统计（见 [getPersonWorkCounts]）
      q["Fields"] = "ChildCount"
      q["EnableImages"] = "true"
      q["ImageTypeLimit"] = "1"
      EmbyHttpCore.getJson<EmbyItemsResult>(server, EmbyPaths.PERSONS, q)
        .Items
        .mapNotNull { item ->
          val id = item.Id ?: return@mapNotNull null
          val name = item.Name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          item.copy(Type = "Person")
        }
    }.onFailure {
      EmbyLog.warn("searchPersons(\"$term\") 失败：${it.message} —— 搜演员会搜不到", it)
    }.getOrDefault(emptyList())
}
