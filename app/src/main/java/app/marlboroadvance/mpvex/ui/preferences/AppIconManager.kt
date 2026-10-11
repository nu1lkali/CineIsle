package app.marlboroadvance.mpvex.ui.preferences

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import app.marlboroadvance.mpvex.MainActivity

/**
 * 桌面图标切换。
 *
 * ## 为什么能换
 *
 * Android 的桌面图标**只能是 APK 内的静态资源** —— 从网络下载的图不能当 launcher icon。
 * 所以「换图标」只有一条路：为每套图标建一个 `activity-alias`（同一个 MainActivity、
 * 各自一套 `android:icon` / `android:roundIcon`），再在运行时用
 * [PackageManager.setComponentEnabledSetting] **启用一个、禁用其余**。
 * 桌面会立刻刷新成新图标（多数启动器会，部分需等桌面刷新或重启桌面）。
 *
 * ⚠️ 两个必须知道的限制：
 * 1. 同一时刻**只能有一个**入口是 enabled，所以必须把其余全部禁用；
 *    只禁用同名而不 disable 其它 alias ⇒ 桌面出现两个图标。
 * 2. 切换会**杀掉本应用进程**（改组件启用状态属于包管理变更）。
 *    所以必须写盘后再切，且切完由系统重新拉起。
 *
 * ## ⚠️ 组件名必须用**相对名**（`.MainActivityAliasXxx`），不能写全限定名
 *
 * 本项目 `applicationId`（`app.cineisle.player`，装到手机上的包名）与 `namespace`
 * （`app.marlboroadvance.mpvex`，代码/R 类的包名）**故意不同**。
 * 而 `AndroidManifest` 里组件名的包前缀由 **namespace** 补全 ——
 * 实际打进 APK 的是 `app.marlboroadvance.mpvex.MainActivityAlias*`，
 * **不是** applicationId。
 *
 * 之前这里硬编码了 `app.cineisle.player.MainActivityAlias*` ⇒
 * `setComponentEnabledSetting` 作用在**不存在的组件**上、异常又被 catch 吞掉
 * ⇒ 表现为「点了图标完全没反应」。
 *
 * ⇒ 现在只存相对名，运行时用 [mainActivityClass] 的真实包前缀拼。
 * 这样以后 namespace 或 applicationId 任一改动都不会再坏
 *（同理呼应红线：凡是要与构建产物对齐的标识，都得由代码自己重建，别手抄）。
 */
object AppIconManager {

  /**
   * 可选图标。
   *
   * [alias] 必须与 [AndroidManifest] 里 `<activity-alias android:name=...>` 完全一致。
   * ⚠️ `storageKey` 会写进 SharedPreferences，**发布后不可改名**（改名等于静默重置）。
   */
  enum class Style(
    val storageKey: String,
    val labelRes: Int,
    /** 预览用：adaptive icon 的背景色资源名。 */
    val backgroundColorRes: String,
    /** 预览用：前景 drawable 资源名。 */
    val foregroundRes: String,
    /**
     * `activity-alias` 的**相对名**（如 `.MainActivityAliasAmber`），与 AndroidManifest
     * 里写的一致。不要写全限定名 —— 见本文件顶部「组件名」那段说明。
     * 空串 = 默认图标走 MainActivity 自身，不走 alias。
     */
    val aliasRelative: String,
  ) {
    Default(
      "default",
      app.marlboroadvance.mpvex.R.string.app_icon_default,
      "ic_launcher_background",
      "ic_launcher_foreground",
      // 默认图标用 MainActivity 自己，不走 alias（少一层组件，也少一个出错点）
      "",
    ),
    DeepSea(
      "deepsea",
      app.marlboroadvance.mpvex.R.string.app_icon_deepsea,
      "app_icon_deepsea_background",
      "ic_launcher_deepsea_foreground",
      ".MainActivityAliasDeepSea",
    ),
    Midnight(
      "midnight",
      app.marlboroadvance.mpvex.R.string.app_icon_midnight,
      "app_icon_midnight_background",
      "ic_launcher_midnight_foreground",
      ".MainActivityAliasMidnight",
    ),
    Minimal(
      "minimal",
      app.marlboroadvance.mpvex.R.string.app_icon_minimal,
      "app_icon_minimal_background",
      "ic_launcher_minimal_foreground",
      ".MainActivityAliasMinimal",
    ),
    Amber(
      "amber",
      app.marlboroadvance.mpvex.R.string.app_icon_amber,
      "app_icon_amber_background",
      "ic_launcher_amber_foreground",
      ".MainActivityAliasAmber",
    ),
    ;

    val isAlias: Boolean get() = aliasRelative.isNotBlank()

    companion object {
      fun fromKey(key: String?): Style = entries.firstOrNull { it.storageKey == key } ?: Default
    }
  }

