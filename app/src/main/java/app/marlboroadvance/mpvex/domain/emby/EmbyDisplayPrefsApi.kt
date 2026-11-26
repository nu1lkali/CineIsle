package app.marlboroadvance.mpvex.domain.emby

import kotlinx.serialization.encodeToString
import okhttp3.RequestBody.Companion.toRequestBody

// ════════════════════════════════════════════════════════════════════════
// 显示偏好（DisplayPreferences）
// 从 EmbyClient 原样搬出（重构：god-class 拆分），行为零变化
// ════════════════════════════════════════════════════════════════════════
//
// ⚠️ 三条容易踩空的真相，实现前务必先读完：
// 1. **粒度是「每个目标」而不是「每个用户」**。落点的Id 叫
//    `DisplayPreferencesId`，来自 `BaseItemDto.DisplayPreferencesId`，
//    媒体库和单条目各有一份。所以不存在「一个用户一份显示偏好」这种写法 ——
//    想让整个 App 的偏好跟着走，就得遍历所有目标库逐个写（见 EmbyDisplayPrefsSync）。
// 2. **`Client` 是必填的分区键**，不是可选的 UA。`GET` 时它是 query 参数
//    （`POST` 时它只在 body 里，见下），取不同值就读到不同的偏好副本。
//    本 App 用 [EmbyClient.DISPLAY_PREFS_CLIENT] 作为一个**独立分区**，
//    好处是绝不会把 Emby Web 的排版偏好冲掉；代价是**不与网页端互通**，
//    这是有意取舍，不是没做完。
// 3. **不要写到 `/Users/{Id}/Configuration`**。`UserConfiguration` 的 17 个字段
//    （音频语言 / 字幕语言 / ProfilePin / 跳过片头 / 重播剩余秒数…）**全是播放类**，
//    一个显示字段都没有，网格密度写不进去。
// 参数位置逐条照抄 SDK（`DisplayPreferencesServiceApi`）：
//   GET  /DisplayPreferences/{Id}?UserId&Client← 只有 {Id} 在路径里
//   POST /DisplayPreferences/{DisplayPreferencesId}?UserId
//        body = 整个 DisplayPreferences（含 Client），**返回 Void（空 body）**

object EmbyDisplayPrefsApi {
  /**
   * 读某个目标（媒体库 / 条目）的显示偏好。
   *
   * 目标不存在时部分部署返回 **404**，也有返回空对象的 —— 两种都当「还没有偏好」处理，
   * 由调用方决定是回落默认值还是提示用户。
   */
  internal fun getDisplayPreferences(
    server: EmbyServer,
    displayPreferencesId: String,
    client: String,
  ): EmbyDisplayPreferences? = EmbyHttpCore.getJson(
    server,
    EmbyPaths.displayPreferences(displayPreferencesId),
    mapOf(
      "UserId" to server.userId,
      "Client" to client,
    ),
  )

  /**
   * 写某个目标的显示偏好。**整体覆盖**（不是增量合并），所以调用方必须
   * 先读出原有 `CustomPrefs` 再把自己的键merge 进去，否则会把别的客户端
   * 或本客户端的其它键一起抹掉。
   *
   * 返回 true = 服务端收下了。注意这条路径**成功时返回空 body**，
   * 不能拿去反序列化 —— 所以这里只判 HTTP 状态码。
   */
  internal fun postDisplayPreferences(
    server: EmbyServer,
    displayPreferencesId: String,
    client: String,
    preferences: EmbyDisplayPreferences,
  ): Boolean {
    val body = EmbyHttpCore.json.encodeToString(preferences)
    val req = EmbyHttpCore.authedRequest(
      server,
      EmbyPaths.displayPreferences(displayPreferencesId),
      mapOf("UserId" to server.userId),
    )
      .post(body.toRequestBody(EmbyHttpCore.jsonMedia))
      .header("Accept", "application/json")
      .build()
    EmbyHttpCore.httpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        throw EmbyApiException(resp.code, resp.body.string().ifEmpty { resp.message })
      }
    }
    return true
  }
}
