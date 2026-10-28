package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkFileType
import app.marlboroadvance.mpvex.domain.network.type
import app.marlboroadvance.mpvex.preferences.NetworkGroupMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * 类型分组的**固定顺序**，同时是段头文案的唯一来源（列表段头与网格段头都读它）。
 *
 * 需求指定：文件夹恒在最顶层，其下 视频 → 图片 → 其他文件。
 *
 * ⚠️ 与 `NetworkSortType.Type` 的排序权重**不是同一套**：那个是「按类型排序」这个
 *    排序维度用的字典序（文件夹 → 图片 → 视频 → 其他，见 `NetworkBrowserViewModel.typeRank`），
 *    这里只服务「强制类型分组」。两者不要合并 —— 合并会顺手改掉「按类型排序」的老行为。
 */
internal val NETWORK_TYPE_GROUP_ORDER: List<Pair<NetworkFileType, String>> =
  listOf(
    NetworkFileType.FOLDER to "文件夹",
    NetworkFileType.VIDEO to "视频",
    NetworkFileType.PHOTO to "图片",
    NetworkFileType.OTHER to "其他文件",
  )

/**
 * 类型段头文案：**唯一取词点**（列表段头 / 网格段头 / 将来任何地方都读它）。
 *
 * 这样段序改了文案跟着改，不会出现「顺序换成视频在前、标题还写着图片」；
 * 也保证「关闭类型分组」时老段头文案不变 —— [NETWORK_TYPE_GROUP_ORDER] 里
 * 的四个标签与老的硬编码字符串逐字一致。
 */
fun networkTypeGroupLabel(type: NetworkFileType): String =
  NETWORK_TYPE_GROUP_ORDER.firstOrNull { it.first == type }?.second ?: type.displayName

/**
 * 一个类型段：类型 + 段头文案 + 段内条目（顺序 = 传入顺序，本函数不重排）。
 *
 * 空的类型**不产出** —— 段头下面空着比没有更糟。
 */
data class NetworkTypeBucket(
  val type: NetworkFileType,
  val label: String,
  val files: List<NetworkFile>,
)

/**
 * 把**已排好序**的列表按类型切成连续段（顺序见 [NETWORK_TYPE_GROUP_ORDER]）。
 *
 * 关键点：只是 partition，**不重新排序**。所以每一段内部的顺序就是全局排序的结果，
 * 即「各类型内部独立按当前排序键排序」——这正好满足「互不影响」：
 * 类型之间不会因为对方的条目多少而改变先后，段内也不受其他类型干扰。
 *
 * 附带的好处：随机排序下也成立。均匀洗牌后按类型 partition，每类内部的相对顺序
 * 仍是一个均匀随机子序列，等价于「每类各自洗牌」，不需要为组合场景写特殊分支。
 *
 * @return 只含**非空**类型段；全空返回空列表。
 */
fun groupNetworkFilesByType(files: List<NetworkFile>): List<NetworkTypeBucket> {
  if (files.isEmpty()) return emptyList()
  // groupBy 内部是 LinkedHashMap ⇒ 每段内部保持传入顺序
  val byType = files.groupBy { it.type() }
  return NETWORK_TYPE_GROUP_ORDER.mapNotNull { (type, label) ->
    byType[type]?.takeIf { it.isNotEmpty() }?.let { NetworkTypeBucket(type, label, it) }
  }
}

/**
 * 一个时间桶：`title` + 桶内条目（顺序 = 传进来的顺序，本函数**不重排**）。
 *
 * `title == null` 表示**不画表头**，有两种来源：
 * 1. 没开分组（[NetworkGroupMode.None]）—— 整段就是一个匿名桶；
 * 2. 开了分组但只有一个非空桶 —— 一行标题框住全部条目纯属噪音。
 */
data class NetworkPeriodGroup(
  val title: String?,
  val files: List<NetworkFile>,
)

/** 「时间未知」桶的标题。恒排最后，不随升/降序翻转。 */
internal const val UNKNOWN_PERIOD_TITLE = "未知时间"

