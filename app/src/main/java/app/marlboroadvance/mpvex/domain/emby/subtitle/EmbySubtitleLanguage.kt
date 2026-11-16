package app.marlboroadvance.mpvex.domain.emby.subtitle

/**
 * 字幕语言归一化 —— 全项目**唯一**口径。
 *
 * ## 为什么需要这一层
 *
 * Emby 返回的语言字段有三套写法，同一个语言会出现三种码：
 *  · `Language` 是 ISO 639-2/B（`chi` / `eng` / `jpn`，偶尔是 639-1 的 `zh` / `en`）；
 *  · `DisplayLanguage` 是**服务端本地化文案**（中文服务器给「简体中文」，英文服务器给
 *    "Chinese (Simplified)"）；服务器换语言设置，同一个文件就换个说法；
 *  · 用户偏好里存的是 mpv 的 `slang` 风格（`zh-CN,zh,eng`），又是第三套。
 *
 * 直接字符串相等比较必然对不上 —— 这是「明明有中文字幕却选不中」的根源。
 *
 * ## 简繁必须分开
 *
 * `chi` 分不出简体/繁体，**ISO 639 本身就不区分**。所以只能从 `DisplayLanguage` /
 * `DisplayTitle` / 文件名这些**文本线索**里认出「简 / 繁」，认不出就落回不带脚本的
 * [ZH]（此时对 `zh-CN` 与 `zh-TW` 的偏好都算命中，宁可选中也不要不选）。
 *
 * ⚠️ 线索只用来**消歧**，绝不单独用来判语言：`DisplayTitle` 里出现「英文」两个字
 * 不代表这条是英文字幕（可能是「简英双语」）。真正的语言仍以 `Language` 字段为准。
 */
object EmbySubtitleLanguage {
  /** 认不出语言。**不匹配任何偏好**（宁可关字幕，也不要随便塞一条）。 */
  const val UNKNOWN = "und"

  /** 中文·简体 */
  const val ZH_HANS = "zh-hans"

  /** 中文·繁体 */
  const val ZH_HANT = "zh-hant"

  /** 中文·脚本未知 */
  const val ZH = "zh"

  /** ISO 639-2/B（以及少量 639-1 之外的历史写法）→ 639-1 */
  private val THREE_TO_TWO: Map<String, String> = mapOf(
    "chi" to "zh", "zho" to "zh", "cmn" to "zh", "yue" to "zh",
    "eng" to "en", "jpn" to "ja", "kor" to "ko",
    "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de",
    "spa" to "es", "rus" to "ru", "por" to "pt", "ita" to "it",
    "tha" to "th", "ara" to "ar", "hin" to "hi", "vie" to "vi",
    "tur" to "tr", "pol" to "pl", "dut" to "nl", "nld" to "nl",
    "swe" to "sv", "nor" to "no", "nob" to "no", "dan" to "da",
    "fin" to "fi", "cze" to "cs", "ces" to "cs", "hun" to "hu",
    "gre" to "el", "ell" to "el", "heb" to "he", "ind" to "id",
    "may" to "ms", "msa" to "ms", "ukr" to "uk", "ron" to "ro",
    "rum" to "ro", "bul" to "bg", "srp" to "sr", "hrv" to "hr",
    "slo" to "sk", "slk" to "sk", "slv" to "sl", "est" to "et",
    "lav" to "lv", "lit" to "lt", "cat" to "ca", "fil" to "tl",
    "tgl" to "tl", "ben" to "bn", "tam" to "ta", "tel" to "te",
    "mal" to "ml", "kan" to "kn", "mar" to "mr", "guj" to "gu",
    "pan" to "pa", "urd" to "ur", "per" to "fa", "fas" to "fa",
    "mya" to "my", "khm" to "km", "lao" to "lo", "nep" to "ne",
    "sin" to "si", "amh" to "am", "swa" to "sw", "zul" to "zu",
  )

