package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.database.dao.EmbyServerDao
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlinx.coroutines.flow.Flow

/**
 * Emby 服务器配置仓库。封装 DAO，对外暴露 Flow 与挂起方法。
 */
class EmbyServerRepository(private val dao: EmbyServerDao) {
  val allServers: Flow<List<EmbyServer>> = dao.observeAll()

  suspend fun getAll(): List<EmbyServer> = dao.getAll()

  suspend fun getById(id: Long): EmbyServer? = dao.getById(id)

  suspend fun add(server: EmbyServer): Long = dao.insert(server)

  suspend fun update(server: EmbyServer) = dao.update(server)

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
}
