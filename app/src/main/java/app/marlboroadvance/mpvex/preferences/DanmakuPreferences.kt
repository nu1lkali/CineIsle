package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.danmaku.DanmakuClient
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
// ⚠️ 只有 `getEnum` 是**顶层扩展函数**（PreferenceStore.kt 里定义的），
// `getString/getInt/getFloat/getBoolean` 都是 `PreferenceStore` 的**接口成员**，
// 靠 import 导入会报 Unresolved reference —— 调用它们只需要一个 PreferenceStore 实例。
import app.marlboroadvance.mpvex.preferences.preference.getEnum

/** 弹幕渲染引擎。 */
enum class DanmakuRenderingEngine {
  /** 走 DanmakuFlameMaster（默认）。 */
  Native,
  /** 关掉：不加载、不渲染，只在设置里留着「记住我关过」。 */
  Off,
}

/**
 * 弹幕的显示密度档位。
 *
 * 只给三档而不是连续滑块：密度直接决定同屏条数，而同屏条数一旦拉满，
 * 每帧 Canvas 上的文字绘制量会上一个量级，低配机掉帧明显。
 * 想调宁可调字号，别调密度。
 */
enum class DanmakuDensity(val labelRes: Int) {
  Sparse(R.string.danmaku_density_sparse),
  Normal(R.string.danmaku_density_normal),
  Dense(R.string.danmaku_density_dense),
}

/**
 * 弹幕偏好。
 *
 * ⚠️ 两个坑：
 * 1. **枚举的持久化用的是 `Enum.name`**（见 `PreferenceStore.getEnum` 的 `serializer = { it.name }`），
 *    所以**枚举项一旦发布就不能改名** —— 改名等于静默把所有用户的设置重置回默认值。
 * 2. **改默认值只对全新用户生效**（老用户本地已存过值）。将来要改默认值，
 *    得换 key 后缀（如 `_v2`）或在 `App.onCreate` 里做一次性播种。
 */
class DanmakuPreferences(preferenceStore: PreferenceStore) {

  /** 总开关。默认关 —— 弹幕要额外联网 + 额外一层绘制，不能替用户默认打开。 */
  val enabled = preferenceStore.getBoolean("danmaku_enabled", false)

  /** 渲染引擎。 */
  val engine = preferenceStore.getEnum("danmaku_engine", DanmakuRenderingEngine.Native)

  /** 弹幕源 host。空串 = 用 [DanmakuClient.DEFAULT_HOST]。 */
  val host = preferenceStore.getString("danmaku_host", DanmakuClient.DEFAULT_HOST)

  /**
   * 开放平台 AppId / AppSecret（签名模式）。
   *
   * ⚠️ 默认值取自 `BuildConfig`，而 BuildConfig 的值来自**构建机上的 local.properties**
   * （已被 .gitignore 忽略）—— 所以仓库里没有任何密钥，发布出去的 APK 里却有。
   * 想彻底不带密钥出包：把 local.properties 里那两行删掉重新编即可，代码不用改。
   *
   * 用户在面板里手填的值会**覆盖**这两个默认值（存进 SharedPreferences），
   * 所以换机器构建、别人 clone 下来编，各自填自己的即可。
   *
   * 两者都为空 ⇒ 不发签名头。这**不是 bug**，而是刻意保留的路：
   * 自建 `danmu_api` 服务不校验签名；审核没过时也可先走这条。
   */
  val appId = preferenceStore.getString("danmaku_app_id", app.marlboroadvance.mpvex.BuildConfig.DANDAN_APP_ID)

  val appSecret = preferenceStore.getString(
    "danmaku_app_secret",
    app.marlboroadvance.mpvex.BuildConfig.DANDAN_APP_SECRET,
  )

  /** 是否按当前剧集名自动匹配（关掉就只能手动搜）。 */
  val autoMatch = preferenceStore.getBoolean("danmaku_auto_match", true)

  /**
   * 合并「关联剧集」的弹幕（对标 Yamby 的 `danmaku_with_related`）。
   *
   * dandanplay 的 `/comment/{id}?withRelated=true` 会把同一集在
   * 不同字幕组版本 / SP / OVA / 分拆放送 下的弹幕一并返回，密度明显更高。
   *
   * ## 为什么默认开
   *
   * 中文弹幕站的实际生态是「一集被多个字幕组分发」，只取当前这一条时间轴
   * 往往只有几十条；开了之后常见几百上千条 —— 这正是弹幕该有的样子。
   *
   * ## 为什么做成开关
   *
   * 关联剧集偶尔包含「另一个剪辑版」（比如 TV 版与 BD 版片长差几分钟），
   * 那部分弹幕的时间点会整体偏移。介意这份"串味"的用户可以关掉。
   */
  val withRelated = preferenceStore.getBoolean("danmaku_with_related", true)

  /** 显示区域占画面高度的比例（0.25 / 0.5 / 0.75 / 1.0）。 */
  val areaRatio = preferenceStore.getFloat("danmaku_area_ratio", 0.5f)

  /** 透明度百分比，10~100。 */
  val alphaPercent = preferenceStore.getInt("danmaku_alpha", 100)

  /** 字号缩放，0.5~2.0。乘在弹幕自带的字体档位上。 */
  val fontScale = preferenceStore.getFloat("danmaku_font_scale", 1.0f)

  /** 速度倍数，0.5~2.0。>1 更快。 */
  val speedScale = preferenceStore.getFloat("danmaku_speed_scale", 1.0f)

  /** 同屏密度。 */
  val density = preferenceStore.getEnum("danmaku_density", DanmakuDensity.Normal)

