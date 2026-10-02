package app.marlboroadvance.mpvex.domain.emby

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 一条会被改写的作品（只带 Id 与片名，够进度提示用） */
data class MergeItemRef(val id: String, val name: String)

/** 合并的阶段，UI 据此换文案 */
enum class MergePhase { COLLECTING, WRITING, DONE }

/** 合并进度（推给 UI 的实时快照） */
data class MergeProgress(
  val phase: MergePhase,
  /** 保留项的名字（进度文案里的主语） */
  val canonicalName: String = "",
  val done: Int = 0,
  val total: Int = 0,
  /** 正在处理的作品名 */
  val currentName: String = "",
  val wrote: Int = 0,
  val failed: Int = 0,
)

/**
 * 一批合并的结果。
 *
 * @param verified 首条回读校验有没有通过。false = 服务器回了成功但**没真的落库**，
 *   这种情况必须显示给用户（部分兼容服务端有「回 200 不生效」的前科），不能假装成功。
 * @param cancelled 被用户中途取消；已写入的部分仍然留在账本里、可以撤销。
 */
data class MergeOutcome(
  val batchId: String?,
  val wrote: Int,
  val skipped: Int,
  val failed: Int,
  val total: Int,
  val verified: Boolean,
  val cancelled: Boolean,
  val firstError: String? = null,
  /** 回读判负时的现场摘要（回读到了什么 vs 期望什么），排障用 */
  val verifyDetail: String? = null,
  /** 补扫阶段救回来的条目数（第一遍被服务端退回、第二遍写成功的） */
  val repaired: Int = 0,
  /** 本批实际被服务端接受的写入动词（POST/PUT），排障用 */
  val writeVerbs: String? = null,
)

/** 一次撤销的结果：恢复了几条、跳过了几条（被别人改过）、失败几条 */
data class UndoOutcome(
  val restored: Int,
  val skipped: Int,
  val failed: Int,
  val firstError: String? = null,
  /** 恢复了几条「合并前的临时改名」；同名合并撤销时会有值 */
  val renamesRestored: Int = 0,
)

/** 「清理孤立演员」的结果 */
data class OrphanCleanOutcome(
  /** 触发到的服务端任务名；null = 没触发（没有孤立演员，或服务端没有对应任务） */
  val taskName: String?,
  /** 扫描出多少个「已无任何作品」的演员 */
  val orphanCount: Int,
  /** 服务端任务清单里压根没找到可用的任务 */
  val noTask: Boolean = false,
)

/**
 * 演职员合并的**执行引擎**：读原文 → 定点改写 `People` → 整包回写 → 回读校验 → 留账本。
 *
 * ## 三条硬约束（都是被服务端的脾气逼出来的）
 *
 * 1. **整包回写**：`POST /Items/{id}` 缺字段会清空，
 *    所以全程在原始 JSON 上做手术（见 [rewriteItemPeople]），绝不走我们的 data class。
 * 2. **首条回读校验 + POST/PUT 自适应**：Emby 4.x 认 `POST`，本仓库原有的「编辑元数据」用的是 `PUT`，
 *    而部分兼容服务端对其中一个会**回 200 却不落库**。所以第一条写完后立刻回读，
 *    指纹没变就整批切到另一种写法，并把首条补上 —— 用**一次额外请求**换掉「整批查无此事」的风险。
 * 3. **顺序 + 限速**：家用 Emby 上并发写元数据会互相打架（它的写是「先回执再落库」），
 *    宁可慢一点也不要丢写。[STEP_DELAY_MS] 是每条之间的呼吸间隔。
 *
 * ⚠️ 全程可取消：取消发生在**条目边界**（不会中断半个请求），已写入的照旧进账本。
 * 账本写入是**同步文件 IO**（见 [PersonMergeJournal]），所以在协程被取消后仍然写得进去。
 */
class PersonMerger(private val journal: PersonMergeJournal) {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  /** 条目之间的呼吸间隔：给服务端一点落库时间，也避免把自己打得太狠 */
  private val stepDelayMs = 120L

  /** 回读判「没写进去」时的复读间隔：有的服务端写入成功但**刚写完读到的还是旧数据** */
  private val readBackRetryMs = 800L

