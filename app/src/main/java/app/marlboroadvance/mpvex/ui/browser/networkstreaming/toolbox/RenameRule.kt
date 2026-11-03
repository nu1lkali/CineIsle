package app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/*
 * 批量重命名的「有序规则链」——实现参考 flut-renamer（github.com/sun-jiao/flut-renamer）
 * 的 lib/rules 目录与 lib/pages/rules_page.dart：
 *
 *   文件名 = 规则1( 规则2( … 原名 ) )
 *
 * 七种规则与它一一对应：Replace / Remove / Insert / Increment / Rearrange /
 * Transliterate / Truncate，每条都有 `ignoreExtension`（规则只作用主名、扩展名原样留）。
 * 规则链本身是持久化的（对应它的 RulePersistence + temp_rules.yaml），并额外记住
 * 「上次添加的规则类型」（对应 Shared.ruleName）——这就是「规则能够记忆」。
 *
 * ## 与 flut-renamer 有意不同的两点
 *
 * 1. **Increment 用 ordinal 纯函数算**，不用它那个可变的 `index` 字段：预览与执行
 *    若各跑一次链，有状态规则会让两趟结果不一样（第二趟从上次停下的号继续）。
 *    这里 `start + ordinal * step` 由条目在批次里的位置决定，预览 = 执行，幂等。
 * 2. **Transliterate 只做 upper / lower 两档**。原实现的拼音 / 简繁 / 西里尔转写
 *    依赖 pinyin、lpinyin、cyrtranslit 三个 Dart 库，本项目没有对应依赖；
 *    与其挂一个点了没反应的菜单项，不如只开放能真正生效的两个子类型。
 * 3. **随机字符串不是第 8 种规则**，而是输出文本里的 {RandomString:N} 标记
 *    （对齐它的 metadata_dialog）。它只在 Insert 里生效且用 UUID v4 每次真随机；
 *    这里改成「任何规则的结果文本都能写」，并按「原名 + 序号 + 规则位置 + 种子」派生
 *    确定性伪随机 —— 每个文件、每个标记都不同，但预览 / 清单 / 执行三处完全一致。
 *    种子是 [RenameRuleBook.randomSeed]，改一次整批换一批，见 expandRandomTags。
 */

/** 规则链里的一条。sealed 让 kotlinx.serialization 自动带上 `type` 判别字段 */
@Serializable
sealed interface RenameRule {
  /** true：先把「主名 / .扩展名」拆开，规则只改主名，最后再把扩展名接回去 */
  val ignoreExtension: Boolean

  /** 规则列表里那一行摘要（对应 flut-renamer 的 Rule.toString） */
  fun describe(): String
}

/** ① 替换：target → replacement。limit：0 全部 / 正 从头 N 处 / 负 从尾 N 处 */
@Serializable
@SerialName("Replace")
data class RuleReplace(
  @SerialName("targetString") val target: String = "",
  @SerialName("replacementString") val replacement: String = "",
  @SerialName("replaceLimit") val limit: Int = 0,
  @SerialName("caseSensitive") val caseSensitive: Boolean = false,
  @SerialName("isRegex") val isRegex: Boolean = false,
  @SerialName("ignoreExtension") override val ignoreExtension: Boolean = true,
) : RenameRule {
  override fun describe(): String =
    "替换「$target」→「$replacement」${if (isRegex) "（正则）" else ""}" +
      when {
        limit > 0 -> "，只换前 $limit 处"
        limit < 0 -> "，只换后 ${-limit} 处"
        else -> ""
      }
}

/** ② 删除：等价于 Replace(target → "")，单独成一类是为了 UI 里一眼能选到 */
@Serializable
@SerialName("Remove")
data class RuleRemove(
  @SerialName("targetString") val target: String = "",
  @SerialName("removeLimit") val limit: Int = 0,
  @SerialName("caseSensitive") val caseSensitive: Boolean = false,
  @SerialName("isRegex") val isRegex: Boolean = false,
  @SerialName("ignoreExtension") override val ignoreExtension: Boolean = true,
) : RenameRule {
  override fun describe(): String = "删除「$target」" + if (isRegex) "（正则）" else ""
}

