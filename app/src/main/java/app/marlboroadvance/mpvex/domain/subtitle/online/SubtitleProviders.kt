package app.marlboroadvance.mpvex.domain.subtitle.online

import app.marlboroadvance.mpvex.utils.net.HttpClients
import app.marlboroadvance.mpvex.utils.net.RemoteEndpoints
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个在线字幕来源。
 *
 * ## 为什么是接口
 *
 * 两个站点的差异极大：迅雷是 JSON API，SubtitleCat 是 HTML 抓取。抓取那一侧的
 * DOM 结构随时会变 —— 把它关在独立实现里，改坏一个不会连累另一个（需求 2.2 末段）。
 *
 * ## 实现约定
 *
 * · 只在 IO 线程跑（实现内部自己 `withContext(Dispatchers.IO)`）；
 * · **不抛异常**：任何失败都返回空列表并记日志 —— 上层按「这个来源没结果」处理，
 *   而不是整次搜索失败；
 * · 不做缓存，缓存归 [OnlineSubtitleSearchManager]。
 */
interface SubtitleProvider {
  val source: OnlineSubtitleSource

  /** @param keyword 已清洗的关键词（非空）。 */
  suspend fun search(keyword: String): List<OnlineSubtitle>
}

/**
 * 字幕站点专用 HTTP 客户端。
 *
 * 与 Emby 的共享客户端分开：那边带着 `api_key` 拦截器与 `STREAM_USER_AGENT`，
 * 对第三方站点既无用又泄露信息；这里的 UA 要伪装成普通浏览器（站点会拦非浏览器 UA），
 * 超时也要更短（用户在面板上等结果，不能让一个站点拖 60 秒）。
 */
internal object SubtitleHttp {
  /** 抓取用的 UA。站点普遍按 UA 拦爬虫，用桌面浏览器 UA 最稳。 */
  const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

  val client: OkHttpClient by lazy {
    HttpClients.build(
      name = "subtitle-search",
      connectTimeoutSeconds = 12,
      readTimeoutSeconds = 20,
      // 搜索是「等一下就好」的操作，不允许无限重试把面板卡住
      retryOnConnectionFailure = false,
    )
  }

  /** 下载客户端：字幕只有几十 KB，但站点偶尔限速，读超时给宽松一点。 */
  val downloadClient: OkHttpClient by lazy {
    HttpClients.build(
      name = "subtitle-download",
      connectTimeoutSeconds = 15,
      readTimeoutSeconds = 45,
    )
  }

  fun get(url: String): Request =
    Request
      .Builder()
      .url(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
      .get()
      .build()

  /**
   * 只放行 https 且主机名可解析的地址。
   *
   * 目的不是「更安全」这么含糊：站点页面里可能混进 `javascript:` / `file://` /
   * 内网地址（被入侵的页面、广告位），直接交给下载器等于让页面决定我们去访问谁。
   * 需求 12.15 / 12.16 明确要求这一层。
   */
  fun isSafeHttpUrl(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    if (host.isBlank()) return false
    // 内网 / 本机地址：不是字幕站会用的主机，出现即为异常（防被页面引导去访问任意地址）
    if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0") return false
    if (host.endsWith(".local") || host.endsWith(".internal")) return false
    if (isPrivateIpv4(host)) return false
    return true
  }

  /** 10/8、172.16/12、192.168/16 三段私有网段。 */
  private fun isPrivateIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    val second = parts[1].toIntOrNull() ?: return false
    if (parts.any { it.toIntOrNull() == null }) return false
    return when {
      first == 10 -> true
      first == 192 && second == 168 -> true
      first == 172 && second in 16..31 -> true
      first == 169 && second == 254 -> true
      else -> false
    }
  }

  /** 稳定 id：内容地址的短哈希。同一地址必然同一条。 */
  fun idOf(source: OnlineSubtitleSource, url: String): String {
    val digest =
      MessageDigest
        .getInstance("SHA-1")
        .digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)
    return "${source.key}:$digest"
  }
}

/**
 * 迅雷字幕 API。
 *
 * 请求：`GET https://api-shoulei-ssl.xunlei.com/oracle/subtitle?name={keyword}`
 * 响应：`{"code":0,"result":"ok","data":[{"name","extra_name","ext","url"}]}`
 *
 * ## 解析上的保守处
 *
 * · `code != 0` 或 `result != "ok"` ⇒ 视为「没有结果」，不去猜 `data` 的结构；
 * · `data` 既可能是数组也可能是 `{"list":[...]}`（接口换过版本），两种都认；
 * · 只收 https 的 `url`：http 明文地址在 Android 9+ 默认被网络安全策略拦掉，
 *   收下来也只会在下载阶段失败，不如搜的时候就丢掉。
 */
