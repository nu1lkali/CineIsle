package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 演职员合并（Person 去重）的**纯逻辑**层。
 *
 * 这里一行网络请求都没有，全是可直接跑单测的纯函数 —— 因为这一层做错事的代价最大：
 * 判重判错会把两个人的作品搅在一起（而且**不会报错**，用户很难发现）。
 * 所以「怎么算同一个名字」「保留哪条」「条目里的 People 怎么改」这三件事
 * 全部落在这里，IO 与 UI 都不碰（见 [PersonMerger] 与 `PersonMergeScreen`）。
 *
 * ⚠️ 根因：Emby 的 Person 是**按人物名字字符串**建条目、也按名字匹配的
 * （官方开发者 Luke 确认：NFO 与不提供 id 的元数据源仍只能靠名字匹配）。
 * ⇒ 名字串只要不一样，就是两条独立 Person：`成龙` 与 `成龙(配音)` 会裂成两条，
 * 于是作品页只显示一部分、收藏对不上、演职员表出现重复面孔。
 */

// ════════════════════════════════════════════════════════════════════════
// 1. 名字规范化
// ════════════════════════════════════════════════════════════════════════

/** 括号注释（中英文、全半角方括号都算）：`张三(配音)` / `张三【配音】` / `Tom (voice)` */
private val BRACKET_NOTE = Regex("[（(\\[【][^）)\\]】]*[）)\\]】]")

/** 需要剔除的分隔符与空白：间隔号、逗号顿号、下划线、连字符、撇号、引号 */
private val NAME_SEPARATORS = Regex("[\\s·．.，,、_\\-'’‘\"“”]")

/**
 * 人物名字规范化 —— **判「是不是同一个人」的唯一一把尺子**。
 *
 * 五步：去首尾空白 → 剔括号注释 → 全角转半角 → 删分隔符与空白 → 折叠大小写。
 *
 * ⚠️ **刻意不做**拼音等价、跨语言翻译等价（`成龙` vs `Jackie Chan`）——
 * 没有可靠词典，而**误判的代价（把两个人的作品搅在一起、还不容易发现）
 * 远高于漏判（用户手动点一次）**。这类跨语言的重复走「手动绑定」入口。
 *
 * 提取成函数是为了能单独验证：这一层判错，后面所有东西都是错的。
 */
fun normalizePersonName(raw: String): String {
  var s = raw.trim()
  // 1) 剔除括号注释：`张三(配音)` → `张三`
  s = BRACKET_NOTE.replace(s, "")
  // 2) 全角 → 半角（字母 / 数字 / 标点），顺便把全角空格统一成普通空格
  val sb = StringBuilder(s.length)
  for (ch in s) {
    sb.append(
      when {
        ch == '\u3000' -> ' '
        ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
        else -> ch
      },
    )
  }
  // 3) 删掉空格与各种分隔符，4) 折叠大小写
  return NAME_SEPARATORS.replace(sb.toString(), "").lowercase()
}

// ════════════════════════════════════════════════════════════════════════
// 2. 数据结构
// ════════════════════════════════════════════════════════════════════════

/**
 * 参与合并的一位演员（轻量投影，不带整条 [EmbyItem]）。
 *
 * @param id Emby 的 Person Id，**唯一身份**
 * @param name 服务器上的原始名字（合并后主条目沿用它，因为名字改不了）
 * @param imageTag 头像 tag，仅用于列表识别
 * @param workCount 参与的作品数，用于挑「保留哪条」
 * @param providerIds 外部元数据 id（Tmdb / Imdb…）。⚠️ `/Persons` 的 Fields 白名单很窄，
 *   这个字段**可能拿不到** —— 拿到时它是「同名但其实是两个人」的关键反证，拿不到就退化为按名字判断。
 */
data class PersonRef(
  val id: String,
  val name: String,
  val imageTag: String? = null,
  val workCount: Int = 0,
  val providerIds: Map<String, String> = emptyMap(),
)

/** 把服务器返回的人员条目投影成 [PersonRef]；没有 Id 的（理论上不存在）直接丢弃 */
fun EmbyItem.toPersonRef(workCount: Int = 0): PersonRef? {
  val pid = Id ?: return null
  val pname = Name?.takeIf { it.isNotBlank() } ?: return null
  return PersonRef(
    id = pid,
    name = pname,
    imageTag = ImageTags["Primary"],
    workCount = workCount,
    providerIds = ProviderIds,
  )
}

