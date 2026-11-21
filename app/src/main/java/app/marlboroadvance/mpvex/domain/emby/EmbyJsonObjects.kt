package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ════════════════════════════════════════════════════════════════════════
// JSON 构造辅助（从 EmbyClient 末尾原样搬出，可见性 private → internal）
// ════════════════════════════════════════════════════════════════════════
//
// 原先 EmbyClient 与 EmbySessionApi 各持一份完全相同的实现，拆分后合并到这里，
// 保证「同一段 body 拼装口径」只有一个来源。实现逐字未改。

/** kotlinx.serialization 的 JsonObject 构造辅助（避免每次写 JsonObject(mapOf(...)) 套娃） */
internal fun jsonObjectOf(entries: Map<String, Any?>): JsonObject {
  val map = LinkedHashMap<String, JsonElement>()
  entries.forEach { (k, v) ->
    val e = when (v) {
      null -> JsonNull
      is String -> JsonPrimitive(v)
      is Number -> JsonPrimitive(v)
      is Boolean -> JsonPrimitive(v)
      else -> JsonPrimitive(v.toString())
    }
    map[k] = e
  }
  return JsonObject(map)
}