  /** 英文语言名 → 639-1。Emby 的 `Language` 偶尔直接给名字（老库 / 手工改过的元数据）。 */
  private val ENGLISH_NAMES: Map<String, String> = mapOf(
    "chinese" to "zh", "cantonese" to "zh", "mandarin" to "zh",
    "english" to "en", "japanese" to "ja", "korean" to "ko",
    "french" to "fr", "german" to "de", "spanish" to "es",
    "russian" to "ru", "portuguese" to "pt", "italian" to "it",
    "thai" to "th", "arabic" to "ar", "hindi" to "hi",
    "vietnamese" to "vi", "turkish" to "tr", "polish" to "pl",
    "dutch" to "nl", "swedish" to "sv", "norwegian" to "no",
    "danish" to "da", "finnish" to "fi", "czech" to "cs",
    "hungarian" to "hu", "greek" to "el", "hebrew" to "he",
    "indonesian" to "id", "malay" to "ms", "ukrainian" to "uk",
    "romanian" to "ro", "bulgarian" to "bg", "serbian" to "sr",
    "croatian" to "hr", "slovak" to "sk", "slovenian" to "sl",
    "estonian" to "et", "latvian" to "lv", "lithuanian" to "lt",
    "catalan" to "ca", "tagalog" to "tl", "bengali" to "bn",
    "tamil" to "ta", "telugu" to "te", "malayalam" to "ml",
    "kannada" to "kn", "marathi" to "mr", "gujarati" to "gu",
    "punjabi" to "pa", "urdu" to "ur", "persian" to "fa", "farsi" to "fa",
  )

  /** 简体线索（会被当作独立 token 或 CJK 子串匹配） */
  private val HANS_TOKENS: Set<String> = setOf(
    "chs", "hans", "sc", "zhcn", "gb", "gb2312", "gbk", "simplified",
    "简体", "简中", "简体中文", "简",
  )

  /** 繁体线索 */
  private val HANT_TOKENS: Set<String> = setOf(
    "cht", "hant", "tc", "zhtw", "zhhk", "zhmo", "big5", "traditional",
    "繁体", "繁中", "繁体中文", "繁", "正體", "正体",
  )

  private val TOKEN_SPLIT = Regex("[^a-z0-9\\u4e00-\\u9fff]+")

  /**
   * 把 Emby / 用户偏好里的语言串归一成「基础语言码」（不含简繁）。
   *
   * `chi` → `zh`、`zh-CN` → `zh`、`English` → `en`、`und`/空 → null。
   *
   * ⚠️ 这里**故意丢掉地区与脚本**：简繁由 [resolve] 单独决定，
   * 否则 `zh-CN`/`zh-TW` 会在这一层就被抹平，后面再也分不开。
   */
  fun baseOf(raw: String?): String? {
    val text = raw?.trim()?.lowercase() ?: return null
    if (text.isEmpty() || text == "und" || text == "unknown" || text == "none") return null
    // 去掉 region / script：zh-CN、zh_Hans、pt-BR …
    val head = text.substringBefore('-').substringBefore('_')
    if (head.isEmpty()) return null
    if (head.length == 2 && head.all { it in 'a'..'z' }) {
      return if (head == "un") null else head
    }
    THREE_TO_TWO[head]?.let { return it }
    ENGLISH_NAMES[head]?.let { return it }
    ENGLISH_NAMES[text]?.let { return it }
    // 兜底：三字母且不在表里（如 `sat`）→ 原样保留，仍可参与相等比较
    return head.takeIf { it.length in 3..8 && it.all { c -> c in 'a'..'z' } }
  }

  /**
   * 归一成**可用于偏好匹配的键**：`zh-hans` / `zh-hant` / `zh` / `en` / …
   *
   * @param raw `Language` 字段（最权威的语言来源）
   * @param hints 消歧线索，**按可信度从高到低**传：`DisplayLanguage` → `DisplayTitle`
   *   → 文件名 / 路径。只有语言是中文时才看这些线索。
   */
  fun resolve(raw: String?, vararg hints: String?): String {
    val base = baseOf(raw) ?: inferBaseFromHints(*hints) ?: return UNKNOWN
    if (base != "zh") return base
    return when (scriptOf(raw, *hints)) {
      ZhScript.HANS -> ZH_HANS
      ZhScript.HANT -> ZH_HANT
      ZhScript.NONE -> ZH
    }
  }

  /** 中文简繁判定结果 */
  enum class ZhScript { NONE, HANS, HANT }