  /** 同名成员临时改名后，回读确认「改名已生效」的节奏（间隔 × 次数） */
  private val renameConfirmIntervalMs = 600L
  private val renameConfirmAttempts = 5

  /** 改名确认之后、动作品之前的额外缓冲：给服务端的人物索引一点落地时间 */
  private val renameSettleMs = 1000L

  /** 收集受影响条目时单页拉多少 */
  private val collectPageSize = 200

  /** 收集受影响条目的硬上限（异常数据兜底，防止把设备拖死） */
  private val collectMaxItems = 20_000

  // ══════════════════════════════════════════════════════════════════════
  // 收集
  // ══════════════════════════════════════════════════════════════════════

  /**
   * 收集所有**引用了 [memberIds] 中任意一个**的作品。
   *
   * 逐页拉而不是只拉第一页：Emby 的 `Limit` 在部分版本上会被截断，
   * 只取首页会让「这个演员的一半作品」悄悄漏掉 —— 那种半成品比整体失败更难发现。
   */
  suspend fun collectAffectedItems(
    server: EmbyServer,
    memberIds: Collection<String>,
  ): List<MergeItemRef> = withContext(Dispatchers.IO) {
    val out = LinkedHashMap<String, MergeItemRef>()
    for (memberId in memberIds) {
      var start = 0
      while (out.size < collectMaxItems) {
        currentCoroutineContext().ensureActive()
        val page = runCatching {
          EmbyClient.getItems(
            server = server,
            personIds = listOf(memberId),
            recursive = true,
            startIndex = start,
            limit = collectPageSize,
            fields = "People",
          )
        }.getOrNull() ?: break
        if (page.Items.isEmpty()) break
        page.Items.forEach { item ->
          val id = item.Id ?: return@forEach
          out.putIfAbsent(id, MergeItemRef(id, item.Name.orEmpty()))
        }
        start += page.Items.size
        if (page.TotalRecordCount > 0 && start >= page.TotalRecordCount) break
      }
    }
    out.values.toList()
  }

  // ══════════════════════════════════════════════════════════════════════
  // 合并
  // ══════════════════════════════════════════════════════════════════════