/**
 * ③ 插入：在字符位置 index 前插入 text；fromEnd=true 表示位置从末尾数。
 * text 里可写 {RandomString} 或 {RandomString:N}（N=1..32），会展开成小写十六进制随机串 ——
 * 对应 flut-renamer 的 metadata_dialog「插入随机字符串」，见 expandRandomTags。
 */
@Serializable
@SerialName("Insert")
data class RuleInsert(
  @SerialName("insert") val text: String = "",
  @SerialName("insertIndex") val index: Int = 0,
  @SerialName("toEnd") val fromEnd: Boolean = false,
  @SerialName("ignoreExtension") override val ignoreExtension: Boolean = true,
) : RenameRule {
  override fun describe(): String =
    "在第 ${if (fromEnd) "倒数第 " else ""}$index 个字符前插入「$text」" +
      if (RANDOM_STRING_TAG.containsMatchIn(text)) "（含随机串）" else ""
}

/** ④ 递增编号：prefix-001 形式，或把编号插进原名指定位置 */
@Serializable
@SerialName("Increment")
data class RuleIncrement(
  @SerialName("prefix") val prefix: String = "",
  @SerialName("startIndex") val start: Int = 1,
  @SerialName("step") val step: Int = 1,
  @SerialName("omitDash") val omitDash: Boolean = false,
  @SerialName("minimumDigits") val minDigits: Int = 3,
  @SerialName("mode") val mode: IncrementMode = IncrementMode.REPLACE,
  @SerialName("insertIndex") val insertIndex: Int = 0,
  @SerialName("toEnd") val fromEnd: Boolean = false,
  @SerialName("suffix") val suffix: String = "",
  @SerialName("ignoreExtension") override val ignoreExtension: Boolean = true,
) : RenameRule {
  override fun describe(): String =
    "编号「$prefix${if (omitDash) "" else "-"}${start.toString().padStart(minDigits, '0')}$suffix」" +
      if (mode == IncrementMode.INSERT) "（插到第 $insertIndex 位）" else "（替换整个主名）"
}

@Serializable
enum class IncrementMode { REPLACE, INSERT }

/** ⑤ 重排：按 delimiter 切段，再按 order（1 起）取段拼回去；delimiter 为空时按字符切 */
@Serializable
@SerialName("Rearrange")
data class RuleRearrange(
  @SerialName("delimiter") val delimiter: String = "-",
  @SerialName("order") val order: List<Int> = listOf(1),
  @SerialName("ignoreExtension") override val ignoreExtension: Boolean = true,
) : RenameRule {
  override fun describe(): String = "按「${delimiter.ifEmpty { "字符" }}」切分后重排为 ${order.joinToString(",")}"
}

/** ⑥ 大小写 / 字符转写：仅开放无需第三方库的两档 */
@Serializable
@SerialName("Transliterate")
data class RuleTransliterate(
  @SerialName("transliterateType") val mode: CaseMode = CaseMode.LOWER,
  @SerialName("ignoreExtension") override val ignoreExtension: Boolean = true,
) : RenameRule {
  override fun describe(): String = if (mode == CaseMode.UPPER) "主名转大写" else "主名转小写"
}

@Serializable
enum class CaseMode { UPPER, LOWER }

/** ⑦ 截断：两个字符位置之间「保留」或「挖掉」 */
@Serializable
@SerialName("Truncate")
data class RuleTruncate(
  @SerialName("index1") val index1: Int = 0,
  @SerialName("index2") val index2: Int = 0,
  @SerialName("i1toEnd") val i1FromEnd: Boolean = false,
  @SerialName("i2toEnd") val i2FromEnd: Boolean = false,
  @SerialName("keepBetween") val keepBetween: Boolean = true,
  @SerialName("ignoreExtension") override val ignoreExtension: Boolean = true,
) : RenameRule {
  override fun describe(): String =
    (if (keepBetween) "只保留第 $index1–$index2 位" else "挖掉第 $index1–$index2 位") +
      if (i1FromEnd || i2FromEnd) "（从末尾数）" else ""
}