/** 重复的成因分档：只影响展示分组与「默认勾不勾」，不影响合并本身的正确性。 */
enum class DuplicateKind {
  /** 规范化后**名字完全相同** —— 这是「自动合并同名」唯一会碰的一档 */
  SAME_NAME,

  /** 名字互为前缀（`成龙` / `成龙 Jackie Chan`）—— **只作建议、默认不勾** */
  SIMILAR_NAME,
}

/**
 * 一组疑似重复的演员。
 *
 * @param conflictingIds 同名但外部 ID 互相冲突（同一家 provider 给了**不同的** id）——
 *   说明是**两个同名的不同人**。这种组必须人工确认，**永远不参与自动合并**。
 */
data class DuplicateGroup(
  val kind: DuplicateKind,
  /** 展示用的组名（同名 = 规范化名；相近 = `A / B`） */
  val key: String,
  val members: List<PersonRef>,
  val conflictingIds: Boolean = false,
) {
  /** 建议保留的那条（规则见 [pickCanonical]） */
  val suggestedCanonical: PersonRef get() = pickCanonical(members)

  val totalWorks: Int get() = members.sumOf { it.workCount }
}

/**
 * 挑「保留哪条」。规则**必须确定性、可复现** —— 同样的输入永远给同样的结果，
 * 否则用户第二次点「自动合并」看到的保留项会不一样，会以为程序在乱来。
 *
 * 顺序：作品最多 → 名字最长 → 字典序最小。
 *
 * ⚠️ 作品数拿不到时（服务端不给 / 统计被跳过）全部为 0，此时会退化成
 * 「名字最长 → 字典序」，仍然是确定的，只是可能不是最优的那条 ——
 * 所以自动合并前必须把「保留谁」明确展示给用户看（见 UI 的确认弹窗）。
 */
fun pickCanonical(members: List<PersonRef>): PersonRef = members
  .sortedWith(
    compareByDescending<PersonRef> { it.workCount }
      .thenByDescending { it.name.length }
      .thenBy { it.name },
  )
  .first()

// ════════════════════════════════════════════════════════════════════════
// 3. 聚类：找出疑似重复的组
// ════════════════════════════════════════════════════════════════════════

/** 同名的不同人：同一家 provider 上出现了两个**不同的**非空 id */
private fun hasProviderConflict(members: List<PersonRef>): Boolean {
  val seen = HashMap<String, String>()
  for (m in members) {
    for ((k, v) in m.providerIds) {
      if (v.isBlank()) continue
      val prev = seen[k]
      if (prev == null) {
        seen[k] = v
      } else if (prev != v) {
        return true
      }
    }
  }
  return false
}

/**
 * 把人员列表聚成重复组。**不联网、不改数据**，只回答「哪些看起来是同一个人」。
 *
 * 两条线：
 *  1. **同名**（[normalizePersonName] 相同，≥2 条）→ [DuplicateKind.SAME_NAME]；
 *  2. **名字相近**（规范化后互为前缀，如 `成龙` / `成龙jackiechan`）→ [DuplicateKind.SIMILAR_NAME]。
 *
 * ⚠️ 这里**没有**「共同作品 + 同角色」那一档：它需要把整个库的每个条目的 People 都拉下来
 * 交叉比对（几千条请求），收益却不如「手动绑定」直接 —— 用户看一眼就知道该不该并。
 * 与其做一个昂贵又半准的自动档，不如把人工入口做好。
 *
 * ⚠️ 相近档用**前缀**判定而不是「长度差 ≤ 2」：`成龙`(2) 与 `成龙jackiechan`(13)
 * 长度差 11，用长度差会把最典型的那个例子漏掉。代价是会带上 `张伟` / `张伟丽`
 * 这类误判 —— 所以这一档**默认不勾、只作建议**。
 */
