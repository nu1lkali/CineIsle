package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import app.marlboroadvance.mpvex.domain.network.NetworkFile

/**
 * **可选能力**：服务端全库/索引搜索。
 *
 * 刻意不塞进 [NetworkClient]：SMB / FTP / WebDAV 三个协议没有这种能力，
 * 塞进去就得给它们各写一个永远失败或空的占位实现，反而把公共接口弄脏。
 * 谁支持谁实现（目前只有 [AlistClient]），UI / ViewModel 用
 * `client as? SearchableNetworkClient` 判能力 —— 与 [NetworkClient.supportsWrite]
 * 同一套手法，工厂 [NetworkClientFactory] 不需要改。
 *
 * 与「目录内本地过滤」是两条互不干扰的链路：本地搜索只过滤一次拉全的当前目录，
 * 零请求；本接口发网络请求、跨目录、命中服务端索引。
 */
interface SearchableNetworkClient {
  /**
   * 探测服务端是否允许搜索（开了搜索索引）。
   *
   * 结论在**本次会话内**缓存（[AlistClient] 的实例字段），`disconnect()` 清空；
   * `Unavailable` 不落盘 —— 管理员随时可能打开索引，下次连接必须重新问。
   * 探测失败（网络/超时）返回 [SearchCapability.Unknown]，不下结论。
   */
  suspend fun probeSearch(): SearchCapability

  /**
   * 执行一次搜索。
   *
   * @param keyword 关键词（调用方保证非空）
   * @param page 从 1 开始的页码
   * @param perPage 每页条数
   * @return 失败用 [Result.failure]；错误原因由服务端业务码/异常决定，
   *   ViewModel 会把它翻成界面文案。
   */
  suspend fun search(keyword: String, page: Int, perPage: Int): Result<SearchPage>
}

/** 搜索能力探测结论。 */
sealed interface SearchCapability {
  /** 没问出来（网络异常 / 超时）—— 不下结论，可重试 */
  data object Unknown : SearchCapability

  /** 服务端可用：UI 才渲染全库搜索入口 */
  data object Available : SearchCapability

  /** 服务端明确不可用 */
  data class Unavailable(
    val reason: SearchUnavailableReason,
  ) : SearchCapability
}

enum class SearchUnavailableReason {
  /** 服务端没开搜索索引 */
  INDEX_DISABLED,

  /** 当前账号/游客无检索权限 */
  UNAUTHORIZED,

  /** 5xx / 其它业务错误 */
  SERVER_ERROR,

  /** 请求超时 */
  TIMEOUT,
}

/**
 * 一条搜索结果。
 *
 * @param file 归一化后的文件：[NetworkFile.path] 已是**项目内部相对路径**
 *   （与列目录同一口径，可直接用于跳转 / 起播）；`sign` / `thumb` 为 null
 *   —— 搜索接口不返回这两项，取流与播放靠既有 `fs/get` 回落链。
 * @param parentLabel 给列表显示「它在哪」，AList 绝对路径视角。
 */
data class SearchHit(
  val file: NetworkFile,
  val parentLabel: String,
)

/** 一页搜索结果。 */
data class SearchPage(
  val hits: List<SearchHit>,
  val total: Int,
  val page: Int,
  val hasMore: Boolean,
)
