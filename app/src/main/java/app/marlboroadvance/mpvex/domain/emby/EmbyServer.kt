package app.marlboroadvance.mpvex.domain.emby

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Emby 服务器配置（持久化到 Room）。
 *
 * 登录成功后会把 [userId] 和 [apiToken] 写回，后续所有 API 调用都带 X-Emby-Token。
 * 密码以明文存储——本应用定位是个人本地播放器，不涉及多用户场景，
 * 若需要更高安全可后续改成 EncryptedSharedPreferences 或 Tink 加密。
 */
@Entity(tableName = "emby_servers")
data class EmbyServer(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  /** 用户自定义的服务器显示名 */
  val name: String,
  /** 服务器主机（IP 或域名，不含 scheme） */
  val host: String,
  val port: Int = 8096,
  /** 是否用 HTTPS。Emby 默认 HTTP 8096、HTTPS 8920。 */
  val useHttps: Boolean = false,
  val username: String,
  val password: String,
  /** 登录成功后写入；空表示未登录 */
  val userId: String = "",
  /** 登录成功后写入；空表示未登录 */
  val apiToken: String = "",
  /** 服务器公开名（来自 SystemInfo，可空） */
  val serverName: String = "",
  /** 服务器版本（来自 SystemInfo，可空） */
  val version: String = "",
  val createdAt: Long = System.currentTimeMillis(),
  val lastConnected: Long = 0,
) {
  /** 完整 BaseUrl，例如 http://192.168.1.10:8096/emby */
  val baseUrl: String
    get() {
      val scheme = if (useHttps) "https" else "http"
      return "$scheme://$host:$port"
    }

  /** 用于图片/视频流 URL 的 host prefix（无 /emby 后缀） */
  val hostUrl: String
    get() {
      val scheme = if (useHttps) "https" else "http"
      return "$scheme://$host:$port"
    }

  /** 是否已登录（有 token 和 userId） */
  val isLoggedIn: Boolean get() = apiToken.isNotEmpty() && userId.isNotEmpty()
}