  /**
   * 把 [members] 全部并入 [canonical]：这些人的作品引用会统一改指保留项。
   *
   * @param onProgress 实时进度回调（在 IO 线程上被调；实现里只应做轻量的事，别写 UI 状态以外的东西）
   */
  suspend fun merge(
    server: EmbyServer,
    canonical: PersonRef,
    members: List<PersonRef>,
    onProgress: (MergeProgress) -> Unit = {},
  ): MergeOutcome = withContext(Dispatchers.IO) {
    if (members.none { it.id != canonical.id }) {
      return@withContext MergeOutcome(
        batchId = null, wrote = 0, skipped = 0, failed = 0, total = 0,
        verified = true, cancelled = false, firstError = "没有需要并入的条目",
      )
    }

    // ── 同名成员先「临时改名」（关键步骤，别删）──
    // Emby 保存条目时对 People 按 Name 重新解析 Person：保留项与被并项**同名**时，
    // 写「指向 A」会被服务端解析回 B，换 PUT 重写也一样 —— 同名合并永远落不了库
    // （实测：不同名合并成功、同名合并失败，根因就在这）。
    // 先把同名成员改成唯一临时名（`原名 #id尾号`），名字不再歧义，服务端解析必然
    // 落在保留项上。改名记录进账本，撤销时恢复原名。
    val renames = ArrayList<PersonRename>()
    val effectiveMembers = ArrayList<PersonRef>(members.size)
    for (m in members) {
      if (m.id == canonical.id) continue
      val sameName = m.name.trim().equals(canonical.name.trim(), ignoreCase = true)
      if (!sameName) {
        effectiveMembers += m
        continue
      }
      val tempName = tempPersonName(m)
      val outcome = runCatching { renamePerson(server, m.id, tempName) }
      if (outcome.isSuccess) {
        renames += PersonRename(m.id, m.name, tempName)
        effectiveMembers += m
      } else {
        // 改名都过不去，后续合并必然落不了库 —— 直接按失败计，跳过这个人
        return@withContext MergeOutcome(
          batchId = null, wrote = 0, skipped = 0, failed = 1, total = 0,
          verified = false, cancelled = false,
          firstError = "「${m.name}」临时改名失败：${outcome.exceptionOrNull()?.message ?: "未知错误"}",
        )
      }
    }
    val memberIds = effectiveMembers.map { it.id }.toSet()

    // ── 等改名在服务端「看得见」，再动作品（关键，别删）──
    // Emby 保存作品时按**名字**解析演员引用：改名刚提交、还没在服务端落定的
    // 那几秒里，同名实体仍然是两条，「指向保留项」的写入会被按旧名字解析回
    // 被并项 —— 实测表现：43 部作品全部回执成功，却一部都没写进去，回读里
    // 被并项还挂在演员表上（还带着改名后的新名字）。回读确认 + 稍作等待把
    // 这个窗口关掉。确认不了也不中止（可能只是读路径有缓存）—— 后面还有补扫兜底。
    if (renames.isNotEmpty()) {
      renames.forEach { r -> confirmRenameVisible(server, r.personId, r.tempName) }
      delay(renameSettleMs)
    }

    onProgress(MergeProgress(MergePhase.COLLECTING, canonical.name))
    val targets = collectAffectedItems(server, memberIds)

    val batchId = journal.newBatchId()
    val written = ArrayList<MergeWrittenItem>()
    val failures = ArrayList<MergeFailure>()
    var skipped = 0
    var cancelled = false
    // 还没确认服务端认哪种写法之前，先按 POST；首条校验判定「没写进去」就整批切 PUT
    var preferPut = false
    var modeConfirmed = false
    // 回读校验的尝试次数上限。一次读不到（网络抖、响应没带 People）不该把写法判断定死，
    // 所以后面几条还会接着试 —— 但也不能每条都读回，白搭一倍请求。
    var verifyAttempts = 0
    var verified = true
    var verifyDetail: String? = null
    // 本批实际被服务端接受的写入动词 —— 失败时随结果带出去，排障要看
    val verbsUsed = LinkedHashSet<String>()

    onProgress(MergeProgress(MergePhase.WRITING, canonical.name, 0, targets.size))

    try {
      for ((index, ref) in targets.withIndex()) {
        currentCoroutineContext().ensureActive()
        onProgress(
          MergeProgress(
            phase = MergePhase.WRITING,
            canonicalName = canonical.name,
            done = index + 1,
            total = targets.size,
            currentName = ref.name,
            wrote = written.size,
            failed = failures.size,
          ),
        )

        val attempt = runCatching { rewriteAndWrite(server, ref, canonical, memberIds, preferPut, verbsUsed) }
        val error = attempt.exceptionOrNull()
        if (error != null) {
          // 取消不是「失败」，要原样抛出去走取消路径
          if (error is CancellationException) throw error
          failures += MergeFailure(ref.id, ref.name, error.message ?: "未知错误")
        } else {
          val record = attempt.getOrThrow()
          if (record == null) {
            // 条目里已经只指向保留项了 —— 幂等跳过，不发请求
            skipped++
          } else {
            written += record
            if (!modeConfirmed && verifyAttempts < 3) {
              verifyAttempts++
              when (
                verifyWrite(
                  server, record.itemId, canonical.id, canonical.name, memberIds,
                  record.afterFingerprint,
                )
              ) {
                WriteVerdict.APPLIED -> modeConfirmed = true

                // 读不到 ≠ 没写进去。这时**什么也不做**：不改写法、不记失败，而且
                // 保持 modeConfirmed = false —— 下一条还会再校验一次，避免一次网络抖动
                // 就把整批的写法判断定死。把「读不到」当成「写失败」正是之前那个误报的来源。
                WriteVerdict.UNKNOWN -> Unit

                WriteVerdict.NOT_APPLIED -> {
                  modeConfirmed = true
                  // POST 回了成功但服务器上没变 → 换成 PUT 把这一条重写一遍
                  preferPut = true
                  val retry = runCatching {
                    rewriteAndWrite(server, ref, canonical, memberIds, preferPut = true, verbLog = verbsUsed)
                  }.getOrNull()
                  if (retry != null) {
                    written[written.lastIndex] = retry
                    if (verifyWrite(
                        server,
                        retry.itemId,
                        canonical.id,
                        canonical.name,
                        memberIds,
                        retry.afterFingerprint,
                      ) == WriteVerdict.NOT_APPLIED
                    ) {
                      verified = false
                      // 抓一份回读现场：万一仍失败，用户把这句发回来就能定位原因，
                      // 不用再来回猜「服务端到底存了什么」
                      verifyDetail = describeReadBack(server, retry.itemId, retry.itemName, canonical, memberIds)
                    }
                  }
                  // retry == null：这条**已经没有任何可改的了** —— 恰恰证明先前那次其实写进去了，
                  // 只是校验没能认出来。此时保持 preferPut 不动，也不记失败。
                }
              }
            }
          }
        }
        delay(stepDelayMs)
      }
    } catch (e: CancellationException) {
      cancelled = true
    } catch (e: Throwable) {
      failures += MergeFailure("", "", e.message ?: "未知错误")
    }

    // ── 补扫：把「第一遍被服务端退回」的作品再写一遍 ──
    // 只在「涉及临时改名」或「第一遍校验没过」时跑：重新查一遍还有哪些作品仍引用
    // 被并项（服务器按人查作品），把那些条目**再改写一次**。这一遍离改名已经隔了
    // 整个主循环的时间，名字解析的歧义窗口早就关了 —— 第一遍撞上窗口被退回的，
    // 这里能救回来。
    var repaired = 0
    if (!cancelled && memberIds.isNotEmpty() && (renames.isNotEmpty() || !verified)) {
      val stillAffected = runCatching { collectAffectedItems(server, memberIds) }.getOrDefault(emptyList())
      if (stillAffected.isEmpty()) {
        // 没有任何作品再引用被并项 —— 第一遍其实都写进去了（先前的判负是回读
        // 撞上了索引/缓存延迟），把误报警撤掉。
        verified = true
      } else {
        var sampled = false
        for (ref in stillAffected) {
          currentCoroutineContext().ensureActive()
          val attempt = runCatching { rewriteAndWrite(server, ref, canonical, memberIds, preferPut, verbsUsed) }
          val error = attempt.exceptionOrNull()
          when {
            error is CancellationException -> throw error
            error != null -> failures += MergeFailure(ref.id, ref.name, error.message ?: "未知错误")
            else -> {
              val again = attempt.getOrThrow() ?: continue // 已归位，幂等跳过
              // 已有这条的记录就只更新指纹：**保留最初的 beforePeople**，
              // 撤销才能回到真正的原始状态（而不是两遍之间的中间态）。
              val idx = written.indexOfFirst { it.itemId == ref.id }
              if (idx >= 0) {
                written[idx] = again.copy(beforePeople = written[idx].beforePeople)
              } else {
                written += again
              }
              repaired++
              // 抽查补写后的第一条，拿最新的判定与失败现场
              if (!sampled) {
                sampled = true
                when (
                  verifyWrite(
                    server, again.itemId, canonical.id, canonical.name, memberIds,
                    again.afterFingerprint,
                  )
                ) {
                  WriteVerdict.APPLIED -> verified = true
                  WriteVerdict.NOT_APPLIED -> {
                    verified = false
                    verifyDetail = describeReadBack(server, again.itemId, again.itemName, canonical, memberIds)
                  }
                  WriteVerdict.UNKNOWN -> Unit
                }
              }
            }
          }
          delay(stepDelayMs)
        }
      }
    }

    val batch = PersonMergeBatch(
      id = batchId,
      serverId = server.id,
      canonicalId = canonical.id,
      canonicalName = canonical.name,
      memberIds = memberIds.toList(),
      memberNames = effectiveMembers.map { it.name },
      createdAt = System.currentTimeMillis(),
      state = if (cancelled || failures.isNotEmpty()) MERGE_STATE_PARTIAL else MERGE_STATE_DONE,
      items = written,
      failures = failures,
      verified = verified,
      renames = renames,
    )
    // 取消路径下协程已被取消，但这两个都是同步文件 IO，照样写得进去 —— 这正是要的
    journal.save(batch)
    journal.prune()

    onProgress(
      MergeProgress(
        phase = MergePhase.DONE,
        canonicalName = canonical.name,
        done = targets.size,
        total = targets.size,
        wrote = written.size,
        failed = failures.size,
      ),
    )

    MergeOutcome(
      batchId = batchId,
      wrote = written.size,
      skipped = skipped,
      failed = failures.size,
      total = targets.size,
      verified = verified,
      cancelled = cancelled,
      firstError = failures.firstOrNull()?.error,
      verifyDetail = verifyDetail,
      repaired = repaired,
      writeVerbs = verbsUsed.joinToString("/").ifEmpty { null },
    )
  }

