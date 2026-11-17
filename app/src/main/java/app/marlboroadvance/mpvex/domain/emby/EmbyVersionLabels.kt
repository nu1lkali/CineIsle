package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 多版本媒体的「本机版本名」。
 *
 * ## 为什么只能存在本地
 *
 * Emby 的 `MergeVersions` / `MediaSources` API **没有版本名写入参数**：版本下拉里显示的
 * 名字是服务端根据文件名自动推出来的，而且会剥掉多个文件的公共前缀
 * （`DGCEMD-229-4K` / `DGCEMD-229` 可能只剩 `229-4K` / `229`）。App 不能、也不应该
 * 去改名或写 Emby 数据库，所以「手动补全版本名」只作为**本机显示别名**：
 *  - 详情页版本下拉、媒体信息卡、播放页顶栏都用它；
 *  - 换设备 / 重装 App 不会同步，也不影响 Emby 网页端；
 *  - 清空后立刻退回 Emby 原始名称。
 *
 * ## 键的形状
 *
 * ```
 * emby_version_label:<serverId>:<itemId>:<mediaSourceId>
 * ```
 *
 * 三段都是稳定 Id（Emby 的 ItemId / MediaSources[i].Id 不含 `:`），因此详情页和播放页
 * 各自算也能算出同一个键。键是动态的，所以复用 [PreferenceStore] 的 `getString(key)`，
 * 不新增表、不改 schema。
 */
class EmbyVersionLabels(
  private val preferenceStore: PreferenceStore,
) {
  companion object {
    const val PREFIX = "emby_version_label:"

    fun key(
      serverId: Long,
      itemId: String,
      mediaSourceId: String,
    ): String = "$PREFIX$serverId:$itemId:$mediaSourceId"
  }

  private val _labels = MutableStateFlow(readAll())
  val labels: StateFlow<Map<String, String>> = _labels.asStateFlow()

  fun label(
    serverId: Long,
    itemId: String,
    mediaSourceId: String,
  ): String? = _labels.value[key(serverId, itemId, mediaSourceId)]

  /** 保存自定义名；传入空白等价于清除。 */
  fun set(
    serverId: Long,
    itemId: String,
    mediaSourceId: String,
    label: String,
  ) {
    val pref = preferenceStore.getString(key(serverId, itemId, mediaSourceId))
    val trimmed = label.trim()
    if (trimmed.isEmpty()) pref.delete() else pref.set(trimmed)
    _labels.value = readAll()
  }

  fun clear(
    serverId: Long,
    itemId: String,
    mediaSourceId: String,
  ) = set(serverId, itemId, mediaSourceId, "")

  private fun readAll(): Map<String, String> =
    preferenceStore
      .getAll()
      .mapNotNull { (key, value) ->
        if (key.startsWith(PREFIX) && value is String && value.isNotBlank()) key to value else null
      }
      .toMap()
}
