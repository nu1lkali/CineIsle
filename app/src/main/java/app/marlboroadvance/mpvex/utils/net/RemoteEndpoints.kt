package app.marlboroadvance.mpvex.utils.net

import java.net.URI

/**
 * **第三方服务的地址注册表。**
 *
 * ## 为什么需要它
 *
 * 这些地址以前全部以 `private const` 藏在各自的实现类里
 * （`UpdateFeature` / `SubtitleProviders` / `DanmakuClient`），后果有三个：
 *
 * · 新增一个同类来源要改两个文件，且很容易漏掉常量而直接写字面量；
 * · 站点换域名 / 换路径必须发版，而地址本身没有集中清单可查；
 * · 「检查更新」的仓库地址与 `R.string.github_repo_url` 是两份真相 ——
 *   改了一个忘了另一个，表现为「关于页跳新仓库，检查更新却查旧仓库」。
 *
 * ## 边界
 *
 * 这里**只放地址与路径**，不放超时、鉴权、重试策略 —— 那些归 [HttpClients]。
 * 也不放 Emby 的端点（那是 [app.marlboroadvance.mpvex.domain.emby.EmbyPaths] 的职责，
 * 它带用户态与故障转移，本对象纯静态）。
 */
object RemoteEndpoints {

  // ══════════════════════════════════════════════════════════════════════
  // GitHub（应用自身发布）
  // ══════════════════════════════════════════════════════════════════════

  /** GitHub REST 根。 */
  const val GITHUB_API_BASE: String = "https://api.github.com"

  /**
   * 从仓库主页地址派生「最新 release」API 地址。
   *
   * 入参就是 `R.string.github_repo_url` 的值（形如 `https://github.com/owner/repo`），
   * 这样**仓库只有一份真相**：关于页链接、检查更新、隐私政策里的地址不会各说各话。
   * 早前这里与字符串资源各写一遍，指向上游仓库时「检查更新永远查不到新版本」，
   * 功能看着在、实际没有任何用。
   *
   * @return null = 地址不合法（不是 github.com 的 `owner/repo` 形态）。
   *   调用方按「无更新」处理并记一条日志，不要抛异常 —— 更新检查失败不该打扰用户。
   */
  fun githubLatestRelease(repoUrl: String): String? {
    val path =
      runCatching { URI(repoUrl.trim()).path }.getOrNull()
        ?: return null
    val segs = path.trim('/').split('/').filter { it.isNotBlank() }
    if (segs.size < 2) return null
    return "$GITHUB_API_BASE/repos/${segs[0]}/${segs[1]}/releases/latest"
  }

  // ══════════════════════════════════════════════════════════════════════
  // 在线字幕来源
  // ══════════════════════════════════════════════════════════════════════

  /**
   * 迅雷字幕检索接口（`GET ?name={keyword}`）。
   *
   * ⚠️ 非官方公开 API：没有文档承诺稳定性，改版只能跟随。
   * 正因为如此更需要集中 —— 改地址时只动这一行。
   */
  const val XUNLEI_SUBTITLE_ENDPOINT: String =
    "https://api-shoulei-ssl.xunlei.com/oracle/subtitle"

  /** SubtitleCat 站点根（网页抓取，不是 API）。 */
  const val SUBTITLE_CAT_BASE: String = "https://subtitlecat.com"

  /** SubtitleCat 主域名 —— 用于校验「下载链接确实指向本站」。 */
  const val SUBTITLE_CAT_HOST: String = "subtitlecat.com"

  /**
   * SubtitleCat 搜索结果页。
   *
   * @param encodedKeyword 已 URL 编码的关键词（编码动作留在调用点）
   */
  fun subtitleCatSearch(encodedKeyword: String): String =
    "$SUBTITLE_CAT_BASE/index.php?search=$encodedKeyword"

  // ══════════════════════════════════════════════════════════════════════
  // 弹幕（dandanplay）
  // ══════════════════════════════════════════════════════════════════════

  /**
   * dandanplay 官方 host，同时是设置项的**默认值**。
   *
   * 与另外三个不同：这是用户可以改的（自建 `danmu_api` 服务协议一致），
   * 所以这里只是默认值，真正的 host 由设置项提供。
   */
  const val DANDANPLAY_HOST: String = "https://api.dandanplay.net"

  /** `GET /api/v2/search/anime?keyword=…` */
  fun dandanSearchAnime(encodedKeyword: String): String =
    "/api/v2/search/anime?keyword=$encodedKeyword"

  /** `GET /api/v2/bangumi/{animeId}` —— 番剧详情（含分集） */
  fun dandanBangumi(animeId: String): String = "/api/v2/bangumi/$animeId"

  /** `GET /api/v2/comment/{episodeId}` —— 弹幕本体 */
  fun dandanComments(episodeId: String, withRelated: Boolean): String =
    "/api/v2/comment/$episodeId?withRelated=$withRelated&chConvert=0"
}