class ThunderSubtitleProvider : SubtitleProvider {
  override val source: OnlineSubtitleSource = OnlineSubtitleSource.THUNDER

  override suspend fun search(keyword: String): List<OnlineSubtitle> =
    withContext(Dispatchers.IO) {
      val url = "$API?name=${URLEncoder.encode(keyword, "UTF-8")}"
      val payload =
        runCatching {
          SubtitleHttp.client.newCall(SubtitleHttp.get(url)).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            response.body.string()
          }
        }.getOrElse { return@withContext emptyList() }

      parse(payload)
    }

  /** 抽成顶层可见函数，便于纯 JVM 单测（见 `OnlineSubtitleParsingTest`）。 */
  fun parse(payload: String): List<OnlineSubtitle> {
    val root = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
    if (root.optInt("code", -1) != 0) return emptyList()
    if (root.optString("result") != "ok") return emptyList()

    val array = dataArray(root) ?: return emptyList()
    val out = ArrayList<OnlineSubtitle>(array.length())
    for (i in 0 until array.length()) {
      val item = array.optJSONObject(i) ?: continue
      val downloadUrl = item.optString("url").trim()
      if (!SubtitleHttp.isSafeHttpUrl(downloadUrl)) continue

      val name = item.optString("name").trim()
      val extra = item.optString("extra_name").trim()
      val title = name.ifBlank { extra }.ifBlank { "迅雷字幕" }
      val format = normalizeFormat(item.optString("ext"))
      out +=
        OnlineSubtitle(
          id = SubtitleHttp.idOf(source, downloadUrl),
          source = source,
          title = title,
          // 落盘名用主标题：`name` 常有完整文件名，`extra_name` 多是语言后缀
          fileName = title,
          format = format,
          // 接口不返回语言字段。**不猜**：`extra_name` 里的 `chs/cht` 是发布组习惯，
          // 并不保证，界面统一显示「语言未知」比猜错强。
          language = null,
          downloadUrl = downloadUrl,
          detailUrl = null,
          fileSizeBytes = item.optLong("size", -1L).takeIf { it > 0 },
        )
    }
    return out
  }

  private fun dataArray(root: JSONObject): JSONArray? {
    val data = root.opt("data") ?: return null
    return when (data) {
      is JSONArray -> data
      is JSONObject -> data.optJSONArray("list") ?: data.optJSONArray("items")
      else -> null
    }
  }

  companion object {
    private const val API = RemoteEndpoints.XUNLEI_SUBTITLE_ENDPOINT

    /** 只认这几种；别的（`sub` / `idx` / 空）一律当「未知格式」，交给内容嗅探。 */
    private val KNOWN = setOf("srt", "ass", "ssa", "vtt", "txt")

    fun normalizeFormat(raw: String?): String? {
      val ext = raw.orEmpty().trim().trimStart('.').lowercase()
      return ext.takeIf { it in KNOWN }
    }
  }
}

/**
 * SubtitleCat 网页抓取。
 *
 * 流程（与用户提供的 Python 脚本一致）：
 * 1. `GET https://subtitlecat.com/index.php?search={keyword}` 拿结果表；
 * 2. 取 `.sub-table tbody tr` 每行第一个 `<a>` 当作详情页；
 * 3. 进详情页按 `#download_zh-CN` → `#download_zh` → `#download_zh-TW` 找中文字幕下载地址。
 *
 * ## 为什么不用 Jsoup
 *
 * 项目里没有这个依赖，为一次抓取引一个新库不划算（需求 13「不要为了架构漂亮制造
 * 不必要的抽象 / 依赖」）。这里只解析两个固定结构，用受限正则足够，且**任何解析
 * 失败都只返回空列表**，不会抛到界面上。
 *
 * ## 为什么详情页最多取 8 个
 *
 * 搜索结果通常一屏十几条，用户真正会点的是前几条。每条详情页都是一次完整请求，
 * 取太多会让搜索变成「等十几秒」。
 */
class SubtitleCatProvider : SubtitleProvider {
  override val source: OnlineSubtitleSource = OnlineSubtitleSource.SUBTITLE_CAT

  override suspend fun search(keyword: String): List<OnlineSubtitle> =
    withContext(Dispatchers.IO) {
      val searchUrl = RemoteEndpoints.subtitleCatSearch(URLEncoder.encode(keyword, "UTF-8"))
      val html =
        runCatching {
          SubtitleHttp.client.newCall(SubtitleHttp.get(searchUrl)).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            response.body.string()
          }
        }.getOrElse { return@withContext emptyList() }

      val rows = parseSearchRows(html).take(MAX_DETAIL_PAGES)
      if (rows.isEmpty()) return@withContext emptyList()

