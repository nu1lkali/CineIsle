package app.marlboroadvance.mpvex.domain.network

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a network connection configuration
 *
 * [password] 落盘时是 Keystore AES-GCM 密文、内存里是明文，
 * 转换统一由 [app.marlboroadvance.mpvex.repository.NetworkRepository] 在读写边界完成。
 */
@Entity(tableName = "network_connections")
data class NetworkConnection(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val name: String,
  val protocol: NetworkProtocol,
  val host: String,
  val port: Int,
  val username: String = "",
  val password: String = "",
  val path: String = "/",
  val isAnonymous: Boolean = false,
  val lastConnected: Long = 0,
  val autoConnect: Boolean = false,
  val useHttps: Boolean = false,  // For WebDAV: use HTTPS instead of HTTP
  /**
   * 是否置顶到连接列表最前。
   *
   * 只影响展示顺序（DAO 里 `ORDER BY pinned DESC, id ASC`），不改变任何连接行为。
   * 落盘是 INTEGER 0/1；v16→v17 用 `ALTER TABLE ... ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0`
   * 补列，老连接默认不置顶，不需要重装。
   */
  val pinned: Boolean = false,
)

/**
 * Supported network protocols
 *
 * ⚠️ 新增枚举值**只能追加在末尾**：Room 用 [app.marlboroadvance.mpvex.database.converters.NetworkProtocolConverter]
 * 以 `name` 存字符串，追加不影响老数据；但插在中间会让依赖 ordinal 的代码错位。
 */
enum class NetworkProtocol(val displayName: String, val defaultPort: Int) {
  SMB("SMB", 445),
  FTP("FTP", 21),
  WEBDAV("WebDAV", 80),

  /**
   * AList 原生 API（不是 WebDAV）。
   *
   * 与 WebDAV 走同一台服务器时的差别是这个协议存在的全部理由：
   * - **一个请求拿整个目录**：`POST /api/fs/list` 一次返回全部条目，还附带
   *   每条的 `sign`（免认证直链签名）与 `thumb`；WebDAV 要做 N 次 PROPFIND。
   * - **token 换一次、用一整场**：`POST /api/auth/login` 拿 token，之后只带
   *   `Authorization` 头，不必像 Basic 认证那样每请求都解一次 Keystore 密文。
   * - **直链可播**：`/d/<路径>?sign=` 是不带凭据也能下的签名直链，播放器不用走本地代理。
   *
   * 契约实证来自 `D:\project\AlistClientN`（Flutter 版 AList 客户端）：
   * `lib/util/file_utils.dart#makeFileLink`、`lib/entity/file_list_resp_entity.dart`、
   * `lib/screen/login_screen.dart#_login`、`lib/net/intercept.dart`。
   */
  ALIST("AList", 5244),
}

/**
 * Runtime status of a network connection
 */
data class ConnectionStatus(
  val connectionId: Long,
  val isConnected: Boolean = false,
  val isConnecting: Boolean = false,
  val error: String? = null,
)
