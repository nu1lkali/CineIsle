package app.marlboroadvance.mpvex.domain.emby

/**
 * `.strm` 直链播放的结果。
 *
 * Emby 对 `.strm` 条目的 `PlaybackInfo.MediaSources[].Path` 给的是 **strm 文件里那一行
 * 远端地址**（`Protocol=Http` / `IsRemote=true`），而不是服务器上的 `.strm` 路径。
 * 把这条地址直接交给播放器，就可以绕开 `/Videos/{id}/stream` 中转。
 *
 * @property requiredHeaders 服务器认为拉这条远端地址**必须**带的请求头
 *   （`MediaSources[].RequiredHttpHeaders`，通常是 Referer / User-Agent）。
 *   为空不代表不需要，而是服务端没声明；调用方仍会补一个默认 UA。
 */
data class StrmDirectPlayInfo(
  val url: String,
  val requiredHeaders: Map<String, String> = emptyMap(),
)

/**
 * `.strm` 直链的纯函数判定与请求头整理。
 *
 * 单独抽出来是为了可单测：这些规则一旦错了，表现是「开关打开后所有 strm 都播不了」，
 * 而不是某个角落的小毛病。
 */
object StrmDirectLink {
  /**
   * 这个路径是不是 `.strm` 文件。
   *
   * ⚠️ 必须用**条目自身的 Path**，不能只看 `MediaSources[].Container`：
   * Emby 在第一次播放后会把容器从 `strm` 改成真实容器（`mp4` / `mkv`…），
   * 而条目 Path 始终是 `xxx.strm`（Emby 官方开发者在论坛里确认过这个口径）。
   */
  fun isStrmPath(path: String?): Boolean {
    val value = path?.trim()?.trim('"', '\'')?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val withoutQuery = value.substringBefore('?').substringBefore('#')
    return withoutQuery.endsWith(".strm", ignoreCase = true)
  }

  /**
   * 从 `PlaybackInfo` 的 `MediaSources[].Path` 取直链。
   *
   * 只接受 `http(s)://`：strm 里还允许 `rtsp://` / `mms://` 甚至本地路径，
   * 那些交给引擎自己处理反而更容易出错（mpv 之外的引擎支持面也不一致），
   * 这里直接返回 null ⇒ 回退正常的 Emby 流地址。
   */
  fun directUrl(rawPath: String?): String? {
    val value = rawPath
      ?.trim()
      ?.trim('"', '\'', ' ', '\n', '\r', '\t')
      ?.takeIf { it.isNotEmpty() }
      ?: return null
    return value.takeIf {
      it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }
  }

  /**
   * 把 `RequiredHttpHeaders` 整理成播放页认得的 `headers` extra 格式：
   * `[User-Agent, ua, key, value, …]`。
   *
   * - `User-Agent` 永远放在第 0/1 位 —— 三个内核的解析都靠这个位置；
   * - 服务端没声明 UA 时补 [fallbackUserAgent]（网盘签名常把 UA 算进去，
   *   不能让播放器用自己的默认 UA 去撞）；
   * - 其余头保持原顺序成对追加（VLC 只认前两对，mpv / GSY 能全部用上）。
   */
  fun headerArray(
    requiredHeaders: Map<String, String>?,
    fallbackUserAgent: String,
  ): Array<String> {
    val userAgent = requiredHeaders
      ?.entries
      ?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
      ?.value
      ?.takeIf { it.isNotBlank() }
      ?: fallbackUserAgent

    val result = ArrayList<String>(2 + (requiredHeaders?.size ?: 0) * 2)
    result.add("User-Agent")
    result.add(userAgent)
    // Referer 尽量紧跟 UA：VLC 只解析前两对（0/1、2/3），
    // 其余内核能读完整数组，但把 Referer 放进前四位对所有内核都最稳。
    requiredHeaders
      ?.entries
      ?.firstOrNull { it.key.equals("Referer", ignoreCase = true) || it.key.equals("Referrer", ignoreCase = true) }
      ?.value
      ?.takeIf { it.isNotBlank() }
      ?.let {
        result.add("Referer")
        result.add(it)
      }
    requiredHeaders?.forEach { (key, value) ->
      if (value.isBlank()) return@forEach
      if (key.equals("User-Agent", ignoreCase = true)) return@forEach
      if (key.equals("Referer", ignoreCase = true) || key.equals("Referrer", ignoreCase = true)) {
        return@forEach
      }
      result.add(key)
      result.add(value)
    }
    return result.toTypedArray()
  }
}
