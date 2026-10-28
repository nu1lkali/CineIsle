package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import android.net.Uri
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import java.io.InputStream

/**
 * Common interface for all network protocol clients
 */
interface NetworkClient {
  /**
   * 这个客户端是否支持**写操作**（删除 / 重命名 / 新建文件夹）。
   *
   * 默认 false：SMB / FTP / WebDAV 三个客户端都没实现写接口。只有 AList 支持
   * （`fs/remove` / `fs/rename` / `fs/mkdir`），它覆写为 true。
   *
   * UI 据此决定要不要响应长按 —— 不支持的协议弹了菜单也是点了报错，
   * 不如干脆不给入口。
   */
  val supportsWrite: Boolean
    get() = false

  /**
   * Connect to the server
   */
  suspend fun connect(): Result<Unit>

  /**
   * Disconnect from the server
   */
  suspend fun disconnect()

  /**
   * Check if currently connected
   */
  fun isConnected(): Boolean

  /**
   * List files and directories at the given path
   */
  suspend fun listFiles(path: String): Result<List<NetworkFile>>

  /**
   * Get input stream for a file
   *
   * @param sign 可选的**下载签名**。目前只有 AList 用得上：它的 `/d/<路径>` 下载口
   *   在服务端开启「签名所有」/ 存储级签名 / 目录加密时**只认 `?sign=`**，
   *   `Authorization` 头在那条路由上根本不被读取（见 [AlistClient] 的类注释）。
   *   SMB / FTP / WebDAV 忽略这个参数。
   */
  suspend fun getFileStream(path: String, sign: String? = null): Result<InputStream>

  /**
   * Get file URI for playback
   */
  suspend fun getFileUri(path: String): Result<Uri>

  /**
   * **零请求**地尽量给出播放直链；拿不到就返回 null（由调用方决定回落还是跳过）。
   *
   * 与 [getFileUri] 的分工：
   * - [getFileUri] 是「**一定能拿到**」的那条路，代价是**至少一次网络往返**
   *   （AList 要 `fs/get` 换 `raw_url` / 新签名）；
   * - 本方法是「**手里已经有的信息够拼就拼**」，纯本地字符串处理，**同步、可循环调用**。
   *
   * 为什么需要它：主播放器的连播队列是「整个目录逐条算地址」，
   * 100 条视频走 [getFileUri] 就是 100 次串行 `fs/get` —— 用户看到的现象是
   * 「点了视频，十几秒后才弹播放器」。而 `fs/list` 本来就把每条的 `sign` 带回来了
   * （存在 [NetworkFile.sign] / 客户端签名缓存里），拼一条 `/d/...?sign=` 就够了。
   *
   * 默认实现返回 null（= 本协议没有这种便宜路），只有 AList 覆写。
   *
   * @param sign 调用方手里的签名（通常是 `fs/list` 现场拿到的那个），可为 null。
   */
  fun directUriOrNull(path: String, sign: String? = null): Uri? = null
}