  /**
   * 防止弹幕重叠（对标 Yamby 的 `prevent_danmaku_overlapping`）。
   *
   * 打开后，库在分配轨道时会**检查新弹幕与同轨道已有弹幕是否真的会追上/叠住**
   * （`DanmakuContext.preventOverlapping` → `BiliDanmakuFilter`），叠住了就换轨道；
   * 关着则只按「轨道宽度是否够」粗判，高速弹幕或长短不一的情况下会撞在一起。
   *
   * ## 为什么默认开
   *
   * 关闭的收益只有一个：同屏能多挤几条。代价是会出现「字压字、两条都读不清」——
   * 这比少几条弹幕难受得多。真正的密度诉求请调 [density]。
   *
   * ⚠️ 这个开关**不需要重新 prepare**：`applyDisplayConfig` 每次显示参数变化都会
   * 重新写入 `DanmakuContext`，库内部按 `isPreventOverlappingEnabled()` 现读。
   */
  val preventOverlapping = preferenceStore.getBoolean("danmaku_prevent_overlapping", true)

  /** 屏蔽词（英文逗号分隔，大小写不敏感）。 */
  val blockWords = preferenceStore.getString("danmaku_block_words", "")

  /**
   * 手动选过的番要不要「记住」——切集时沿用同一部番，而不是重新按片名自动匹配。
   *
   * ## 为什么这是个开关而不是恒开
   *
   * 恒开会带来一个很难理解的副作用：用户手动纠错过一次（比如自动匹配到了同名的
   * 真人版），之后**每一集**都沿用那个错的选择，除非他再手动改回来。
   * 恒关则相反：手动挑好的番切到下一集就被冲掉，每集都要手点一次。
   *
   * 两种行为都有人需要，所以做成开关、默认开 —— 手动选一次多半意味着
   * 「自动匹配对我这部片不好用」，那整季沿用才是他要的。
   */
  val rememberSelection = preferenceStore.getBoolean("danmaku_remember_selection", true)

  /**
   * 时间轴偏移（毫秒，正数 = 弹幕延后出现）—— **全局默认值**。
   *
   * 解决的是「弹幕时间轴和片源差几秒」——蓝光原盘多一段片头、
   * 或弹幕源扒的是别的版本时非常常见。Yamby 把这项叫 `danmaku_offset`。
   *
   * ⚠️ 真正生效的值是「按剧记忆的覆盖」优先于这个全局值，见 [timeOffsetFor]。
   */
  val timeOffsetMs = preferenceStore.getInt("danmaku_time_offset_ms", 0)

  /**
   * 每部剧单独记的偏移：`seriesKey -> 毫秒`，序列化成 `key=ms;key=ms`。
   *
   * ## 为什么必须按剧记
   *
   * 偏移是「片源与弹幕源的版本差」，**这是片子的属性**，不是用户的偏好：
   * 某部蓝光原盘多了一段 40 秒的播出前回顾 ⇒ 那部片要 -40000；
   * 换一部正常的 web 源 ⇒ 立刻要 0。全局值做不到，用户每换片都得手动清零。
   *
   * ## 为什么用字符串而不是多个 Preference 项
   *
   * PreferenceStore 没有 map 类型。拼成 `k=v;k=v` 一行，读时 split 一次即可 ——
   * 条目数是个位数（用户真正会手动校正的片子很少），不值得为它建一张 Room 表。
   *
   * ⚠️ seriesKey 是 Emby 的 ItemId（32 位十六进制）或本地片的路径 hash，
   * 不会含 `;` / `=`，所以分隔符是安全的。
   */
  val seriesTimeOffsets = preferenceStore.getString("danmaku_series_offsets", "")

  /**
   * 取某部剧实际生效的偏移：**按剧覆盖 > 全局默认**。
   *
   * @param seriesKey 剧的记忆键（[PlayerActivity.currentSeriesKey] 那一套）；
   *   为 null（本地播放 / 非 Emby）时只能用全局值 —— 没有稳定的剧标识可挂。
   */
  fun timeOffsetFor(seriesKey: String?): Int {
    if (seriesKey.isNullOrBlank()) return timeOffsetMs.get()
    return parseSeriesOffsets(seriesTimeOffsets.get())[seriesKey] ?: timeOffsetMs.get()
  }

  /** 某部剧是否单独设过偏移（用来在面板里显示「已按本剧记住」）。 */
  fun hasSeriesOffset(seriesKey: String?): Boolean =
    !seriesKey.isNullOrBlank() && parseSeriesOffsets(seriesTimeOffsets.get()).containsKey(seriesKey)

  /**
   * 给某部剧写入偏移。
   *
   * @param ms null = **删掉这条覆盖**，回落到全局值（用户点「跟随全局」时走这条）。
   */
  fun setSeriesOffset(seriesKey: String?, ms: Int?) {
    if (seriesKey.isNullOrBlank()) {
      // 没有剧标识（本地播放）时只能改全局值 —— 否则用户拖了滑杆什么都没发生。
      if (ms != null) timeOffsetMs.set(ms)
      return
    }
    val map = parseSeriesOffsets(seriesTimeOffsets.get()).toMutableMap()
    if (ms == null) map.remove(seriesKey) else map[seriesKey] = ms
    seriesTimeOffsets.set(serializeSeriesOffsets(map))
  }

  private fun parseSeriesOffsets(raw: String): Map<String, Int> {
    if (raw.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, Int>()
    for (entry in raw.split(';')) {
      val i = entry.indexOf('=')
      if (i <= 0) continue
      val key = entry.substring(0, i)
      val value = entry.substring(i + 1).toIntOrNull() ?: continue
      out[key] = value
    }
    return out
  }

  private fun serializeSeriesOffsets(map: Map<String, Int>): String =
    map.entries.joinToString(";") { "${it.key}=${it.value}" }
}