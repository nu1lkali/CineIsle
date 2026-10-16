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
   */
  suspend fun getFileStream(path: String): Result<InputStream>

  /**
   * Get file URI for playback
   */
  suspend fun getFileUri(path: String): Result<Uri>
}