  /**
   * 改写并回写一条作品。
   *
   * @param verbLog 记录实际被服务端接受的写入动词（POST/PUT），排障用；可为 null
   * @return 写成功的记录；**条目本来就已经归位**（没有任何改动）时返回 null
   */
  private fun rewriteAndWrite(
    server: EmbyServer,
    ref: MergeItemRef,
    canonical: PersonRef,
    memberIds: Set<String>,
    preferPut: Boolean,
    verbLog: MutableSet<String>? = null,
  ): MergeWrittenItem? {
    val raw = EmbyClient.getItemRawJson(server, ref.id)
    val item = json.parseToJsonElement(raw).jsonObject
    // 撤销要用「改写前的 People 原文」—— 必须在这里抓，回写之后就再也拿不到原始值了
    val beforePeople = (item["People"] as? JsonArray)?.toString() ?: return null
    val rewritten = rewriteItemPeople(item, canonical.id, canonical.name, memberIds) ?: return null
    verbLog?.add(EmbyClient.updateItemRawJson(server, ref.id, rewritten.toString(), preferPut = preferPut))
    return MergeWrittenItem(
      itemId = ref.id,
      itemName = ref.name,
      beforePeople = beforePeople,
      afterFingerprint = peopleFingerprint(rewritten),
    )
  }