      val out = ArrayList<OnlineSubtitle>(rows.size)
      for (row in rows) {
        // 单条详情页失败只跳过这一条 —— 一条坏页不该毁掉整批结果
        val detail =
          runCatching {
            SubtitleHttp.client.newCall(SubtitleHttp.get(row.detailUrl)).execute().use { response ->
              if (!response.isSuccessful) return@runCatching null
              response.body.string()
            }
          }.getOrNull() ?: continue

        val link = parseChineseDownload(detail) ?: continue
        if (!SubtitleHttp.isSafeHttpUrl(link.url)) continue

        out +=
          OnlineSubtitle(
            id = SubtitleHttp.idOf(source, link.url),
            source = source,
            title = row.title,
            fileName = row.title,
            format = link.format ?: "srt",
            language = link.language,
            downloadUrl = link.url,
            detailUrl = row.detailUrl,
          )
      }
      out
    }

  /** 表格行：标题 + 详情页绝对地址。抽成内部类型便于单测。 */
  internal data class ResultRow(val title: String, val detailUrl: String)

  internal data class DownloadLink(val url: String, val format: String?, val language: String?)

  internal fun parseSearchRows(html: String): List<ResultRow> {
    val out = ArrayList<ResultRow>()
    var inSubTable = false
    // 逐行扫：先定位 `.sub-table`，再抓它内部 `<tr>`。
    // 这样做比「先切整张表再逐行」稳 —— 页面里可能有多张表，只有这一张是结果表。
    html.lineSequence().forEach { line ->
      if (!inSubTable && line.contains("sub-table")) inSubTable = true
      if (!inSubTable) return@forEach
      if (!line.contains("<tr", ignoreCase = true)) return@forEach

      val anchor = FIRST_ANCHOR.find(line) ?: return@forEach
      val href = decodeEntities(anchor.groupValues[1]).trim()
      val title = stripTags(anchor.groupValues[2]).trim()
      if (href.isBlank() || title.isBlank()) return@forEach

      val absolute = absolutize(href) ?: return@forEach
      // 详情页必须在字幕站本域：跨域链接多半是广告位
      if (!isSameSite(absolute)) return@forEach
      out += ResultRow(title = title, detailUrl = absolute)
    }
    return out
  }

  /**
   * 详情页里找中文字幕下载地址。
   *
   * 优先级：`zh-CN` > `zh` > `zh-TW`（与用户脚本一致：先简体，退通用中文，最后繁体）。
   */
  internal fun parseChineseDownload(html: String): DownloadLink? {
    for (candidate in CHINESE_DOWNLOADS) {
      val tag = Regex("<a[^>]*id=[\"']${Regex.escape(candidate)}[\"'][^>]*>", RegexOption.IGNORE_CASE)
        .find(html)
        ?: continue
      val href = HREF_ATTR.find(tag.value)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
      if (href.isNullOrBlank()) continue
      val absolute = absolutize(href) ?: continue
      val format = absolute.substringAfterLast('.', "").lowercase().takeIf { it in KNOWN_FORMATS }
      return DownloadLink(
        url = absolute,
        format = format,
        language = when (candidate) {
          "download_zh-CN" -> "zh-CN"
          "download_zh-TW" -> "zh-TW"
          else -> "zh"
        },
      )
    }
    return null
  }

  private fun absolutize(href: String): String? =
    when {
      href.startsWith("https://", ignoreCase = true) -> href
      href.startsWith("//") -> "https:$href"
      href.startsWith("/") -> "$BASE$href"
      href.startsWith("http://", ignoreCase = true) -> null // 明文地址一律不收
      href.startsWith("javascript:", ignoreCase = true) -> null
      else -> "$BASE/$href"
    }

  private fun isSameSite(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
    return host == HOST || host.endsWith(".$HOST")
  }

  companion object {
    private const val BASE = RemoteEndpoints.SUBTITLE_CAT_BASE
    private const val HOST = RemoteEndpoints.SUBTITLE_CAT_HOST

    /** 详情页抓取上限：每页一次请求，多了搜索会变成「等十几秒」。 */
    private const val MAX_DETAIL_PAGES = 8

    private val CHINESE_DOWNLOADS = listOf("download_zh-CN", "download_zh", "download_zh-TW")
    private val KNOWN_FORMATS = setOf("srt", "ass", "ssa", "vtt", "txt")

    private val FIRST_ANCHOR =
      Regex("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HREF_ATTR =
      Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")

    internal fun stripTags(raw: String): String =
      decodeEntities(raw.replace(TAG, " ")).replace(Regex("\\s+"), " ")

    /** 只处理会污染标题/地址的那几个实体；其余保持原样，不猜。 */
    internal fun decodeEntities(raw: String): String =
      raw
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
  }
}