  /**
   * 从若干文本线索里判简繁。第一个能给出结论的线索即生效（越靠前越可信）。
   *
   * `raw` 也参与：偏好里可能写的就是 `zh-CN` / `zh-TW`。
   */
  fun scriptOf(raw: String?, vararg hints: String?): ZhScript {
    // 先看明确的 region 写法（zh-CN / zh_TW / zh-Hant），这是确定性最高的信号
    regionScript(raw)?.let { return it }
    for (hint in hints) {
      if (hint.isNullOrBlank()) continue
      regionScript(hint)?.let { return it }
      tokenScript(hint)?.let { return it }
    }
    return ZhScript.NONE
  }

  private fun regionScript(raw: String?): ZhScript? {
    if (raw.isNullOrBlank()) return null
    val t = raw.trim().lowercase().replace('_', '-')
    return when {
      t.contains("hans") -> ZhScript.HANS
      t.contains("hant") -> ZhScript.HANT
      t == "zh-cn" || t == "zh-sg" || t == "zh-my" -> ZhScript.HANS
      t == "zh-tw" || t == "zh-hk" || t == "zh-mo" -> ZhScript.HANT
      else -> null
    }
  }

  private fun tokenScript(hint: String): ZhScript? {
    val lower = hint.lowercase()
    // CJK 不会被打散成 token，所以中文线索单独按子串判
    if (HANS_TOKENS.any { it.any { c -> c.code > 0x2E80 } && lower.contains(it) }) {
      return ZhScript.HANS
    }
    if (HANT_TOKENS.any { it.any { c -> c.code > 0x2E80 } && lower.contains(it) }) {
      return ZhScript.HANT
    }
    val tokens = lower.split(TOKEN_SPLIT).filter { it.isNotEmpty() }.toSet()
    if (tokens.any { it in HANS_TOKENS }) return ZhScript.HANS
    if (tokens.any { it in HANT_TOKENS }) return ZhScript.HANT
    return null
  }

  private fun inferBaseFromHints(vararg hints: String?): String? {
    for (hint in hints) {
      if (hint.isNullOrBlank()) continue
      val lower = hint.lowercase()
      if (lower.contains("中文") || lower.contains("中字") || lower.contains("chinese")) return "zh"
      ENGLISH_NAMES[lower]?.let { return it }
    }
    return null
  }

  /**
   * 偏好（用户设置里的一项）是否覆盖某个归一化后的语言键。
   *
   * 规则：
   * · `zh`（脚本未知）与 `zh-hans` / `zh-hant` **互相覆盖** —— 认不出脚本时不该把字幕关掉；
   * · 同一基础语言的其它地区写法等价（`en-GB` 覆盖 `en`）；
   * · [UNKNOWN] 不被任何偏好覆盖。
   */
  fun matches(preference: String, key: String): Boolean {
    val prefKey = resolve(preference)
    if (prefKey == UNKNOWN || key == UNKNOWN) return false
    if (prefKey == key) return true
    val prefBase = baseOf(prefKey)
    val keyBase = baseOf(key)
    if (prefBase == null || keyBase == null || prefBase != keyBase) return false
    if (prefBase == "zh") {
      // ⚠️ 简繁必须分辨：`zh-CN` 的偏好**不覆盖** `zh-hant` 字幕（需求 4.1）。
      // 只有一侧**认不出脚本**（裸 `zh`）时才允许互通 —— 那时不选反而更糟。
      return prefKey == ZH || key == ZH
    }
    // 同语言的地区写法等价（en-GB ≈ en）
    return prefKey == prefBase || key == prefBase
  }

  /** 中文名（日志 / 自动选择理由用）。未知语言原样返回。 */
  fun label(key: String): String = when (key) {
    ZH_HANS -> "简体中文"
    ZH_HANT -> "繁体中文"
    ZH -> "中文"
    "en" -> "英语"
    "ja" -> "日语"
    "ko" -> "韩语"
    "fr" -> "法语"
    "de" -> "德语"
    "es" -> "西班牙语"
    "ru" -> "俄语"
    "pt" -> "葡萄牙语"
    "it" -> "意大利语"
    "th" -> "泰语"
    "ar" -> "阿拉伯语"
    "vi" -> "越南语"
    "id" -> "印尼语"
    "ms" -> "马来语"
    "tr" -> "土耳其语"
    "nl" -> "荷兰语"
    "pl" -> "波兰语"
    "hi" -> "印地语"
    UNKNOWN -> "未指定语言"
    else -> key
  }
}