/**
 * 把**已排好序**的列表按年 / 年月切成若干时间桶。
 *
 * ## 与排序的分工（这是整套方案的核心约定）
 *
 * 本函数**只负责切堆与堆的先后**，桶内顺序完全沿用 `files` 的现有顺序（调用方传进来的
 * 已经是 `networkFileComparator` 排好的结果）。所以「分组」和「排序」不会互相覆盖：
 * 换排序只改桶内，换分组只改切法。
 *
 * ## 桶的先后
 *
 * - 默认 **新 → 旧**（时间轴的自然阅读方向），与 `sortType` 无关；
 * - 只有 `bucketAscending = true` 时才翻成旧 → 新。调用方传它的条件应当是
 *   「用户明确选了时间升序」（`sortType == Date && order == Ascending`）——
 *   否则会出现「桶是新的在前、桶内却是旧的在前」的自相矛盾。
 *
 * ## 边界
 *
 * - `lastModified <= 0`（SMB / FTP 的部分条目、某些 WebDAV 实现返回 0）归入
 *   **「未知时间」**桶，且**恒排最后**：它不参与时间比较，翻转方向时也不该跑到最前
 *   —— 那会让人误以为它是最新的。
 * - 只有一个非空桶时返回 `title = null`（不画表头）。
 * - 时区取 `ZoneId.systemDefault()`：分桶口径必须和屏幕上显示的日期一致，
 *   而显示那边用的是本机时区。用 `java.time` 而不是「除以 365 天」那种算法 ——
 *   后者在闰年 / 夏令时 / 跨年边界上会算错。
 * - 未来时间戳（设备时钟偏移）自然落进最新的那个桶，不单独造「未来」桶。
 *
 * 纯函数、O(n)；调用方用 `remember(files, mode, ascending)` 包住，别在重组里重算。
 */
fun groupNetworkFilesByPeriod(
  files: List<NetworkFile>,
  mode: NetworkGroupMode,
  bucketAscending: Boolean,
): List<NetworkPeriodGroup> {
  if (files.isEmpty()) return emptyList()
  if (!mode.isActive) return listOf(NetworkPeriodGroup(null, files))

  val zone = ZoneId.systemDefault()
  val (dated, undated) = files.partition { it.lastModified > 0 }

  val buckets: List<NetworkPeriodGroup> =
    when (mode) {
      NetworkGroupMode.Year -> {
        val map = LinkedHashMap<Int, MutableList<NetworkFile>>()
        dated.forEach { file -> map.getOrPut(yearOf(file.lastModified, zone)) { mutableListOf() }.add(file) }
        val keys = if (bucketAscending) map.keys.sorted() else map.keys.sortedDescending()
        keys.map { NetworkPeriodGroup("$it 年", map.getValue(it)) }
      }

      NetworkGroupMode.YearMonth -> {
        val map = LinkedHashMap<YearMonth, MutableList<NetworkFile>>()
        dated.forEach { file -> map.getOrPut(monthOf(file.lastModified, zone)) { mutableListOf() }.add(file) }
        val keys = if (bucketAscending) map.keys.sorted() else map.keys.sortedDescending()
        keys.map { NetworkPeriodGroup("${it.year} 年 ${it.monthValue} 月", map.getValue(it)) }
      }

      // isActive 已挡掉，这里只是让 when 穷尽
      NetworkGroupMode.None -> listOf(NetworkPeriodGroup(null, files))
    }

  val all = if (undated.isEmpty()) buckets else buckets + NetworkPeriodGroup(UNKNOWN_PERIOD_TITLE, undated)
  return if (all.size <= 1) listOf(NetworkPeriodGroup(null, all.first().files)) else all
}

private fun yearOf(epochMillis: Long, zone: ZoneId): Int =
  Instant.ofEpochMilli(epochMillis).atZone(zone).year

private fun monthOf(epochMillis: Long, zone: ZoneId): YearMonth =
  YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(zone))