fun clusterDuplicatePersons(persons: List<PersonRef>): List<DuplicateGroup> {
  if (persons.size < 2) return emptyList()

  val result = ArrayList<DuplicateGroup>()
  val byNormalized = LinkedHashMap<String, MutableList<PersonRef>>()
  for (p in persons) {
    val key = normalizePersonName(p.name)
    if (key.isEmpty()) continue
    byNormalized.getOrPut(key) { ArrayList<PersonRef>() }.add(p)
  }

  // ── 同名组 ──
  for ((key, members) in byNormalized) {
    if (members.size < 2) continue
    result += DuplicateGroup(
      kind = DuplicateKind.SAME_NAME,
      key = key,
      members = members.sortedWith(
        compareByDescending<PersonRef> { it.workCount }.thenBy { it.name },
      ),
      conflictingIds = hasProviderConflict(members),
    )
  }

  // ── 相近组（互为前缀）──
  // 排序后，「以 X 为前缀的名字」必然连成一段，所以对每个 X 只往后扫到不再匹配为止，
  // 复杂度 O(n × 段的平均长度)。直接两两比较在几千人时是 O(n²)，会卡死主线程。
  val names = byNormalized.keys.sorted()
  val parent = IntArray(names.size) { it }
  fun find(x: Int): Int {
    var r = x
    while (parent[r] != r) r = parent[r]
    var c = x
    while (parent[c] != c) {
      val next = parent[c]
      parent[c] = r
      c = next
    }
    return r
  }

  fun union(a: Int, b: Int) {
    val ra = find(a)
    val rb = find(b)
    if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
  }

  for (i in names.indices) {
    val head = names[i]
    // 单个字符的名字不做前缀匹配：`李` 能匹配一大片，全是噪音
    if (head.length < 2) continue
    var j = i + 1
    while (j < names.size && names[j].startsWith(head)) {
      union(i, j)
      j++
    }
  }

  val similarBuckets = LinkedHashMap<Int, MutableList<String>>()
  for (i in names.indices) {
    // 单个名字自成一组的不算「相近」；同时把已在同名组里的名字也带上 ——
    // 例如 `成龙`(2 条) 与 `成龙jackiechan`(1 条) 应当一起出现在相近组里，
    // 否则用户在这个入口根本看不到它们。
    if (byNormalized[names[i]]!!.size < 2) continue
    similarBuckets.getOrPut(find(i)) { ArrayList<String>() }.add(names[i])
  }
  // 上面只收了「本身有同名重复」的名字，还要把与它们互为前缀的**单个**名字也拉进来
  for (i in names.indices) {
    val root = find(i)
    val bucket = similarBuckets[root] ?: continue
    if (!bucket.contains(names[i])) bucket.add(names[i])
  }

  for ((_, bucket) in similarBuckets) {
    if (bucket.size < 2) continue
    val members = bucket.flatMap { byNormalized[it].orEmpty() }
    if (members.size < 2) continue
    result += DuplicateGroup(
      kind = DuplicateKind.SIMILAR_NAME,
      key = bucket.joinToString(" / "),
      members = members.sortedWith(
        compareByDescending<PersonRef> { it.workCount }.thenBy { it.name },
      ),
      conflictingIds = hasProviderConflict(members),
    )
  }

  // 同名组排前面（更可信），组内先按作品多的排
  return result.sortedWith(
    compareBy<DuplicateGroup> { it.kind.ordinal }
      .thenByDescending { it.totalWorks }
      .thenBy { it.key },
  )
}

/** 「自动合并同名」用的筛选：**只碰同名档，且排除 ID 冲突的组**。 */
fun autoMergeableGroups(groups: List<DuplicateGroup>): List<DuplicateGroup> =
  groups.filter { it.kind == DuplicateKind.SAME_NAME && !it.conflictingIds && it.members.size >= 2 }

// ════════════════════════════════════════════════════════════════════════
// 4. 改写条目的 People（纯 JSON 级，不碰 data class）
// ════════════════════════════════════════════════════════════════════════

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonObject.tripleKey(): String? {
  val id = this["Id"].stringOrNull() ?: return null
  val type = this["Type"].stringOrNull().orEmpty()
  val role = this["Role"].stringOrNull().orEmpty()
  return "$id\u0000$type\u0000$role"
}

