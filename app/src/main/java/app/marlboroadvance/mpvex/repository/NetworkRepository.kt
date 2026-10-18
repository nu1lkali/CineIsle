package app.marlboroadvance.mpvex.repository

import app.marlboroadvance.mpvex.database.dao.FolderCoverCacheDao
import app.marlboroadvance.mpvex.database.dao.NetworkConnectionDao
import app.marlboroadvance.mpvex.database.dao.NetworkFavoriteDao
import app.marlboroadvance.mpvex.domain.network.ConnectionStatus
import app.marlboroadvance.mpvex.domain.network.FolderCoverCacheEntity
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
 *
 * ## 文件夹封面的两级缓存（L1 进程内 LRU + L2 Room）
 *
 * 网络浏览的文件夹卡用「文件夹里第一张图片」当封面，这要列一次子目录才知道。
 * - **L1**（[coverL1]）：进程内 LRU，导航来回切页面零请求；
 * - **L2**（`folder_cover_cache` 表）：跨进程重启仍然有效，第二次进同一目录
 *   直接读库、不发请求。
 * 两层都缓存**负结果**（coverPath = null = 列过了但没有图片）—— 纯电影目录
 * 恰恰是最常见的场景，不为它每次重列。写缓存只发生在列目录**成功**之后；
 * 失败不写（下次重试）。下拉刷新按当前目录的子文件夹 path 批量失效。
 */