  /** 同名成员的临时名：`原名 #id尾号` —— 带 Id 尾号保证唯一，搜索时也能看出它是谁 */
  private fun tempPersonName(m: PersonRef): String =
    "${m.name} #${m.id.takeLast(6).lowercase()}"

  /**
   * 改一个 Person 的名字。同样是「读原文 → 只动 Name/SortName → 整包回写」，
   * 绝不经过 data class（缺字段会被清空的教训在这也得记着）。
   */
  private fun renamePerson(server: EmbyServer, personId: String, newName: String) {
    val raw = EmbyClient.getItemRawJson(server, personId)
    val item = json.parseToJsonElement(raw).jsonObject
    val mutable = item.toMutableMap()
    mutable["Name"] = JsonPrimitive(newName)
    mutable["SortName"] = JsonPrimitive(newName)
    EmbyClient.updateItemRawJson(server, personId, JsonObject(mutable).toString())
  }

  /**
   * 回读确认「临时改名已经在服务器上生效」：读到 Person 的名字就是 [expectedName] 为止。
   *
   * 改名 POST 回 200 ≠ 改名已落定 —— Emby 的人物索引是异步刷新的。**在那几秒里
   * 去写作品就是白写**（服务端按名字解析演员引用，同名实体还是两条，写入会被解析回
   * 被并项原样退回 —— 实测同名合并 43 部作品全军覆没就是这个窗口造成的）。
   *
   * @return 是否确认到了。确认不了也不当失败处理：可能只是读路径有缓存，
   *   后面还有补扫兜底；真没生效时补扫也会失败，结果信息里会说清楚。
   */
  private suspend fun confirmRenameVisible(
    server: EmbyServer,
    personId: String,
    expectedName: String,
  ): Boolean {
    repeat(renameConfirmAttempts) {
      val name = runCatching {
        val o = json.parseToJsonElement(EmbyClient.getItemRawJson(server, personId)).jsonObject
        (o["Name"] as? JsonPrimitive)?.contentOrNull
      }.getOrNull()
      if (name?.trim()?.equals(expectedName.trim(), ignoreCase = true) == true) return true
      delay(renameConfirmIntervalMs)
    }
    return false
  }