  private const val PREFS = "app_icon"
  private const val KEY_STYLE = "style"
  private const val APPLYING = "applying"
  private const val TAG = "AppIconManager"

  /**
   * MainActivity 的真实类对象。
   *
   * ⭐ 用 `MainActivity::class.java` 拿**运行期真实全限定名**，而不是手写字符串 ——
   * 这样 manifest 里的包前缀（由 namespace 决定）怎么变都不用改代码。
   */
  private val mainActivityClass: Class<*> get() = MainActivity::class.java

  private val mainComponent: String get() = mainActivityClass.name

  /** 把相对名补成全限定名：`.MainActivityAliasX` → `app.marlboroadvance.mpvex.MainActivityAliasX`。 */
  private fun resolveAlias(relative: String): String =
    mainActivityClass.`package`?.name?.plus(relative).orEmpty()

  /**
   * 自检：清单里真的有这些 alias 吗？
   *
   * ⚠️ 这一段专门为了防「本轮这个 bug」再犯：`setComponentEnabledSetting` 对
   * **不存在**的组件既不抛异常也不生效，所以写错名字时唯一的症状就是
   * 「点了没反应」。这里在真正切换前先查一遍 `PackageManager.getActivityInfo`，
   * 查不到就当作切换失败返回 false（调用方会提示用户），而不是静默吞掉。
   *
   * @return 缺失的 alias 全限定名；全都在则返回空列表
   */
  fun missingAliases(context: Context): List<String> {
    val pm = context.packageManager
    return Style.entries.filter { it.isAlias }.map { resolveAlias(it.aliasRelative) }
      .filter { name ->
        try {
          pm.getActivityInfo(ComponentName(context.packageName, name), 0)
          false
        } catch (t: Throwable) {
          Log.w(TAG, "清单里没有 $name —— 换图标对它无效", t)
          true
        }
      }
  }

  fun currentStyle(context: Context): Style {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // ⚠️ 上次切换中途被杀（切换本身会杀进程）⇒ applying 标记还留着。
    //   此时必须重放一次，否则图标停在「全禁用」= 桌面一个图标都没有。
    if (prefs.getBoolean(APPLYING, false)) {
      val target = Style.fromKey(prefs.getString(KEY_STYLE, null))
      apply(context, target)
    }
    return Style.fromKey(prefs.getString(KEY_STYLE, null))
  }

  /**
   * 切换到 [style]。
   *
   * @return true 表示这次真的改了（false = 已经是它了，什么都不用做）
   */
  fun setStyle(context: Context, style: Style): Boolean {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getString(KEY_STYLE, null) == style.storageKey) return false

    // ⚠️ 先写 applying 再改组件：改组件会杀进程，中间挂掉时下次启动能自己修回来。
    prefs.edit {
      putString(KEY_STYLE, style.storageKey)
      putBoolean(APPLYING, true)
    }
    return apply(context, style)
  }

  /**
   * 真正改组件启用状态。
   *
   * ⚠️ 默认图标（[Style.Default]）走 MainActivity 自身 —— 所以要把**所有 alias 全禁用**、
   * MainActivity 启用；选 alias 时反过来。这两条路径必须互为镜像，否则会留下 0 个或 2 个入口。
   */
  private fun apply(context: Context, style: Style): Boolean {
    val pm = context.packageManager
    val main = ComponentName(context.packageName, mainComponent)
    return try {
      Style.entries.filter { it.isAlias }.forEach { s ->
        pm.setComponentEnabledSetting(
          ComponentName(context.packageName, resolveAlias(s.aliasRelative)),
          if (s == style) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
          else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
          PackageManager.DONT_KILL_APP,
        )
      }
      pm.setComponentEnabledSetting(
        main,
        if (style == Style.Default) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        // ⚠️ 不传 DONT_KILL_APP：我们要的就是「切完重启，桌面才刷新图标」。
        //   传了它某些启动器不会重新读图标。
        0,
      )
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(APPLYING, false) }
      true
    } catch (t: Throwable) {
      // ⚠️ 失败要还原：否则可能停在「MainActivity 被禁用」的半路上 ⇒ 桌面图标消失。
      // 组件切换是系统级调用，个别 ROM 上可能抛 SecurityException。
      Log.w(TAG, "切换图标失败：$style", t)
      runCatching {
        pm.setComponentEnabledSetting(
          main,
          PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
          PackageManager.DONT_KILL_APP,
        )
      }
      false
    }
  }
}