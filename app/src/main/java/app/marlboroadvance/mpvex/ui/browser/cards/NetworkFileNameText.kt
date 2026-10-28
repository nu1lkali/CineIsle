package app.marlboroadvance.mpvex.ui.browser.cards

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 列表行的**完整日期**（含年份），定长补零：`2026-09-07`。
 *
 * 为什么是 `yyyy-MM-dd` 而不是 `2026/9/7` 或「2026年9月7日」：
 * - **定长**（10 字符）⇒ 每一行的日期起点对齐，扫视时天然是一列，不需要再单独排一列日期；
 * - 纯 ASCII 数字 ⇒ 在任何 locale 下都不会被翻成「9月7日」这种和文件名混在一起的中文；
 * - 大端序（年→月→日）⇒ 字符串序 = 时间序，与「按时间排序 / 按年分组」的心智一致。
 *
 * ⚠️ 用 `Locale.US` 固定格式：`SimpleDateFormat` 的模式字母本身不受 locale 影响，
 *    但 locale 会影响数字系统（如阿拉伯语环境会把 0-9 换成别的数字），统一走 US 更稳。
 */
internal fun formatNetworkFullDate(timestamp: Long): String =
  SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))

/**
 * 只到月日：`09-07`。用在「按年份分组」的行内 —— 组头已经写了年份，
 * 行内再来一次是重复，省下的 5 个字符正好还给文件名。
 *
 * 仍然补零定长（5 字符），所以同一个组里每一行的左边界还是齐的。
 */
internal fun formatNetworkMonthDay(timestamp: Long): String =
  SimpleDateFormat("MM-dd", Locale.US).format(Date(timestamp))

/**
 * 列表行标题：**日期前缀 + 文件名**，同一个 `Text` 内两种样式。
 *
 * ## 为什么日期放前缀，而不是后缀
 *
 * 标题是 `maxLines` + `Ellipsis`。放后缀的话，名字一长**先被截掉的正是日期** ——
 * 等于「有日期」这件事在长名下不成立。放前缀则相反：日期永远是第一个被画出来的，
 * 无论名字多长都在；被截断的只是文件名尾部。
 *
 * ## 为什么不用 `Row(日期, 名字)`
 *
 * 拆成两个 `Text` 后，名字的第二行会从**整行起点**开始，视觉上和日期那一列的
 * 左边界对不齐（悬挂缩进要自己算，还得跟着字号/密度变）。单 `AnnotatedString`
 * 则由排版引擎自然处理折行，第二行自动回到行首 —— 见下方「对齐」说明。
 * 代价是折行后第二行会**顶到最左**而不是缩进到名字起点，这与系统文件管理器的
 * 行为一致（它们同样是折行回行首），可以接受。
 *
 * ## 名称过长时的可读性
 *
 * 1. 日期用 `onSurfaceVariant` 降一级颜色 ⇒ 扫视名字时它不抢注意力，但仍可读；
 * 2. 日期定长 ⇒ 所有行左边界一致，形成「隐式日期列」，不需要额外的列宽；
 * 3. 日期在前 ⇒ 截断只发生在文件名尾部，日期与扩展名之外的语义不受影响；
 * 4. 上行留 2 行（`maxLines`）⇒ 常见「剧集名 + 集数 + 分辨率」都放得下；
 * 5. 若后续启用「按年份分组」，组头已给出年份，行内可只保留 `MM-dd`
 *    （见 [formatNetworkFullDate] 的调用方按需切换），再省 5 个字符。
 *
 * @param name 原始文件名（**不含**日期，日期由本组件拼）
 * @param lastModified 修改时间（epoch 毫秒）。`<= 0` = 时间未知 ⇒ 不拼日期，只画名字
 *   （SMB / FTP 的部分目录项、某些 WebDAV 实现会返回 0，硬拼一个 1970 年只会误导）
 */
@Composable
internal fun NetworkFileNameText(
  name: String,
  lastModified: Long,
  style: TextStyle,
  maxLines: Int,
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified,
  /** false = 只画 `MM-dd`（配合「按年份分组」的组头，见 [formatNetworkMonthDay]） */
  showYear: Boolean = true,
) {
  val dateColor = MaterialTheme.colorScheme.onSurfaceVariant
  // 日期与名字之间用两个空格：比单空格更能分隔，又不引入「·」等第三种符号
  val text =
    remember(name, lastModified, dateColor, showYear) {
      buildAnnotatedString {
        if (lastModified > 0) {
          val date =
            if (showYear) formatNetworkFullDate(lastModified) else formatNetworkMonthDay(lastModified)
          withStyle(SpanStyle(color = dateColor)) { append(date) }
          append("  ")
        }
        append(name)
      }
    }
  Text(
    text = text,
    style = style,
    color = color,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}
