package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** 整批都写成功了 */
const val MERGE_STATE_DONE = "DONE"

/** 部分成功（有失败条目）或被用户中途取消 */
const val MERGE_STATE_PARTIAL = "PARTIAL"

/** 已经被撤回过 */
const val MERGE_STATE_REVERTED = "REVERTED"

/**
 * 一条被改写的作品。
 *
 * @param beforePeople 改写**之前**的 `People` 数组原文（JSON 字串）。撤销就是把它原样写回去 ——
 *   所以必须存原文而不是「反向计算」，跨角色的情况反推不回来。
 * @param afterFingerprint 改写**之后**的 People 指纹（见 [peopleFingerprint]）。撤销前拿它比对
 *   当前服务器上的值，确认「这条自从我们改过之后没被别人动过」，没有它就不敢盲写。
 */
@Serializable
data class MergeWrittenItem(
  val itemId: String,
  val itemName: String,
  val beforePeople: String,
  val afterFingerprint: String,
)

/** 一条尝试过但失败的条目（网络 / 权限 / 数据异常），留给用户看而不是静默吞掉 */
@Serializable
data class MergeFailure(
  val itemId: String,
  val itemName: String,
  val error: String,
)

/**
 * 一次「临时改名」记录。
 *
 * 为什么需要它：Emby 保存条目时对 People **按名字重新解析 Person** ——
 * 保留项与被并项**同名**时（去重场景最常见的情形），写「指向 A」会被解析回 B，
 * 合并永远落不了库（实测：不同名合并成功、同名合并失败）。
 * 破解办法是先把被并项改成唯一临时名（`原名 #id尾号`），让名字不再歧义；
 * 撤销时按这条记录把原名改回去。
 */
@Serializable
data class PersonRename(
  val personId: String,
  val originalName: String,
  val tempName: String,
)

/**
 * 一次合并 = 一个批次。撤销是按批次整体回的。
 *
 * @param memberIds 被并掉的那些 Person Id（不含保留项）
 * @param verified 抽样回读有没有通过。false 表示「服务器回了成功，但回读发现没生效」——
 *   这种情况必须显式告诉用户，不能假装成功（部分兼容服务端会回 200 却不落库）。
 */
@Serializable
data class PersonMergeBatch(
  val id: String,
  val serverId: Long,
  val canonicalId: String,
  val canonicalName: String,
  val memberIds: List<String> = emptyList(),
  val memberNames: List<String> = emptyList(),
  val createdAt: Long,
  val state: String = MERGE_STATE_DONE,
  val items: List<MergeWrittenItem> = emptyList(),
  val failures: List<MergeFailure> = emptyList(),
  val verified: Boolean = true,
  /** 合并前做过的临时改名（都是与保留项同名的被并项）；撤销时按它恢复原名 */
  val renames: List<PersonRename> = emptyList(),
) {
  /** 合并前有这么多条，之后只剩 1 条 */
  val mergedFrom: Int get() = memberIds.size + 1
  val touchedCount: Int get() = items.size
  /** 有可撤销的东西：改写过作品，**或者**只是做过临时改名（改名后一个作品都没写成功也要能撤） */
  val canUndo: Boolean get() = state != MERGE_STATE_REVERTED && (items.isNotEmpty() || renames.isNotEmpty())
}

/**
 * 合并撤销账本 —— 落在 `filesDir/person_merge/`，一个批次一个 JSON 文件。
 *
 * ⚠️ **为什么不放 SharedPreferences**：一条完整条目的 `People` 数组原文有几 KB，
 * 合并一个 200 部作品的演员就可能攒到几百 KB～几 MB ——
 * 塞进偏好文件会拖垮它（那个文件是全量读写的）。
 *
 * ⚠️ 所有方法都是**同步阻塞**的，且**刻意不是** suspend：合并被用户取消时协程会被取消，
 * 挂起函数写不进文件，但这批已经写出去的改动必须留下账本（否则用户撤销不了）。
 * 调用方保证在 IO 线程上跑（见 `PersonMerger`）。
 */
class PersonMergeJournal(private val dir: File) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val seq = AtomicInteger(0)

  /** 生成批次 Id：时间戳 + 进程内自增 + 随机尾巴，保证同一秒内多次合并也不撞名 */
  fun newBatchId(): String {
    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val n = seq.incrementAndGet()
    val tail = (System.nanoTime() and 0xFFFF).toString(16).padStart(4, '0')
    return "$ts-$n$tail"
  }

  private fun fileOf(id: String) = File(dir, "$id.json")

  /** 写入 / 覆盖一个批次 */
  fun save(batch: PersonMergeBatch) {
    runCatching {
      if (!dir.exists()) dir.mkdirs()
      // 先写临时文件再改名：中途被杀不会留下半个 JSON（下次读会当成损坏文件丢掉）
      val tmp = File(dir, "${batch.id}.tmp")
      tmp.writeText(json.encodeToString(batch))
      if (!tmp.renameTo(fileOf(batch.id))) {
        fileOf(batch.id).writeText(tmp.readText())
        tmp.delete()
      }
    }
  }

  fun get(id: String): PersonMergeBatch? = runCatching {
    val f = fileOf(id)
    if (!f.exists()) null else json.decodeFromString<PersonMergeBatch>(f.readText())
  }.getOrNull()

  /** 全部批次，**按时间倒序**（最近的排最前，UI 直接用） */
  fun list(): List<PersonMergeBatch> = runCatching {
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
    files
      .mapNotNull { f -> runCatching { json.decodeFromString<PersonMergeBatch>(f.readText()) }.getOrNull() }
      .sortedByDescending { it.createdAt }
  }.getOrDefault(emptyList())

  fun count(): Int = runCatching {
    dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0
  }.getOrDefault(0)

  /** 标记某批次已被撤销（只改状态，条目留着当历史记录） */
  fun markReverted(id: String) {
    val batch = get(id) ?: return
    save(batch.copy(state = MERGE_STATE_REVERTED))
  }

  /** 该批次里所有被并掉的 Person Id（「清理孤立演员」默认只在这个范围里找） */
  fun knownMemberIds(): Set<String> =
    list().flatMapTo(HashSet()) { it.memberIds }

  /**
   * 清理旧账本：只保留最近 [keep] 批、且不超过 [maxAgeDays] 天的。
   *
   * 为什么不无限留：账本里存的是**完整 People 原文**，攒久了很占空间；
   * 而「合并完过了一个月还想撤销」本来就极少见。
   */
  fun prune(keep: Int = 20, maxAgeDays: Long = 30) {
    runCatching {
      val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 3600L * 1000L
      list().forEachIndexed { index, batch ->
        if (index >= keep || batch.createdAt < cutoff) fileOf(batch.id).delete()
      }
    }
  }
}
