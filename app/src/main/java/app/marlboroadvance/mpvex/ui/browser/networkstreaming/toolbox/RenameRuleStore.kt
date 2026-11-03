package app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 批量重命名规则链的持久化 —— 对应 flut-renamer 的 `RulePersistence`
 * （启动自动 loadRules、每次改规则立刻 saveRules 写回临时 YAML）。
 *
 * 这里把整条 [RenameRuleBook]（规则链 + 上次添加的类型 + 范围开关）序列化成一份
 * JSON 落进项目的 PreferenceStore：与其余偏好共用同一份存储、同一套默认值口径，
 * 备份 / 清空设置时也一起处理，不必自己再开一个文件。
 *
 * ⚠️ 反序列化一律兜底到默认规则本：旧版本数据、手改坏的数据、新增规则类型导致
 *    的不兼容，都不能让工具箱面板打不开 —— 最坏情况只是「规则没了」，而不是崩。
 */
class RenameRuleStore(preferenceStore: PreferenceStore) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val bookPref =
    preferenceStore.getObject(
      key = KEY,
      defaultValue = RenameRuleBook(),
      serializer = { book -> json.encodeToString(book) },
      deserializer = { raw ->
        runCatching { json.decodeFromString<RenameRuleBook>(raw) }
          .getOrDefault(RenameRuleBook())
      },
    )

  fun book(): RenameRuleBook = bookPref.get()

  /** 每次增删改 / 拖动排序后立刻调用 —— 这就是「规则能够记忆」的落盘动作 */
  fun save(book: RenameRuleBook) {
    bookPref.set(book)
  }

  private companion object {
    const val KEY = "alist_rename_rule_book"
  }
}
