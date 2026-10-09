package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore

class AdvancedPreferences(
  preferenceStore: PreferenceStore,
) {
  val mpvConfStorageUri = preferenceStore.getString("mpv_conf_storage_location_uri")
  val mpvConf = preferenceStore.getString("mpv.conf")
  val inputConf = preferenceStore.getString("input.conf")

  val verboseLogging = preferenceStore.getBoolean("verbose_logging", BuildConfig.BUILD_TYPE != "release")

  val enableRecentlyPlayed = preferenceStore.getBoolean("enable_recently_played", true)

}

/*
 * 「默认统计页面」偏好（键 `enabled_stats_page`）**已在清单 #19 连同 UI 一起删除**。
 *
 * ⚠️ 别把它加回来：设置页的六个 chip（关闭 + 第 1~5 页）早已整块移除，
 * 而 [app.marlboroadvance.mpvex.ui.player.MPVView.postInitOptions] 里那段
 * 「按这个值自动弹出 mpv 统计浮层」的代码也一并删了。两种恢复方式都是坑：
 *  · 只恢复读 → 升级用户（本地还留着旧值）每次播片都凭空弹出一个没法关的浮层；
 *  · 只恢复写 → 恢复了没人读的死偏好。
 *
 * 需要统计浮层就写进自己的 `mpv.conf`：
 * `script-binding stats/display-stats-toggle`
 */