/**
 * 把条目里的 [memberIds] 全部改指到 [canonicalId]，返回改写后的**整份条目 JSON**。
 *
 * ## 为什么必须动原始 JSON，而不是我们自己的 `EmbyItem`
 * `POST /Items/{id}` 要求**回传完整 payload**（Emby 开发者 Luke 官方确认：
 * "you have to include the complete payload"，缺字段会被清空）。而我们的 `EmbyItem`
 * 只声明了四十来个字段，序列化回去恰好就是「不完整的 payload」—— 会**清掉**
 * 简介 / 评分 / 图片 tag 等等上百个字段。所以这里全程在 `JsonObject` 上做手术，
 * 一个我们不认识的键都不丢。
 *
 * ## 改写规则（比社区脚本细）
 * ```
 * for p in People:
 *   if p.Id ∈ memberIds:
 *     if 已存在 q: q.Id == canonicalId ∧ q.Type == p.Type ∧ q.Role == p.Role:
 *       drop p                      // (Id, Type, Role) 三项全同 = 完全重复
 *     else:
 *       p.Id = canonicalId; p.Name = canonicalName; 去掉 p.PrimaryImageTag
 *       // Type / Role **原样保留**
 * ```
 * ⚠️ 社区脚本的判重条件是「只要保留项已存在就 drop」—— 同一个人**既是演员又是导演**
 * （或同片两个不同角色）时，它会把其中一条直接删掉、**丢信息**。这里按三元组判重，
 * 只有三项全同才删。
 *
 * @return 改写后的整份 JSON；**没有任何改动时返回 null**（调用方据此跳过，不发请求）
 */
fun rewriteItemPeople(
  item: JsonObject,
  canonicalId: String,
  canonicalName: String,
  memberIds: Set<String>,
): JsonObject? {
  if (memberIds.isEmpty() || canonicalId in memberIds) return null
  val people = item["People"] as? JsonArray ?: return null
  if (people.isEmpty()) return null

  var changed = false
  val rewritten = ArrayList<JsonElement>(people.size)

  for (el in people) {
    val person = el as? JsonObject
    val pid = person?.get("Id").stringOrNull()
    if (person == null || pid == null || pid !in memberIds) {
      rewritten.add(el)
      continue
    }
    if (pid == canonicalId) {
      rewritten.add(el)
      continue
    }
    val type = person["Type"].stringOrNull()
    val role = person["Role"].stringOrNull()
    // 保留项**以同样的身份**已经在这个条目里 → 这条纯属重复，直接去掉
    val duplicated = people.any { other ->
      val o = other as? JsonObject ?: return@any false
      o["Id"].stringOrNull() == canonicalId &&
        o["Type"].stringOrNull() == type &&
        o["Role"].stringOrNull() == role
    }
    if (duplicated) {
      changed = true
      continue
    }
    // 换指保留项：只改 Id / Name。
    // PrimaryImageTag 必须去掉 —— 它属于「变体」那张头像，换指后 tag 就过期了，
    // 留着会让客户端拿一个对不上的 tag 去请求（图片加载失败）；去掉后 Emby 读的时候会重新填。
    val mutable = LinkedHashMap(person)
    mutable["Id"] = JsonPrimitive(canonicalId)
    mutable["Name"] = JsonPrimitive(canonicalName)
    mutable.remove("PrimaryImageTag")
    rewritten.add(JsonObject(mutable))
    changed = true
  }

  if (!changed) return null

  // 收尾：按 (Id, Type, Role) 去重一次（保序）。
  // 只有**拿到 Id 的**条目才参与去重 —— 没有 Id 的没法判断是不是同一个，宁可留着。
  val seen = HashSet<String>()
  val deduped = ArrayList<JsonElement>(rewritten.size)
  for (el in rewritten) {
    val o = el as? JsonObject
    val key = o?.tripleKey()
    if (o == null || key == null) {
      deduped.add(el)
      continue
    }
    if (seen.add(key)) deduped.add(el)
  }

  val out = LinkedHashMap(item)
  // ⚠️ 提交前把每条 People **削成最小结构**（见 sanitizePersonEntry）——
  // 原样回传 GET 拿到的条目（带 PrimaryImageTag 等附加字段）会被部分服务端
  // 静默退回：回 200、People 原样不动。
  out["People"] = JsonArray(deduped.map { sanitizePersonEntry(it) })
  return JsonObject(out)
}

