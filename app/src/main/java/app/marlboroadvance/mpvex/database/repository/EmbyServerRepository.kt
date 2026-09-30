package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.database.dao.EmbyServerDao
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.utils.security.CryptoUtils
import app.marlboroadvance.mpvex.utils.security.withDecryptedPassword
import app.marlboroadvance.mpvex.utils.security.withEncryptedPassword
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Emby 服务器配置仓库。封装 DAO，对外暴露 Flow 与挂起方法。
 *
 * 密码的加解密收口在这里：**写进去的是密文，读出来的是明文**。
 * 上层（EmbyRepository / 界面 / EmbyClient）拿到的永远是明文，无需感知加密。
 * 详见 [app.marlboroadvance.mpvex.utils.security.CryptoUtils]。
 */
class EmbyServerRepository(private val dao: EmbyServerDao) {
  val allServers: Flow<List<EmbyServer>> =
    dao.observeAll().map { list -> list.map { it.withDecryptedPassword() } }

  suspend fun getAll(): List<EmbyServer> = dao.getAll().map { it.withDecryptedPassword() }

  suspend fun getById(id: Long): EmbyServer? = dao.getById(id)?.withDecryptedPassword()

  suspend fun add(server: EmbyServer): Long = dao.insert(server.withEncryptedPassword())

  suspend fun update(server: EmbyServer) = dao.update(server.withEncryptedPassword())

  suspend fun delete(server: EmbyServer) = dao.delete(server)

  suspend fun deleteById(id: Long) = dao.deleteById(id)

  /**
   * 登录成功后回写凭据（避免整个 update 触发 INSERT→REPLACE 把 createdAt 抹掉）。
   */
  suspend fun saveCredentials(
    id: Long,
    userId: String,
    apiToken: String,
    serverName: String,
    version: String,
    lastConnected: Long = System.currentTimeMillis(),
  ) = dao.updateCredentials(id, userId, apiToken, serverName, version, lastConnected)

  /**
   * 一次性升级：把老版本遗留的**明文**密码就地重新加密。
   *
   * 只在 App 启动时跑一次，幂等（已经是密文的行会被跳过），
   * 逐行处理且整体 try 住 —— 升级失败不该影响任何功能。
   */
  suspend fun encryptLegacyPlaintextPasswords(): Int {
    val all = runCatching { dao.getAll() }.getOrNull() ?: return 0
    var upgraded = 0
    for (server in all) {
      if (server.password.isEmpty() || CryptoUtils.isEncrypted(server.password)) {
        continue
      }
      runCatching {
        dao.update(server.withEncryptedPassword())
        upgraded++
      }
    }
    return upgraded
  }
}