/** 添加规则菜单里可选的七种类型（对应 rules_page.dart 的 PopupMenuButton 七项） */
enum class RenameRuleType(val label: String) {
  REPLACE("替换"),
  REMOVE("删除"),
  INSERT("插入"),
  INCREMENT("递增编号"),
  REARRANGE("重排"),
  TRANSLITERATE("大小写"),
  TRUNCATE("截断"),
  ;

  fun blank(): RenameRule =
    when (this) {
      REPLACE -> RuleReplace()
      REMOVE -> RuleRemove()
      INSERT -> RuleInsert()
      INCREMENT -> RuleIncrement()
      REARRANGE -> RuleRearrange()
      TRANSLITERATE -> RuleTransliterate()
      TRUNCATE -> RuleTruncate()
    }
}

/**
 * 规则本：整条规则链 + 「上次添加的类型」+ 是否包含文件夹。
 *
 * 对应 flut-renamer 的三份记忆：`temp_rules.yaml`（链）、`Shared.ruleName`（上次类型）、
 * `Shared.fileOrDir`（文件 / 文件夹范围）。合成一份 JSON 落盘，见 [RenameRuleStore]。
 * 另存 [randomSeed]：确定性随机串的种子，改一次整批换一批，不必重排规则或改文件名。
 */
@Serializable
data class RenameRuleBook(
  val rules: List<RenameRule> = emptyList(),
  val lastAddedType: String = RenameRuleType.REPLACE.name,
  val includeDirs: Boolean = false,
  val randomSeed: Int = 0,
)

/** AList / Windows 都要挡掉的非法文件名字符，换成下划线而不是直接丢 */
private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")

/**
 * flut-renamer 的「插入随机字符串」标记：{RandomString} 默认 8 位，
 * {RandomString:N} 指定 N 位（1..32）。字符集与它一致 = UUID v4 去连字符后的小写十六进制。
 */
private val RANDOM_STRING_TAG = Regex("\\{RandomString(?::(\\d+))?\\}", RegexOption.IGNORE_CASE)
private const val RANDOM_HEX = "0123456789abcdef"

/**
 * 把插入文本里的随机串标记展开成**确定性**随机串。
 *
 * ⚠️ 为什么不用 kotlin.random.Random / UUID：规则页预览、正式清单、真正执行会各跑一次链，
 *    真随机会让三处名字都不一样 —— 用户看着清单点确认，结果改名却是另一个串。
 *    这里用「原文件名 + 批次序号 + 规则在链里的位置 + 第几个标记 + 用户种子」派生种子，
 *    再走 xorshift64* 生成：同一输入永远同一输出，不同文件天然不同串。
 *    想换一批随机串就改 [seed]（规则页里可调），不必重排规则或改文件名。
 */
private fun expandRandomTags(
  text: String,
  original: String,
  ordinal: Int,
  ruleIndex: Int,
  seed: Int,
): String {
  if (!RANDOM_STRING_TAG.containsMatchIn(text)) return text
  var tagIndex = 0
  return RANDOM_STRING_TAG.replace(text) { match ->
    val length = (match.groupValues.getOrNull(1)?.toIntOrNull() ?: 8).coerceIn(1, 32)
    val s = randomTagSeed(original, ordinal, ruleIndex, tagIndex++, seed)
    randomHex(s, length)
  }
}

/** FNV 风格的长整型混合：只依赖整数运算与 Long 自然回绕，跨进程 / 跨设备结果一致 */
private fun randomTagSeed(
  original: String,
  ordinal: Int,
  ruleIndex: Int,
  tagIndex: Int,
  seed: Int,
): Long {
  var h = 1125899906842597L
  fun mix(value: Long) {
    h = h * 31 + value
  }
  original.forEach { mix(it.code.toLong()) }
  mix(ordinal.toLong() + 1)
  mix(ruleIndex.toLong() + 1)
  mix(tagIndex.toLong() + 1)
  mix(seed.toLong())
  return if (h == 0L) 1L else h
}

private fun randomHex(seed: Long, length: Int): String {
  var state = seed
  val out = CharArray(length)
  for (i in 0 until length) {
    state = state xor (state shl 13)
    state = state xor (state ushr 7)
    state = state xor (state shl 17)
    out[i] = RANDOM_HEX[((state ushr 60) and 0xF).toInt()]
  }
  return String(out)
}

