package app.marlboroadvance.mpvex.ui.preferences

import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.PlayerButton
import app.marlboroadvance.mpvex.preferences.preference.Preference

/**
 * 「播放器按钮显示开关」与「控件布局编辑器」的双向同步逻辑。
 *
 * ## 要解决的问题
 *
 * 按钮既能被用户**手动**加进控件簇（布局编辑器），又有一个「是否显示」的开关。
 * 两个入口互不知情，就会出现「用户在编辑器里拖了按钮，开关却是关的，按钮不显示」，
 * 用户完全不知道该去哪儿修。
 *
 * ## 选定的语义：双向同步
 *
 * · 关开关 ⇒ **连带把布局里已有的该按钮移除**（一次性、不可逆，须二次确认）。
 * · 开开关 ⇒ **自动加回它上次所在的簇**（记在 `dislike_layout_cluster` 里；
 *   没有记录时用 [DEFAULT_CLUSTER]）。
 * · 在布局编辑器里放了按钮 ⇒ 开关自动打开，并记录它落在哪个簇
 *   （见 `ControlLayoutEditorScreen`）。
 *
 * 为什么要「记住原来的簇」而不是每次塞默认位置：加到哪个控件簇是用户的布局决定，
 * App 猜不出来 —— 硬塞进顶栏或底部都可能挤掉别的按钮（竖屏底部按钮条只有 411dp 宽，
 * 见 `AppearancePreferences.portraitBottomControls`）。记住原位置就等于「恢复原状」，
 * 既不挤掉别的按钮，也不需要用户再去编辑器里做一遍。
 *
 * 唯一还猜不出的是**从没被手动摆放过**的新用户（没有记录）—— 那就退到默认簇，
 * 并且在设置页明确告诉他「可在布局里换位置」，把最终决定权交回用户。
 *
 * ## 纯函数
 *
 * 布局存的是逗号分隔的枚举名 CSV（见 [AppearancePreferences.topLeftControls] 等 5 个控件簇），
 * 所以这里纯做字符串手术，可直接 JVM 单测。
 */
object DislikeLayoutSync {

  /** 需要清理的控件簇 key —— 布局里一共就这么五处，列全以免漏。 */
  val CONTROL_KEYS = listOf(
    "top_left_controls",
    "top_right_controls",
    "bottom_right_controls_v2",
    "bottom_left_controls",
    "portrait_bottom_controls_v3",
  )

  /**
   * 「逻辑簇 key」→ 真实的偏好对象。
   *
   * ⚠️ 逻辑 key 与 SharedPreferences 里的**存储 key 不是一回事**：
   * `bottom_left_controls` 存的是 `bottom_left_controls_v2`（v2/v3 是老版本换默认值时
   * 被迫换的存储 key，见 `AppearancePreferences` 里的注释）。所以这份映射只能有**一处**，
   * 两处各写一份迟早对不上 —— 对不上的后果是：设置页改了 A 簇，实际写进了 B 簇。
   */
  fun clusterPrefs(
    appearance: AppearancePreferences,
  ): Map<String, Preference<String>> = mapOf(
    "top_left_controls" to appearance.topLeftControls,
    "top_right_controls" to appearance.topRightControls,
    "bottom_right_controls_v2" to appearance.bottomRightControls,
    "bottom_left_controls" to appearance.bottomLeftControls,
    "portrait_bottom_controls_v3" to appearance.portraitBottomControls,
  )

  /** 五个簇当前的值（逻辑 key → CSV）。 */
  fun readClusters(prefs: Map<String, Preference<String>>): Map<String, String> =
    prefs.mapValues { (_, pref) -> pref.get() }

  /** 把 [writes] 写回对应簇。key 不在映射里就跳过（不该发生，但也不能崩）。 */
  fun writeClusters(
    prefs: Map<String, Preference<String>>,
    writes: Map<String, String>,
  ) {
    writes.forEach { (key, value) -> prefs[key]?.set(value) }
  }