  /**
   * 回读判负时抓一份「服务器到底存了什么」的现场摘要 —— 用于失败提示与排障。
   *
   * ⚠️ 文案原则：**说人话**。不写「指纹」「People」这类内部词；「名字[ID末4位]」
   * 的格式要在括号里解释清楚 —— 用户看不懂的排障信息等于没有信息。
   */
  private fun describeReadBack(
    server: EmbyServer,
    itemId: String,
    itemName: String,
    canonical: PersonRef,
    memberIds: Set<String>,
  ): String {
    val raw = runCatching { EmbyClient.getItemRawJson(server, itemId) }.getOrNull()
      ?: return "抽查《$itemName》时读取服务器失败"
    val item = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
      ?: return "抽查《$itemName》时服务器返回了无法解析的内容"
    val people = item["People"] as? JsonArray
      ?: return "抽查《$itemName》时服务器没有返回演员表"

    fun idOf(o: JsonObject): String? = (o["Id"] as? JsonPrimitive)?.contentOrNull
    fun nameOf(o: JsonObject): String? = (o["Name"] as? JsonPrimitive)?.contentOrNull

    val objs = people.filterIsInstance<JsonObject>()
    val ids = objs.mapNotNull { idOf(it) }
    val sb = StringBuilder("抽查《").append(itemName).append("》：服务器上这部作品的演员表共 ")
      .append(people.size).append(" 人")
    if (memberIds.any { m -> ids.any { it.equals(m, ignoreCase = true) } }) {
      sb.append("，但被并入的演员仍在演员表里 —— 这次改动确实没有写进服务器")
    }
    if (ids.any { it.equals(canonical.id, ignoreCase = true) }) sb.append("（保留项在表中）")
    val otherSameName = objs.any { o ->
      nameOf(o)?.trim()?.equals(canonical.name.trim(), ignoreCase = true) == true &&
        idOf(o)?.equals(canonical.id, ignoreCase = true) != true
    }
    if (otherSameName) sb.append("；演员表里出现了与保留项同名但 Id 不同的条目（服务器把引用解析到了另一条同名人上）")
    if ((item["LockData"] as? JsonPrimitive)?.booleanOrNull == true) {
      sb.append("；注意：这部作品的元数据处于「已锁定」状态，锁定会阻止修改，请先在 Emby 里解锁再试")
    }
    sb.append("。演员表前几人（格式：名字[ID末4位]）：").append(
      objs.take(4).joinToString("、") { o ->
        "${nameOf(o) ?: "？"}[${idOf(o)?.takeLast(4) ?: "？？？"}]"
      },
    )
    return sb.toString()
  }

  /**
   * 回读校验的三种结论。
   *
   * ⚠️ [UNKNOWN] 必须和「没写进去」分开。把两者混为一谈会造成**最讨厌的那种误报**：
   * 数据明明写对了，界面却警告「可能没写入」，用户白跑一趟作品页。
   */
  private enum class WriteVerdict {
    /** 服务器上确实已经是改写后的样子 */
    APPLIED,

    /** 读到了条目，但**成员还挂在上面 / 保留项不在** —— 这次写入没有生效 */
    NOT_APPLIED,

    /** 读不到（网络失败、条目已删、返回体不是 JSON、这个端点没回 People）—— 信息不足，不下结论 */
    UNKNOWN,
  }

  /**
   * 回读一条作品，判断这次改写到底落库了没有（带一次延迟复读）。
   *
   * 第一次判「没写进去」时**不急着下结论**：有一种很常见的情况是写入其实成功了，
   * 只是**刚写完就读，服务器还端着旧数据**（内部缓存 / 异步落库 / 前置代理）。
   * 隔 800ms 再读一次，仍不行才算真的没写进去。
   */
  private suspend fun verifyWrite(
    server: EmbyServer,
    itemId: String,
    canonicalId: String,
    canonicalName: String,
    memberIds: Set<String>,
    expectedFingerprint: String,
  ): WriteVerdict {
    val first = verifyWriteOnce(server, itemId, canonicalId, canonicalName, memberIds, expectedFingerprint)
    if (first != WriteVerdict.NOT_APPLIED) return first
    delay(readBackRetryMs)
    return verifyWriteOnce(server, itemId, canonicalId, canonicalName, memberIds, expectedFingerprint)
  }