/**
 * 把一条 People 条目**削成服务端「已知能吃」的最小结构**：
 * 只留 `Name / Id / Type / Role / ProviderIds`，其余（`PrimaryImageTag`、图片 tag 等
 * 服务端下发时附带的东西）全部丢弃。
 *
 * 为什么必须削：Emby 官方论坛的社区实测与现成工具（emby-toolkit）都表明，
 * `POST /Items/{id}` 的演员表更新对 People 条目里**多余的回传字段**非常敏感 ——
 * 原样回传 GET 拿到的条目，部分服务端会**回 200 但 People 原样不保存**；
 * 整个数组都换成这种最小结构就能正常落库。
 * （另：论坛实测还确认服务端保存时会按 Name 重新解析 Person、无视条目里的 Id ——
 * 所以同名合并必须先把被并项改成唯一的临时名，见 PersonMerger。）
 */
private fun sanitizePersonEntry(el: JsonElement): JsonElement {
  val o = el as? JsonObject ?: return el
  val clean = LinkedHashMap<String, JsonElement>()
  o["Name"]?.let { clean["Name"] = it }
  o["Id"]?.let { clean["Id"] = it }
  o["Type"]?.let { clean["Type"] = it }
  o["Role"]?.let { clean["Role"] = it }
  (o["ProviderIds"] as? JsonObject)?.let { clean["ProviderIds"] = it }
  return JsonObject(clean)
}

/**
 * 条目 People 的**指纹**，用于两件事：
 *  1. 回写后校验「服务器是不是真的收下了」（有的兼容服务端回 200 却什么都没改）；
 *  2. 撤销前确认「这条自从我们改过之后没被别人动过」。
 *
 * 取 `(Id, Type, Role)` 三元组的**排序去重集合**而不是原始数组：
 * 服务端保存时重排 People 顺序是常事，按数组逐字比较会把「其实没问题」误判成
 * 「被人改过了」，撤销就永远不敢动。去重 + 排序 + 大小写归一后只对**实质变化**敏感。
 */
fun peopleFingerprint(item: JsonObject): String {
  val people = item["People"] as? JsonArray ?: return ""
  return people
    .mapNotNull { el ->
      val o = el as? JsonObject ?: return@mapNotNull null
      // ⚠️ Id 也必须归一化：它是 GUID，大小写没有任何语义，但**字面比较会因此翻车**。
      // 曾经漏掉这一步 —— 服务端存回去时把 GUID 的大小写换了一下，指纹就对不上，
      // 于是「写入完全成功」被报成「回读校验未通过」。
      val id = o["Id"].stringOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: return@mapNotNull null
      val type = o["Type"].stringOrNull().orEmpty().trim().lowercase()
      val role = o["Role"].stringOrNull().orEmpty().trim().lowercase()
      "$id|$type|$role"
    }
    .toSortedSet()
    .joinToString("\n")
}

/**
 * **旧版**指纹（`Id` 不做大小写归一），只为读**历史账本**而保留。
 *
 * 账本里存的 `afterFingerprint` 是当初那一版算法算出来的；如果只认新值，
 * 升级前存下的批次会在撤销时被统统误判成「这条被别人改过」而拒绝回滚 ——
 * 用户明明什么都没动，撤销却一个都不生效。所以闸门要**新旧两个值都接受**。
 *
 * ⚠️ 只用于「比对既有账本」，**不要**用于新写入。
 */
fun legacyPeopleFingerprint(item: JsonObject): String {
  val people = item["People"] as? JsonArray ?: return ""
  return people
    .mapNotNull { el ->
      val o = el as? JsonObject ?: return@mapNotNull null
      val id = o["Id"].stringOrNull() ?: return@mapNotNull null
      val type = o["Type"].stringOrNull().orEmpty().lowercase()
      val role = o["Role"].stringOrNull().orEmpty().trim().lowercase()
      "$id|$type|$role"
    }
    .toSortedSet()
    .joinToString("\n")
}

/**
 * 从「作品数表」里挑出已经没有任何作品的演员。
 *
 * ⚠️ **只在 [candidateIds] 范围内找**（默认是本次合并账本里出现过的变体）。
 * 全库范围扫会把「暂时没作品但用户想留着」的人也算进来 —— 那是越权。
 *
 * ⚠️ 调用方必须先确认「作品数表是可信的」：`getPersonWorkCounts` 在库太大时
 * 会**整体放弃**返回空表，此时拿它判孤立会把**所有演员都误判成孤立**。
 */
fun findOrphanPersons(
  counts: Map<String, Int>,
  candidateIds: Collection<String>,
): List<String> = candidateIds.filter { counts[it] == 0 }.toList()