  /**
   * 从一段 CSV 里移除指定按钮。
   *
   * 只认**完整 token**（逗号分隔后 `trim()` 再比对全等）——
   * 用 `contains` 会出现「EMBY_FAVORITE 里含 FAVORITE」这类误删。
   *
   * @return 处理后的 CSV；没有该按钮时原样返回（调用方可借此判断是否要提示）
   */
  fun removeFrom(csv: String, button: PlayerButton): String {
    val target = button.name
    val kept =
      csv.splitToSequence(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filter { it.uppercase() != target }
        .toList()
    return kept.joinToString(",")
  }

  /**
   * 该布局里是否含此按钮（决定要不要提示用户「已经从布局里移掉了」）。
   */
  fun containsIn(csv: String, button: PlayerButton): Boolean {
    val target = button.name
    return csv.splitToSequence(',')
      .map { it.trim() }
      .any { it.uppercase() == target }
  }

  /**
   * 汇总：布局里一共被清掉了几个控件簇里的按钮。
   *
   * 用于设置页在关开关后告诉用户「顺带从 N 个控件位置移掉了它」，
   * 否则用户回头看布局编辑器会发现按钮不见了却不知道去哪问。
   *
   * @return (改动后的 CSV 列表, 被改动过的簇数)。列表与 [CONTROL_KEYS] **等长且同序**，
   *   某项为 null 表示调用方给的那份 map 里没有这个簇 —— 保留原值、不写回。
   */
  fun removeFromAll(
    cvs: Map<String, String>,
    button: PlayerButton = PlayerButton.DISLIKE,
  ): Pair<List<String?>, Int> {
    var changed = 0
    val out = CONTROL_KEYS.map { key ->
      // key 不在 map 里就跳过（返回 null），由调用方保留原值 ——
      // getValue 会在缺 key 时抛异常，而这里缺 key 只是"这个簇没配置"，不是错误。
      val csv = cvs[key]
      if (csv == null) {
        null
      } else {
        val next = removeFrom(csv, button)
        if (next != csv) changed++
        next
      }
    }
    return out to changed
  }

  /**
   * 把按钮**加回**某个控件簇（加到末尾，避免打乱用户已有的顺序）。
   *
   * @return 加过后的 CSV；本来就有这个按钮时原样返回。
   */
  fun addTo(csv: String, button: PlayerButton = PlayerButton.DISLIKE): String {
    if (containsIn(csv, button)) return csv
    val kept =
      csv.splitToSequence(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toMutableList()
    kept += button.name
    return kept.joinToString(",")
  }

  /**
   * 「关开关」与「开开关」的完整动作（真正动偏好的一侧）。
   *
   * 纯函数 —— 只算，不写。写偏好由调用方做（这样本文件能直接 JVM 单测，
   * 不用起 Koin / SharedPreferences）。
   *
   * @param current 各控件簇当前的值（key → CSV）
   * @param clusterKey 上次记录的所在簇；空/无效则用默认簇 [DEFAULT_CLUSTER]
   * @param button 要操作的按钮
   * @return 需要写回的 (key → 新 CSV) + 需要提示用户的信息
   */
  data class Plan(
    val writes: Map<String, String>,
    /** 动过几个簇 —— 设置页据此提示「顺带从 N 个位置移掉了它」。 */
    val changedClusters: Int,
  )

  /** 恢复默认后的目标：加回时用这个簇。横屏顶栏右侧，和「收藏」同簇。 */
  const val DEFAULT_CLUSTER = "top_right_controls"

  fun planTurnOff(
    current: Map<String, String>,
    button: PlayerButton = PlayerButton.DISLIKE,
  ): Plan {
    val (values, changed) = removeFromAll(current, button)
    val writes = HashMap<String, String>()
    CONTROL_KEYS.forEachIndexed { i, key ->
      val next = values[i] ?: return@forEachIndexed
      // ⚠️ 只写**真的变了**的簇。
      //   removeFrom 对"本来就没有该按钮"的簇会返回同值字符串，
      //   若照单全写，调用方会做一次毫无意义的 SharedPreferences 写入，
      //   而且 changedClusters 的语义（"改过几个"）会与实际写入数不一致。
      if (next != current[key]) writes[key] = next
    }
    return Plan(writes, changed)
  }

  /**
   * 出厂播种要写进哪几个簇。
   *
   * ⚠️ **横屏与竖屏是两套独立的簇**，只播一个等于另一个方向没有入口：
   *  · 横屏渲染 `top_left / top_right / bottom_left / bottom_right`；
   *  · 竖屏**只渲染 `portrait_bottom_controls_v3`**（见 `PlayerControls` 的
   *    `portraitTopButtons` / `portraitBottomButtons` 分支），横屏那几个簇它根本不读。
   *  第一版只播了 `top_right_controls` ⇒ 横屏有按钮、竖屏一个都没有。
   *
   * 竖屏底部条「必要时可横向滚动」，多加一颗不会挤爆别的按钮。
   */
  val SEED_CLUSTERS = listOf(
    "top_right_controls",
    "portrait_bottom_controls_v3",
  )

  /**
   * 出厂播种：把按钮加进 [SEED_CLUSTERS] 里**还没有它**的那些簇。
   *
   * ## 为什么必须有这一步
   *
   * 本功能的开关语义是「关开关 ⇒ 从布局里清掉；开开关 ⇒ 只置开关、不主动加回」
   * （用户选定，避免 App 猜该塞哪个簇）。这个语义能成立的前提是：
   * **出厂时按钮已经在布局里**。我第一版漏了播种 —— 于是开关默认「开」、
   * 布局里却没有按钮，用户在 mpv 播放器里**到处都找不到入口**，
   * 而设置页还显示着「已开启」，看上去像开关坏了。
   *
   * ## 为什么不能在偏好默认值里改一下就完事
   *
   * 控件簇的 CSV 一旦被读取过就写进了本地存储，改 `getString` 的默认值
   * **对已安装用户完全无效**（`AppearancePreferences` 里 v2/v3 换 key 的注释就是这个教训）。
   * 所以要有播种 + 一个**版本号**标记。
   *
   * ## 为什么按簇分别判断，而不是「任一簇已有就整体跳过」
   *
   * 「整体跳过」会让**只播了一半**的老用户永远补不上另一半 ——
   * 第一版播了横屏没播竖屏，若沿用整体跳过，竖屏那个洞就永远在那儿。
   * 按簇独立判断才能补齐。
   *
   * 「只播一次、不覆盖用户之后的手动调整」由调用方的版本号保证（见
   * `BrowserPreferences.dislikeLayoutSeedVersion`），本函数只管算。
   */
  fun planSeed(
    current: Map<String, String>,
    button: PlayerButton = PlayerButton.DISLIKE,
  ): Plan {
    val writes = HashMap<String, String>()
    SEED_CLUSTERS.forEach { key ->
      val csv = current[key] ?: return@forEach
      if (containsIn(csv, button)) return@forEach
      writes[key] = addTo(csv, button)
    }
    return Plan(writes, writes.size)
  }

  fun planTurnOn(
    current: Map<String, String>,
    clusterKey: String?,
    button: PlayerButton = PlayerButton.DISLIKE,
  ): Plan {
    // 记录里没有、或记录的簇已经不存在（老版本偏好）⇒ 退回默认簇
    val target =
      clusterKey?.takeIf { key -> CONTROL_KEYS.contains(key) && current.containsKey(key) }
        ?: DEFAULT_CLUSTER
    val existing = current[target].orEmpty()
    val next = addTo(existing, button)
    if (next == existing) return Plan(emptyMap(), 0)
    return Plan(mapOf(target to next), 1)
  }
}