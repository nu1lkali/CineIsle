package app.marlboroadvance.mpvex.domain.emby

import okhttp3.RequestBody.Companion.toRequestBody

// ════════════════════════════════════════════════════════════════════════
// Emby 在线字幕（清单 1.5 拆分第 3 刀，从 EmbyClient.kt 独立出来）
// 零行为变更：EmbyClient 里保留同名转发，全项目调用点一行不用改。
// 这块之所以值得单独成文件：它是全项目**唯一**一处「三段式外部资源流程」
// （搜 → 让服务端下载 → 取字节），而三段里每一段都有独立的失败原因
// （提供方没配 / 服务端下不下来 / 格式转换失败），排查时需要能一次看完三段。
// ════════════════════════════════════════════════════════════════════════

/**
 * 在线字幕的搜索、下载、取字节。
 *
 * 依赖 [EmbyClient] 的 `internal` 工具（`authedRequest` / `getJson` / `postJson`），
 * 同样必须写全限定名 —— `internal` 不等于「自动导入」。
 */
object EmbySubtitles {
  // ════════════════════════════════════════════════════════════════════════
  // 5.6 字幕在线搜索（Emby 服务端）
  // ════════════════════════════════════════════════════════════════════════
  // ⚠️ 与上面那个 [subtitleUrl]（拼 URL 把**服务端已有**的字幕轨交给 mpv）是两回事：
  // 这里是「去搜字幕提供方 → 下载到服务器 → 再取字节」的三段式流程。
  // **为什么必须让服务端下载，不能客户端直接抓字幕提供方的 URL**：
  // 搜出来的结果里只有 `Id`（提供方内部的 ID），**没有可直连的下载地址** ——
  // 真要自己下，就得自己实现各提供方的 API 协议（OpenSubtitles 需要 ApiKey、
  // 某些要登录态）。让 Emby 服务端去下，我们只传 Id，这是唯一稳妥的路径。

  /**
   * 搜索某个媒体的在线字幕候选。
   *
   * `GET /Items/{Id}/RemoteSearch/Subtitles/{Language}`
   *
   * @param language 三字母语言码（`chi` / `eng` / `jpn`…），**大小写敏感的 path 段**。
   *   ⚠️ **没有「一次搜多语言」的端点** —— 要搜中英双轨就得发两次请求，
   *   所以调用方（ViewModel）负责循环 + 合并。
   * @param mediaSourceId 媒体源 Id。**参考客户端把它标成 (required)，但实际是 query 参数**；
   *   不传时服务端会用默认媒体源。对多版本（4K/1080P 各自带不同字幕）必须传对。
   * @param isPerfectMatch 只看「与文件名哈希精确匹配」的（质量最高、通常只 0~3 条）。
   * @return 候选列表；服务端没配任何字幕提供方时返回**空列表而不是报错**，
   *   这是正常状态（用户没在 Emby 后台开字幕插件）。
   */
  fun searchRemoteSubtitles(
    server: EmbyServer,
    itemId: String,
    language: String,
    mediaSourceId: String? = null,
    isPerfectMatch: Boolean? = null,
    isForced: Boolean? = null,
    isHearingImpaired: Boolean? = null,
  ): List<EmbyRemoteSubtitle> {
    // language 走 path 段，必须编码（虽然三字母码实际都是 ASCII，但别赌）
    val path = "/Items/$itemId/RemoteSearch/Subtitles/${java.net.URLEncoder.encode(language, "UTF-8")}"
    return EmbyClient.getJson(
      server = server,
      path = path,
      query = buildMap {
        put("MediaSourceId", mediaSourceId)
        put("IsPerfectMatch", isPerfectMatch?.toString())
        put("IsForced", isForced?.toString())
        put("IsHearingImpaired", isHearingImpaired?.toString())
      },
    )
  }

  /**
   * 让服务端把选中的在线字幕**存到媒体库里**，返回存好后的字幕轨索引。
   *
   * `POST /Items/{Id}/RemoteSearch/Subtitles/{SubtitleId}`
   *
   * 存好之后就能用 [subtitleUrl] 拿字节了 —— 也就是说，**这一调用顺带完成了
   * 「服务端侧持久化」**：别的设备（网页端、电视端）打开同一部片子也能用到这条字幕。
   * 客户端再单独存一份到本地字幕目录，是为了离线播放和跨内核。
   *
   * @return 新字幕轨的 index；服务端没返回时返回 null（调用方要能接受这个失败）
   */
  fun downloadRemoteSubtitle(
    server: EmbyServer,
    itemId: String,
    subtitleId: String,
    mediaSourceId: String? = null,
  ): EmbySubtitleDownloadResult =
    EmbyClient.postJson(
      server = server,
      path = "/Items/$itemId/RemoteSearch/Subtitles/$subtitleId",
      bodyJson = "",
      query = buildMap { put("MediaSourceId", mediaSourceId) },
    )

  /**
   * 取已存到服务器的字幕字节。
   *
   * 与 [subtitleUrl] 的差别只是这里真的**发请求拿字节**（而不是拼 URL 交给 mpv）。
   *
   * `GET /Items/{Id}/{MediaSourceId}/Subtitles/{Index}/Stream.{Format}`
   * （参考客户端还有个 `/Videos/...` 的等价形式，两者服务端都认，这里统一用 `Items`）
   *
   * @param format 服务端支持的格式：`srt` / `ass` / `ssa` / `vtt` / `srt` 等。
   *   注意 Emby 的转换能力因服务端而异，**拿不到时应该回退 srt**（最通用的目标格式）。
   */
  fun downloadSubtitleStream(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    subtitleIndex: Int,
    format: String = "srt",
  ): ByteArray {
    val req = EmbyClient.authedRequest(
      server,
      "/Items/$itemId/$mediaSourceId/Subtitles/$subtitleIndex/Stream.$format",
    ).get().build()
    EmbyClient.httpClient.newCall(req).execute().use { resp ->
      val bytes = resp.body.bytes()
      if (!resp.isSuccessful) {
        throw EmbyApiException(
          resp.code,
          "字幕下载失败（HTTP ${resp.code}）：" +
            String(bytes, Charsets.UTF_8).take(120).ifBlank { resp.message },
        )
      }
      if (bytes.isEmpty()) throw EmbyApiException(resp.code, "服务端返回了空字幕")
      return bytes
    }
  }
}