/**
 * 按 code point 拆字符。
 *
 * flut-renamer 用 grapheme cluster（字素簇），Kotlin 标准库没有等价物；按 code point
 * 拆已经能正确处理中文与 emoji（不会像 UTF-16 char 那样把 emoji 劈成两半），
 * 组合字符序列这种边缘情况才与它略有差异 —— 对媒体文件名整理足够。
 */
private fun charactersOf(text: String): List<String> {
  val out = ArrayList<String>(text.length)
  var i = 0
  while (i < text.length) {
    val cp = text.codePointAt(i)
    out += String(Character.toChars(cp))
    i += Character.charCount(cp)
  }
  return out
}

/** 把文件名拆成 (主名, 含点的扩展名)；与 flut-renamer 的 splitFileName 同语义 */
private fun splitFileName(name: String, ignoreExtension: Boolean): Pair<String, String> {
  if (!ignoreExtension) return name to ""
  val dot = name.lastIndexOf('.')
  return if (dot > 0 && dot < name.length - 1) {
    name.substring(0, dot) to name.substring(dot)
  } else {
    name to ""
  }
}

private fun positionOf(length: Int, index: Int, fromEnd: Boolean): Int =
  (if (fromEnd) length - index else index).coerceIn(0, length)

/** 替换 / 删除共用的匹配替换：按 limit 选前 N 或后 N 处，且不让插入文本被再次匹配 */
private fun replaceSelected(
  input: String,
  target: String,
  replacement: String,
  limit: Int,
  caseSensitive: Boolean,
  isRegex: Boolean,
): String {
  if (target.isEmpty()) return input
  val regex =
    runCatching {
      val pattern = if (isRegex) target else Regex.escape(target)
      val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
      Regex(pattern, options)
    }.getOrElse { return input } // 正则非法：这一条规则整体不生效，预览里能看出来

  val matches = regex.findAll(input).toList()
  val count = if (limit == 0) matches.size else kotlin.math.abs(limit).coerceAtMost(matches.size)
  val selected = if (limit >= 0) matches.take(count) else matches.takeLast(count)
  if (selected.isEmpty()) return input

  val out = StringBuilder()
  var cursor = 0
  selected.forEach { m ->
    out.append(input, cursor, m.range.first)
    out.append(
      if (isRegex) {
        // 支持 \1 形式的捕获组引用（与 flut-renamer 的 replacer 一致）
        Regex("\\\\(\\d+)").replace(replacement) { ref ->
          val i = ref.groupValues[1].toIntOrNull()
          if (i == null || i > m.groupValues.size - 1) "" else m.groupValues[i]
        }
      } else {
        replacement
      },
    )
    cursor = m.range.last + 1
  }
  out.append(input, cursor, input.length)
  return out.toString()
}

/**
 * 把一条规则作用到文件名上。
 *
 * [ordinal] 是条目在本批次里的序号（递增编号 / 随机串种子用）；
 * [ruleIndex] 是这条规则在链里的位置、[original] 是未过规则的原始文件名 ——
 * 后两者只用来派生随机串种子，保证同一文件、同一条规则永远得到同一个随机串。
 */
