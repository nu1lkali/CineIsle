package app.marlboroadvance.mpvex.repository

import app.marlboroadvance.mpvex.database.dao.NetworkConnectionDao
import app.marlboroadvance.mpvex.database.dao.NetworkFavoriteDao
import app.marlboroadvance.mpvex.domain.network.ConnectionStatus
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFavorite
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClient
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClientFactory
import app.marlboroadvance.mpvex.utils.security.CryptoUtils
import app.marlboroadvance.mpvex.utils.security.withDecryptedPassword
import app.marlboroadvance.mpvex.utils.security.withEncryptedPassword
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Repository for managing network connections and file browsing
 *
 * 密码的加解密收口在这里：**数据库里存密文，交给客户端的对象里是明文**。
 * 这样 SmbClient / FtpClient / WebDavClient / NetworkStreamingProxy 全都不用改。
 */
class NetworkRepository(
  private val dao: NetworkConnectionDao,
  private val favoriteDao: NetworkFavoriteDao,
) {
  // Active network clients
  private val activeClients = mutableMapOf<Long, NetworkClient>()

  // Connection statuses
  private val _connectionStatuses = MutableStateFlow<Map<Long, ConnectionStatus>>(emptyMap())
  val connectionStatuses: StateFlow<Map<Long, ConnectionStatus>> = _connectionStatuses.asStateFlow()

  /**
   * Get all saved connections as a Flow
   */
  fun getAllConnections(): Flow<List<NetworkConnection>> =
    dao.getAllConnections().map { list -> list.map { it.withDecryptedPassword() } }

  /**
   * Get connections that should auto-connect on launch
   */
  suspend fun getAutoConnectConnections(): List<NetworkConnection> =
    dao.getAutoConnectConnections().map { it.withDecryptedPassword() }

  /**
   * Get a connection by ID
   */
  suspend fun getConnectionById(id: Long): NetworkConnection? =
    dao.getConnectionById(id)?.withDecryptedPassword()

  /**
   * Add a new connection
   */
  suspend fun addConnection(connection: NetworkConnection): Long =
    dao.insert(connection.withEncryptedPassword())

  /**
   * Update an existing connection
   */
  suspend fun updateConnection(connection: NetworkConnection) {
    dao.update(connection.withEncryptedPassword())
    // Disconnect and remove cached client if it exists
    // This ensures the next connection uses the updated credentials
    activeClients[connection.id]?.let { client ->
      try {
        client.disconnect()
      } catch (e: Exception) {
        // Ignore errors during cleanup
      }
      activeClients.remove(connection.id)
      // Update status to disconnected
      updateConnectionStatus(
        connection.id,
        ConnectionStatus(
          connectionId = connection.id,
          isConnected = false,
          isConnecting = false,
        ),
      )
    }
  }

  /**
   * Delete a connection
   */
  suspend fun deleteConnection(connection: NetworkConnection) {
    dao.delete(connection)
    // 连接没了，它的收藏快捷入口也全是死的 —— 一起清掉
    favoriteDao.deleteByConnection(connection.id)
    // Clean up connection status
    _connectionStatuses.value -= connection.id
    // Disconnect if active
    activeClients[connection.id]?.let { client ->
      try {
        client.disconnect()
      } catch (e: Exception) {
        // Ignore errors during cleanup
      }
      activeClients.remove(connection.id)
    }
  }

  /**
   * Connect to a network share
   */
  suspend fun connect(connection: NetworkConnection): Result<Unit> {
    // Update status to connecting
    updateConnectionStatus(
      connection.id,
      ConnectionStatus(
        connectionId = connection.id,
        isConnecting = true,
      ),
    )

    return try {
      // Create client for this connection
      val client = NetworkClientFactory.createClient(connection)

      // Attempt to connect
      client.connect().onSuccess {
        // Store the active client
        activeClients[connection.id] = client

        // Update last connected time
        dao.updateLastConnected(connection.id, System.currentTimeMillis())

        // Update status to connected
        updateConnectionStatus(
          connection.id,
          ConnectionStatus(
            connectionId = connection.id,
            isConnected = true,
            isConnecting = false,
          ),
        )
      }.onFailure { e ->
        // Update status with error
        updateConnectionStatus(
          connection.id,
          ConnectionStatus(
            connectionId = connection.id,
            isConnected = false,
            isConnecting = false,
            error = e.message ?: "Connection failed",
          ),
        )
        throw e
      }
      Result.success(Unit)
    } catch (e: Exception) {
      // Update status with error
      updateConnectionStatus(
        connection.id,
        ConnectionStatus(
          connectionId = connection.id,
          isConnected = false,
          isConnecting = false,
          error = e.message ?: "Connection failed",
        ),
      )
      Result.failure(e)
    }
  }

  /**
   * Disconnect from a network share
   */
  suspend fun disconnect(connection: NetworkConnection): Result<Unit> =
    try {
      // Get and disconnect the client
      activeClients[connection.id]?.let { client ->
        client.disconnect()
        activeClients.remove(connection.id)
      }

      // Update status to disconnected
      updateConnectionStatus(
        connection.id,
        ConnectionStatus(
          connectionId = connection.id,
          isConnected = false,
          isConnecting = false,
        ),
      )
      Result.success(Unit)
    } catch (e: Exception) {
      // Even if disconnect fails, update status
      updateConnectionStatus(
        connection.id,
        ConnectionStatus(
          connectionId = connection.id,
          isConnected = false,
          isConnecting = false,
          error = e.message,
        ),
      )
      Result.failure(e)
    }

  /**
   * List files in a directory on a network share
   */
  suspend fun listFiles(
    connection: NetworkConnection,
    path: String,
  ): Result<List<NetworkFile>> =
    try {
      // List files
      clientFor(connection).listFiles(path)
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * 打开一个文件的读取流（照片查看器 / 保存本地 / Coil Fetcher 走这里）。
   *
   * ⚠️ 流的生命周期归调用方：用完必须 close（SMB 会话数有限，泄漏会把这台
   *    服务器的连接耗尽）。网络照片链路里 Coil 的 Fetcher 负责关。
   */
  suspend fun openFileStream(
    connection: NetworkConnection,
    path: String,
  ): Result<java.io.InputStream> =
    try {
      clientFor(connection).getFileStream(path)
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * 取（必要时现建并连接）这个连接的客户端。
   *
   * 原来这段逻辑长在 [listFiles] 里，照片链路也要用同一套 ——
   * 抽出来后两边共享：活跃 client 优先，没有就按最新凭据建一个并连上。
   */
  private suspend fun clientFor(connection: NetworkConnection): NetworkClient {
    // Always fetch the latest connection from database to ensure we have current credentials
    // 注意：DAO 里存的是密文，这里必须解一次再交给客户端
    val latestConnection =
      (dao.getConnectionById(connection.id) ?: connection).withDecryptedPassword()

    // Check if we have an active client
    val existingClient = activeClients[connection.id]
    return existingClient ?: NetworkClientFactory.createClient(latestConnection).also { newClient ->
      newClient.connect().getOrThrow()
      activeClients[connection.id] = newClient
    }
  }

  /**
   * Get an active client for a connection
   */
  fun getActiveClient(connectionId: Long): NetworkClient? = activeClients[connectionId]

  /**
   * Check if a connection is active
   */
  fun isConnected(connectionId: Long): Boolean = activeClients.containsKey(connectionId)

  /**
   * Disconnect all active connections
   */
  suspend fun disconnectAll() {
    activeClients.values.forEach { client ->
      try {
        client.disconnect()
      } catch (e: Exception) {
        // Ignore errors during cleanup
      }
    }
    activeClients.clear()
    _connectionStatuses.value = emptyMap()
  }

  /**
   * 一次性升级：把老版本遗留的**明文**密码就地重新加密。
   *
   * 只在 App 启动时跑一次，幂等（已是密文的行跳过），整体 try 住 ——
   * 升级失败不影响任何功能，下次启动会再试。
   */
  suspend fun encryptLegacyPlaintextPasswords(): Int {
    val all = runCatching { dao.getAllConnectionsList() }.getOrNull() ?: return 0
    var upgraded = 0
    for (connection in all) {
      if (connection.password.isEmpty() || CryptoUtils.isEncrypted(connection.password)) continue
      runCatching {
        dao.update(connection.withEncryptedPassword())
        upgraded++
      }
    }
    return upgraded
  }

  // ── 收藏的目录（快捷入口） ──

  /** 全部收藏（按收藏时间倒序）。主界面快捷入口 section 直接 collect */
  fun getAllFavorites(): Flow<List<NetworkFavorite>> = favoriteDao.getAllFavorites()

  suspend fun isFavorite(connectionId: Long, path: String): Boolean =
    favoriteDao.isFavorite(connectionId, path)

  /**
   * 收藏 / 取消收藏一个目录。已收藏时再调 = 取消。
   *
   * @return true = 本次是「加上去」，false = 「取下来」
   */
  suspend fun toggleFavorite(connection: NetworkConnection, path: String, name: String): Boolean {
    val existing = favoriteDao.getFavorite(connection.id, path)
    return if (existing == null) {
      favoriteDao.insert(
        NetworkFavorite(
          connectionId = connection.id,
          path = path,
          name = name,
          connectionName = connection.name,
        ),
      )
      true
    } else {
      favoriteDao.delete(connection.id, path)
      false
    }
  }

  suspend fun deleteFavorite(id: Long) {
    favoriteDao.deleteById(id)
  }

  private fun updateConnectionStatus(
    connectionId: Long,
    status: ConnectionStatus,
  ) {
    _connectionStatuses.value += (connectionId to status)
  }
}
