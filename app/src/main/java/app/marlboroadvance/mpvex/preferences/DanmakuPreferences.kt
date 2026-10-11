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

  /** 屏蔽词（英文逗号分隔，大小写不敏感）。 */
  val blockWords = preferenceStore.getString("danmaku_block_words", "")
}