private fun applyOne(
  name: String,
  rule: RenameRule,
  ordinal: Int,
  ruleIndex: Int,
  original: String,
  randomSeed: Int,
): String {
  val (base, ext) = splitFileName(name, rule.ignoreExtension)
  val chars = charactersOf(base)
  val result =
    when (rule) {
      is RuleReplace -> replaceSelected(base, rule.target, rule.replacement, rule.limit, rule.caseSensitive, rule.isRegex)
      is RuleRemove -> replaceSelected(base, rule.target, "", rule.limit, rule.caseSensitive, rule.isRegex)

      is RuleInsert -> {
        val pos = positionOf(chars.size, rule.index, rule.fromEnd)
        chars.take(pos).joinToString("") + rule.text + chars.drop(pos).joinToString("")
      }

      is RuleIncrement -> {
        val number = (rule.start + ordinal * rule.step).toString().padStart(rule.minDigits.coerceIn(1, 10), '0')
        val token = "${rule.prefix}${if (rule.omitDash) "" else "-"}$number${rule.suffix}"
        if (rule.mode == IncrementMode.INSERT) {
          val pos = positionOf(chars.size, rule.insertIndex, rule.fromEnd)
          chars.take(pos).joinToString("") + token + chars.drop(pos).joinToString("")
        } else {
          token
        }
      }

      is RuleRearrange -> {
        val parts =
          if (rule.delimiter.isEmpty()) {
            chars
          } else {
            val pieces = mutableListOf<String>()
            var start = 0
            Regex.escape(rule.delimiter).toRegex().findAll(base).forEach { m ->
              pieces += base.substring(start, m.range.first)
              start = m.range.last + 1
            }
            pieces += base.substring(start)
            pieces
          }
        rule.order
          .filter { it in 1..parts.size }
          .map { parts[it - 1] }
          .joinToString(rule.delimiter)
      }

      is RuleTransliterate ->
        if (rule.mode == CaseMode.UPPER) base.uppercase(Locale.ROOT) else base.lowercase(Locale.ROOT)

      is RuleTruncate -> {
        var start = positionOf(chars.size, rule.index1, rule.i1FromEnd)
        var end = positionOf(chars.size, rule.index2, rule.i2FromEnd)
        if (start > end) {
          val swap = start
          start = end
          end = swap
        }
        if (rule.keepBetween) {
          chars.subList(start, end).joinToString("")
        } else {
          chars.take(start).joinToString("") + chars.drop(end).joinToString("")
        }
      }
    }
  // 所有「输出文本」类规则统一在结果上展开 {RandomString:N}：Insert 的插入文本、
  // Replace 的替换文本、Increment 的前后缀、Rearrange 的分隔符都能用；同一段里出现
  // 多个标记会按出现顺序各得一个不同的串（配合 Replace 的「前 N / 后 N 处」正好每处一个）。
  return expandRandomTags(result, original, ordinal, ruleIndex, randomSeed) + ext
}

/**
 * 顺序跑完整条规则链（只管「一条名字怎么变」，不管整批怎么避让）。
 *
 * @return 新文件名；null 表示规则链没改动这个名字（预览里显示为「保持原名」）。
 *         撞名避让在 [computeRenameBatch] 里做 —— 它需要「全目录名单」，
 *         单条规则链拿不到，也不该拿到。
 */
fun applyRuleChain(
  original: String,
  rules: List<RenameRule>,
  ordinal: Int,
  randomSeed: Int = 0,
): String? {
  if (original.isBlank()) return null
  var current = original
  rules.forEachIndexed { i, rule ->
    current = applyOne(current, rule, ordinal, i, original, randomSeed)
  }
  val cleaned = current.replace(ILLEGAL_NAME_CHARS, "_").trim()
  if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
  return cleaned.takeIf { it != original }
}

/**
 * 一次算出整批重命名结果 —— 规则页的即时预览与 [NetworkToolboxPlanner.planBatchRename]
 * **必须共用这一份**，否则两边各写一遍循环时 ordinal 基准、taken 演化会悄悄漂移：
 * 「递增编号」规则就会出现预览一个号、正式清单另一个号。
 *
 * @param allNames 当前目录全部原名（含不参与改名的文件夹），作为撞名避让的初始集合
 * @param targetNames 按执行顺序排好的待改名名单；includeDirs 关时已在调用方排除文件夹
 * @return 与 targetNames 一一对应的 (原名, 新名)，新名 null = 规则链没改动它
 */
fun computeRenameBatch(
  allNames: List<String>,
  targetNames: List<String>,
  rules: List<RenameRule>,
  randomSeed: Int = 0,
): List<Pair<String, String?>> {
  val taken = allNames.toMutableSet()
  return targetNames.mapIndexed { ordinal, name ->
    val newName = applyRuleChain(name, rules, ordinal, randomSeed)?.let { toolboxUniqueName(it, taken) }
    if (newName != null) taken += newName
    name to newName
  }
}