  /**
   * 单次回读判定。
   *
   * 判据分两层：
   *  1. **指纹**（快）：完全一致直接算过；
   *  2. **目标是否达成**（稳）：指纹对不上也未必是没写 —— 服务器可能重排了 People、
   *     补了字段、或换了 Id 大小写。真正要确认的是「成员一个都不剩，保留项已在」，
   *     这个判据对一切等价的表示形式都不敏感。
   *
   * 「保留项已在」有两种认定：**Id 就是它**，或**至少名字是它** —— 有的服务端保存时会
   * 按 `Name` 重新解析 Person（去重场景里同名实体不止一条，解析到哪条不一定），
   * 此时 Id 对不上但显示效果与撤销能力都不受影响，不该算失败。
   */
  private fun verifyWriteOnce(
    server: EmbyServer,
    itemId: String,
    canonicalId: String,
    canonicalName: String,
    memberIds: Set<String>,
    expectedFingerprint: String,
  ): WriteVerdict {
    val raw = runCatching { EmbyClient.getItemRawJson(server, itemId) }.getOrNull()
      ?: return WriteVerdict.UNKNOWN
    val item = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
      ?: return WriteVerdict.UNKNOWN
    val people = item["People"] as? JsonArray ?: return WriteVerdict.UNKNOWN
    if (peopleFingerprint(item) == expectedFingerprint) return WriteVerdict.APPLIED

    val ids = ArrayList<String>(people.size)
    val names = ArrayList<String>(people.size)
    for (el in people) {
      val o = el as? JsonObject ?: continue
      (o["Id"] as? JsonPrimitive)?.contentOrNull?.let { ids.add(it) }
      (o["Name"] as? JsonPrimitive)?.contentOrNull?.let { names.add(it) }
    }
    val membersGone = memberIds.none { member -> ids.any { it.equals(member, ignoreCase = true) } }
    val canonicalIn = ids.any { it.equals(canonicalId, ignoreCase = true) } ||
      names.any { it.trim().equals(canonicalName.trim(), ignoreCase = true) }
    return if (membersGone && canonicalIn) WriteVerdict.APPLIED else WriteVerdict.NOT_APPLIED
  }

  // ══════════════════════════════════════════════════════════════════════
  // 撤销
  // ══════════════════════════════════════════════════════════════════════

  /**
   * 撤销一批合并：把每条作品的 `People` 写回**改写前的原文**。
   *
   * 两条安全阀：
   *  1. **只改 People**，拿当前 JSON 做底、只替换 `People` 键 —— 不用账本里那份旧快照整包覆盖，
   *     否则会把这段时间里别处做的改动（换了海报、改了简介）一起回滚掉。
   *  2. **先比指纹**：当前 People 若不等于我们当初写下去的，说明这条在这之后被别人动过，
   *     **跳过并计数**，不硬覆盖。
   */
  suspend fun undo(
    server: EmbyServer,
    batch: PersonMergeBatch,
    onProgress: ((Int, Int) -> Unit)? = null,
  ): UndoOutcome = withContext(Dispatchers.IO) {
    var restored = 0
    var skipped = 0
    var failed = 0
    var firstError: String? = null
    var renamesRestored = 0

    // 先恢复临时改名（如果有）：名字回到原样，后面恢复作品 People 时
    // 服务端按名字解析才有意义。恢复失败不阻断 —— 作品照常恢复，如实计数。
    // ⚠️ 同名合并的撤销有一个服务端行为决定的硬限制：同一条作品里「保留项」与
    // 「被并项」的引用名字相同，按名字解析的服务器可能把两条归到同一个人身上 ——
    // 名字与角色都能恢复，但「哪条指向谁」不保证逐一精确还原。
    for (r in batch.renames) {
      currentCoroutineContext().ensureActive()
      val outcome = runCatching { renamePerson(server, r.personId, r.originalName) }
      if (outcome.isSuccess) {
        renamesRestored++
      } else {
        failed++
        if (firstError == null) {
          firstError = "恢复「${r.originalName}」原名失败：${outcome.exceptionOrNull()?.message ?: "未知错误"}"
        }
      }
      delay(stepDelayMs)
    }

    for ((index, record) in batch.items.withIndex()) {
      currentCoroutineContext().ensureActive()
      onProgress?.invoke(index + 1, batch.items.size)

      val current = runCatching {
        json.parseToJsonElement(EmbyClient.getItemRawJson(server, record.itemId)).jsonObject
      }.getOrNull()
      // 闸门要**新旧两种指纹都认**：账本里存的可能是升级前那一版算法算出来的值，
      // 只认新值会把历史批次全部误判成「被别人动过」（见 legacyPeopleFingerprint）。
      val matches = current != null &&
        (
          peopleFingerprint(current) == record.afterFingerprint ||
            legacyPeopleFingerprint(current) == record.afterFingerprint
          )
      if (!matches) {
        // 读不到，或这条已经被别处改过 —— 都不动它
        skipped++
        continue
      }
      val currentItem = current
      val before = runCatching { json.parseToJsonElement(record.beforePeople) as? JsonArray }.getOrNull()
      if (before == null) {
        skipped++
        continue
      }
      val reverted = JsonObject(currentItem.toMutableMap().apply { put("People", before) })
      val outcome = runCatching { EmbyClient.updateItemRawJson(server, record.itemId, reverted.toString()) }
      if (outcome.isSuccess) {
        restored++
      } else {
        failed++
        if (firstError == null) firstError = outcome.exceptionOrNull()?.message
      }
      delay(stepDelayMs)
    }

    if (restored > 0 || renamesRestored > 0) journal.markReverted(batch.id)
    UndoOutcome(
      restored = restored,
      skipped = skipped,
      failed = failed,
      firstError = firstError,
      renamesRestored = renamesRestored,
    )
  }