class NetworkRepository(
  private val dao: NetworkConnectionDao,
  private val favoriteDao: NetworkFavoriteDao,
  private val coverCacheDao: FolderCoverCacheDao,
) {
  // Active network clients
  private val activeClients = mutableMapOf<Long, NetworkClient>()

  /**
   * L1 封面缓存：进程内 LRU。
   *
   * - key = `"$connectionId#$path"`（连接 id 与路径拼一个字符串，简单且不会撞）；
   * - value 允许 coverPath = null（负缓存：列过了、没图片）；
   * - accessOrder = true ⇒ 读一次就算「最近用过」，淘汰最久没读的；
   * - 所有读写都过 [coverL1] 自身监视器（synchronized）—— 这个 Repository 是
   *   单例，多个浏览页 / Coil 请求会并发碰它。
   */
  private val coverL1: LinkedHashMap<String, FolderCoverCacheEntity> =
    object : LinkedHashMap<String, FolderCoverCacheEntity>(16, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, FolderCoverCacheEntity>?,
      ): Boolean = size > COVER_L1_CAP
    }

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
   * 置顶 / 取消置顶一个连接。
   *
   * 刻意**不走** [updateConnection]：那条路会重新加密密码并**断开正在用的连接**，
   * 而置顶只是改列表展示顺序，不该把用户正在浏览的会话踢掉。
   * 顺序由 DAO 的 `ORDER BY pinned DESC, id ASC` 保证，这里不动内存集合。
   */
  suspend fun setPinned(id: Long, pinned: Boolean) {
    dao.updatePinned(id, pinned)
  }

  /**
   * Delete a connection
   */
  suspend fun deleteConnection(connection: NetworkConnection) {
    dao.delete(connection)
    // 连接没了，它的收藏快捷入口也全是死的 —— 一起清掉
    favoriteDao.deleteByConnection(connection.id)
    // 封面缓存同理：连接删了，这些行的封面路径再也点不开了
    runCatching { coverCacheDao.deleteByConnection(connection.id) }
    synchronized(coverL1) {
      // 先收集 key 再删：LinkedHashMap 迭代中 remove 会 ConcurrentModificationException
      coverL1.keys
        .filter { it.startsWith("${connection.id}#") }
        .toList()
        .forEach { coverL1.remove(it) }
    }
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
            error = e.message ?: "连接失败",
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
          error = e.message ?: "连接失败",
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
   *
   * @param sign 可选的下载签名（AList = `fs/list` 条目带的 `sign`）。
   *   AList 的 `/d/` 下载口在开启「签名所有」/ 存储级签名 / 目录加密时只认它；
   *   其余协议忽略。传 null 时 [app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.AlistClient]
   *   还有一层 `fs/get` 兜底，所以**不传也不会取不到**，只是多一个请求。
   */
  suspend fun openFileStream(
    connection: NetworkConnection,
    path: String,
    sign: String? = null,
  ): Result<java.io.InputStream> =
    try {
      clientFor(connection).getFileStream(path, sign)
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * 取（必要时现建并连接）这个连接的客户端。
   *
   * 原来这段逻辑长在 [listFiles] 里，照片链路也要用同一套 ——
   * 抽出来后两边共享：活跃 client 优先，没有就按最新凭据建一个并连上。
   *
   * 对外开放：播放前要用 NaOutput 客户端自己的 [NetworkClient.getFileUri]
   * （AList 要据此换签名直链），不能只停留在代理 / ContentProvider 两条路上。
   */
  suspend fun clientFor(connection: NetworkConnection): NetworkClient {
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

  // ── 文件夹封面缓存（L1 LRU + L2 Room） ──

  /**
   * 查一个文件夹的封面缓存（L1 → L2 读穿，L2 命中会回填 L1）。
   *
   * @return null = **从没列过**（缓存未命中，调用方该去列目录）；
   *   非 null = 列过了，[FolderCoverCacheEntity.coverPath] 可空
   *   （null = 列过了但没有图片 —— 这也是有效缓存，别再列）。
   */
  suspend fun getFolderCover(connectionId: Long, path: String): FolderCoverCacheEntity? {
    val key = coverKey(connectionId, path)
    synchronized(coverL1) { coverL1[key] }?.let { return it }
    val entry = coverCacheDao.get(connectionId, path) ?: return null
    synchronized(coverL1) { coverL1[key] = entry }
    return entry
  }

  /**
   * 写封面缓存（L1 + L2）。**只在列目录成功后调用** —— 失败是暂时的，
   * 不能把「这次没列成」记成「没有图片」缓存 30 天。
   *
   * L2 写失败不致命：本次进程内 L1 仍然有效，下次启动重新列一次而已。
   */
  suspend fun putFolderCover(connectionId: Long, path: String, coverPath: String?) {
    val entry = FolderCoverCacheEntity(
      connectionId = connectionId,
      path = path,
      coverPath = coverPath,
      updatedAt = System.currentTimeMillis(),
    )
    synchronized(coverL1) { coverL1[coverKey(connectionId, path)] = entry }
    runCatching { coverCacheDao.upsert(entry) }
  }

  /**
   * 批量失效封面缓存（下拉刷新当前目录时用）：这些子文件夹的内容可能变了，
   * 旧封面（含「没有图片」的负缓存）全部作废，下次滚到眼前时重新列。
   */
  suspend fun invalidateFolderCovers(connectionId: Long, paths: List<String>) {
    if (paths.isEmpty()) return
    val keys = paths.map { coverKey(connectionId, it) }
    synchronized(coverL1) { keys.forEach { coverL1.remove(it) } }
    // SQLite 绑定变量有上限（常见 999），超大目录分批删
    paths.chunked(400).forEach { chunk ->
      runCatching { coverCacheDao.deleteByPaths(connectionId, chunk) }
    }
  }

  /**
   * TTL 清理：删掉 [maxAgeMs] 没刷新过的封面缓存行（启动时调一次）。
   * 只扫 L2 —— L1 是进程内的，进程死了缓存自然没了。
   */
  suspend fun clearStaleFolderCovers(maxAgeMs: Long = FOLDER_COVER_TTL_MS) {
    runCatching { coverCacheDao.deleteOlderThan(System.currentTimeMillis() - maxAgeMs) }
  }

  private fun coverKey(connectionId: Long, path: String): String = "$connectionId#$path"

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

  companion object {
    /** L1 封面缓存封顶：一个浏览页通常几十个文件夹，500 条够跨多个连接用 */
    private const val COVER_L1_CAP = 500

    /** 封面缓存 TTL：30 天。共享目录会变（往里加了照片），超期重列一次保证不永久停在旧图 */
    const val FOLDER_COVER_TTL_MS = 30L * 24 * 60 * 60 * 1000
  }
}