  // ══════════════════════════════════════════════════════════════════════
  // 孤立演员
  // ══════════════════════════════════════════════════════════════════════

  /**
   * 在 [candidateIds] 里找出**已经没有任何作品**的演员。
   *
   * ⚠️ 只按 Id 逐个问服务端要作品数（一次请求一个人，`Limit=1` 只读 `TotalRecordCount`），
   * **不用** `getPersonWorkCounts`：那个是整库逐页拉，库大了会整体放弃、返回空表，
   * 拿空表判孤立会把**所有**候选都误判成孤立。
   *
   * ⚠️ 调用方要把范围限在「本次合并产生过的变体」上：全库扫会把「暂时没作品但用户想留着」
   * 的演员也列出来，那是越权。
   */
  suspend fun findOrphans(
    server: EmbyServer,
    candidateIds: Collection<String>,
    onProgress: ((Int, Int) -> Unit)? = null,
  ): List<String> = withContext(Dispatchers.IO) {
    val ids = candidateIds.toList()
    val orphans = ArrayList<String>()
    for ((index, id) in ids.withIndex()) {
      currentCoroutineContext().ensureActive()
      onProgress?.invoke(index + 1, ids.size)
      if (EmbyClient.getPersonWorkCount(server, id) == 0) orphans += id
    }
    orphans
  }

  /**
   * 清理孤立演员：**不自己去删 Person**，而是触发 Emby 自己的「刷新人员」任务，
   * 由服务端回收已经没人引用的条目。
   *
   * 为什么不用 `DELETE /Items/{id}`：那是不可逆的，而且如果服务端其实还引用了它
   * （例如某个我们没扫到的合集 / 播放列表），删掉就是直接损坏数据。
   * 走服务端任务**零风险**，代价只是异步生效。
   *
   * 任务匹配顺序：`Key=RefreshPeople` → 名字含「人员 / people」→ `Key=RefreshLibrary`。
   * 三个都没有就返回 [OrphanCleanOutcome.noTask]，**绝不瞎调一个任务**。
   */
  suspend fun cleanOrphans(
    server: EmbyServer,
    candidateIds: Collection<String>,
    onProgress: ((Int, Int) -> Unit)? = null,
  ): OrphanCleanOutcome = withContext(Dispatchers.IO) {
    val orphans = findOrphans(server, candidateIds, onProgress)
    if (orphans.isEmpty()) return@withContext OrphanCleanOutcome(null, 0)

    val tasks = EmbyClient.getScheduledTasks(server)
    val task = tasks.firstOrNull { it.Key.equals("RefreshPeople", ignoreCase = true) }
      ?: tasks.firstOrNull { t ->
        val n = t.Name.orEmpty()
        n.contains("人员") || n.contains("people", ignoreCase = true)
      }
      ?: tasks.firstOrNull { it.Key.equals("RefreshLibrary", ignoreCase = true) }
    val taskId = task?.Id
    if (task == null || taskId == null) {
      return@withContext OrphanCleanOutcome(null, orphans.size, noTask = true)
    }
    val triggered = EmbyClient.runScheduledTask(server, taskId)
    OrphanCleanOutcome(
      taskName = if (triggered) (task.Name ?: task.Key) else null,
      orphanCount = orphans.size,
      noTask = !triggered,
    )
  }
}
