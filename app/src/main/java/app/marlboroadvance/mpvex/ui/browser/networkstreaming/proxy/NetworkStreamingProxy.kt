package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClient
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClientFactory
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Local HTTP proxy server that enables seeking for network streaming protocols
 * that don't support it natively
 */
class NetworkStreamingProxy private constructor() : NanoHTTPD("127.0.0.1", 0) {

  /**
   * WebDAV Range 请求专用的 OkHttp 客户端（**全进程复用**）。
   *
   * ⚠️ 原来在 [getStreamWithOffsetWebDAV] 方法体内每次 `OkHttpClient.Builder()...build()`。
   * 而拖进度条会**高频**调这个方法（每次 seek 一次），于是每次都新建一套
   * 连接池 + 线程池 —— 这是「拖动时卡顿、连接数暴涨」的直接原因。
   *
   * 提到这里复用后，连接得以复用，seek 也就不再每次握手了。
   *
   * 不并入 [app.marlboroadvance.mpvex.domain.emby.EmbyHttp]共享池：那是给 Emby
   * API 用的（带 token 注入 / 401 重登），而这里访问的是**用户自己的 WebDAV**，
   * 鉴权是 Basic 且服务器来源跟 Emby 无关。
   */
  private val webDavRangeClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }

companion object {
    private const val TAG = "NetworkStreamingProxy"

@Volatile
    private var instance: NetworkStreamingProxy? = null

    /**
     * 专供 [NetworkStreamingProxy] 关闭连接用的后台线程（清单 #20 必修 3）。
     *
     * 为什么需要它：`NetworkClient.disconnect()` 是 `suspend`，而关连接的路径
     * （`stopInstance` → `cleanup` → `unregisterStream`）是从 Activity 的 `onDestroy`
     * 一路同步调下来的，也就是**主线程**。原来那里直接 `runBlocking`，
     * 于是「退app」会被 SMB/FTP/WebDAV 的握手超时（`SmbConfig` 里给到 30s）拖住，
     * 最坏情况就是 ANR / 「无响应」弹窗。
     *
     * 关键点：用**自建线程**而不是 `GlobalScope`：这样
     * ① 不受协程取消作用域影响 —— 页面都destroy 了，连接还是要关干净；
     * ② 单线程 ⇒ 天然保持原先 cleanup 的「按注册顺序逐个关」语义。
     *
     * daemon 线程：进程本来就要退了，不该被它挂住。
     */
    private val disconnectExecutor: ExecutorService
      get() = synchronized(this) {
        if (disconnectExecutorInstance == null || disconnectExecutorInstance!!.isShutdown) {
          disconnectExecutorInstance = Executors.newSingleThreadExecutor { r ->
            Thread(r, "net-proxy-disconnect").apply { isDaemon = true }
          }
        }
        disconnectExecutorInstance!!
      }

    private var disconnectExecutorInstance: ExecutorService? = null

    fun getInstance(): NetworkStreamingProxy {
      return instance ?: synchronized(this) {
        instance ?: NetworkStreamingProxy().also {
          it.start()
          instance = it
        }
      }
    }

    /**
     * 停掉本地 HTTP 服务，并**异步**关闭所有连接。
     *
     * ⚠️ 不再阻塞调用方（清单 #20 必修 3）：原来这里同步 `runBlocking` 等
     * 每个客户端 `disconnect()`，而它在主线程（Activity `onDestroy`）。
     * 现在「关连接」丢到 [disconnectExecutor]，本函数只做必须同步的两件事：
     * 停 HTTP server（这是纯内存操作，微秒级）+ 把单例引用摘掉。
     *
     * 因为单例引用已经摘掉，即使还有连接正在异步关闭，
     * 下一次 [getInstance] 也会建出一个全新的代理实例，两批连接互不干扰。
     */
    fun stopInstance() {
      val proxy: NetworkStreamingProxy
      synchronized(this) {
   proxy = instance ?: return
 instance = null
      }
      // 这两行是内存操作，留在调用线程同步做完
      runCatching { proxy.stop() }
        .onFailure { Log.w(TAG, "Failed to stop proxy server", it) }
      // 关连接是网络 IO，丢后台
      proxy.cleanupAsync()
    }
  }

  // Store active connections and their clients
  private val activeStreams = ConcurrentHashMap<String, StreamInfo>()

  data class StreamInfo(
    val connection: NetworkConnection,
    val filePath: String,
    val client: NetworkClient,
    var fileSize: Long = -1L,
    var mimeType: String = "video/mp4",
  )

  /**
   * Register a stream for proxying
   * @return The local URL to use for playback
   */
  fun registerStream(
    streamId: String,
    connection: NetworkConnection,
    filePath: String,
    fileSize: Long = -1L,
    mimeType: String = "video/mp4",
  ): String {
    val client = NetworkClientFactory.createClient(connection)

    val streamInfo = StreamInfo(
      connection = connection,
      filePath = filePath,
      client = client,
      fileSize = fileSize,
      mimeType = mimeType,
    )

    activeStreams[streamId] = streamInfo

    return "http://127.0.0.1:$listeningPort/$streamId"
  }

/**
   * Unregister a stream.
   *
   * [NetworkClient.disconnect] 是挂起函数，而这条路径可能来自主线程
   * （`stopInstance` ← Activity `onDestroy`），所以**绝不**在这里等它。
   * 先把条目从 map 里摘掉（这一步是纯内存，立刻生效），
   * 再把真正的关闭丢给 [disconnectExecutor]（清单 #20 必修 3）。
   */
  fun unregisterStream(streamId: String) {
    val streamInfo = activeStreams.remove(streamId) ?: return
    disconnectExecutor.execute {
      try {
        runBlocking { streamInfo.client.disconnect() }
      } catch (e: Exception) {
        // Ignore disconnect errors
        Log.w(TAG, "disconnect failed for stream $streamId", e)
      }
    }
  }

  /**
   * 关闭所有活动连接（**异步**，清单 #20 必修 3）。
   *
   * 先把 map 一次性取空，再逐条丢后台关 —— 这样 `serve()` 里的
   * `activeStreams[streamId]` 会立刻开始返回 404，不会有「HTTP 服务已停、
   * 但还能命中旧条目」的中间态。
   */
  private fun cleanupAsync() {
    val orphaned = activeStreams.values.toList()
    activeStreams.clear()
    if (orphaned.isEmpty()) return
    disconnectExecutor.execute {
      orphaned.forEach { info ->
        runCatching { runBlocking { info.client.disconnect() } }
          .onFailure { Log.w(TAG, "disconnect failed during cleanup", it) }
      }
    }
  }

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri

    // Extract stream ID from URI (format: /streamId)
    val streamId = uri.removePrefix("/").split("/").firstOrNull()
    if (streamId.isNullOrEmpty()) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")
    }

    val streamInfo = activeStreams[streamId]
    if (streamInfo == null) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")
    }

    // Handle range requests for seeking
    val rangeHeader = session.headers["range"]

    return try {
      if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
        handleRangeRequest(session, streamInfo, rangeHeader)
      } else {
        handleFullRequest(session, streamInfo)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error serving request for stream $streamId: ${streamInfo.filePath}", e)
      Log.e(
        TAG,
        "Connection: ${streamInfo.connection.protocol} ${streamInfo.connection.host}:${streamInfo.connection.port}${streamInfo.connection.path}",
      )
      newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        MIME_PLAINTEXT,
        "Error: ${e.message}",
      )
    }
  }

  private fun handleRangeRequest(
    session: IHTTPSession,
    streamInfo: StreamInfo,
    rangeHeader: String,
  ): Response {
    // Parse range header: bytes=start-end
    val rangeValue = rangeHeader.removePrefix("bytes=")
    val parts = rangeValue.split("-")
    val start = parts[0].toLongOrNull() ?: 0L
    val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
      parts[1].toLongOrNull()
    } else {
      null
    }

    // Get file size if not known
    if (streamInfo.fileSize < 0) {
      streamInfo.fileSize = getFileSize(streamInfo)
    }

    val fileSize = streamInfo.fileSize
    val rangeEnd = end ?: (fileSize - 1)
    val contentLength = rangeEnd - start + 1

    // Get stream with offset
    val inputStream = getStreamWithOffset(streamInfo, start)

    if (inputStream == null) {
      return newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        MIME_PLAINTEXT,
        "Failed to open stream",
      )
    }

    // Create response with partial content
    val response = newFixedLengthResponse(
      Response.Status.PARTIAL_CONTENT,
      streamInfo.mimeType,
      inputStream,
      contentLength,
    )

    response.addHeader("Accept-Ranges", "bytes")
    response.addHeader("Content-Range", "bytes $start-$rangeEnd/$fileSize")
    response.addHeader("Content-Length", contentLength.toString())

    return response
  }

  private fun handleFullRequest(
    session: IHTTPSession,
    streamInfo: StreamInfo,
  ): Response {
    // Get file size if not known
    if (streamInfo.fileSize < 0) {
      streamInfo.fileSize = getFileSize(streamInfo)
    }

    val inputStream = getStream(streamInfo)

    if (inputStream == null) {
      return newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        MIME_PLAINTEXT,
        "Failed to open stream",
      )
    }

    val response = newFixedLengthResponse(
      Response.Status.OK,
      streamInfo.mimeType,
      inputStream,
      streamInfo.fileSize,
    )

    response.addHeader("Accept-Ranges", "bytes")
    if (streamInfo.fileSize > 0) {
      response.addHeader("Content-Length", streamInfo.fileSize.toString())
    }

    return response
  }

  private fun getFileSize(streamInfo: StreamInfo): Long {
    return runBlocking {
      try {
        when (streamInfo.client) {
          is app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.SmbClient -> {
            getFileSizeSMB(streamInfo)
          }

          is app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.FtpClient -> {
            getFileSizeFTP(streamInfo)
          }

          is app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.WebDavClient -> {
            val webDavClient = streamInfo.client
            if (!webDavClient.isConnected()) {
              webDavClient.connect().getOrThrow()
            }
            val sizeResult = webDavClient.getFileSize(streamInfo.filePath)
            sizeResult.getOrNull() ?: -1L
          }

          else -> {
            if (!streamInfo.client.isConnected()) {
              streamInfo.client.connect().getOrThrow()
            }
            val ftpClient =
              streamInfo.client as? app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.FtpClient
            val sizeResult = ftpClient?.getFileSize(streamInfo.filePath)
            sizeResult?.getOrNull() ?: -1L
          }
        }
      } catch (e: Exception) {
        -1L
      }
    }
  }

  /**
   * Get file size using SMB
   */
  private suspend fun getFileSizeSMB(streamInfo: StreamInfo): Long {
    try {
      Log.d(TAG, "SMB getFileSize called")
      Log.d(TAG, "  Connection path: ${streamInfo.connection.path}")
      Log.d(TAG, "  File path: ${streamInfo.filePath}")

      // Extract share name from connection path (just the share name, no subfolders)
      val shareName = streamInfo.connection.path.trim('/')

      if (shareName.isEmpty() || shareName.contains('/')) {
        Log.e(TAG, "SMB: Invalid share name: $shareName")
        return -1L
      }

      // Parse filePath to extract the relative path within the share
      // filePath format: smb://host/shareName/folder/file.mkv
      val relativePath = when {
        streamInfo.filePath.startsWith("smb://", ignoreCase = true) -> {
          // Don't use URI parsing - just use string manipulation to avoid encoding issues
          // Format: smb://host/shareName/path/to/file.mkv
          val pathAfterProtocol = streamInfo.filePath.substring(6) // Remove "smb://"
          val firstSlash = pathAfterProtocol.indexOf('/')
          if (firstSlash == -1) {
            Log.e(TAG, "Invalid SMB path format")
            return -1L
          }

          // Skip past "host/shareName/" to get the file path
          val pathAfterHost = pathAfterProtocol.substring(firstSlash + 1) // Remove "host/"
          val secondSlash = pathAfterHost.indexOf('/')
          if (secondSlash == -1) {
            // Just "smb://host/shareName" with no file
            ""
          } else {
            // Get everything after "shareName/"
            val extracted = pathAfterHost.substring(secondSlash + 1)
            Log.d(TAG, "  Extracted from SMB URL: $extracted")
            extracted
          }
        }
        else -> {
          // Fallback: assume it's already a relative path
          val extracted = streamInfo.filePath.trim('/')
          Log.d(TAG, "  Using as relative path: $extracted")
          extracted
        }
      }

      Log.d(TAG, "  Final: share=$shareName, relativePath=$relativePath")

      val smbConfig = SmbConfig.builder()
        .withTimeout(30000, TimeUnit.MILLISECONDS)
        .withSoTimeout(35000, TimeUnit.MILLISECONDS)
        .build()
      val smbClient = SMBClient(smbConfig)
      val connection = smbClient.connect(streamInfo.connection.host, streamInfo.connection.port)

      val authContext = if (streamInfo.connection.isAnonymous) {
        AuthenticationContext.anonymous()
      } else {
        AuthenticationContext(
          streamInfo.connection.username,
          streamInfo.connection.password.toCharArray(),
          null,
        )
      }

      val session = connection.authenticate(authContext)
      val diskShare = session.connectShare(shareName) as DiskShare

      val file = diskShare.openFile(
        relativePath,
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
        SMB2CreateDisposition.FILE_OPEN,
        null,
      )

      val fileSize = file.fileInformation.standardInformation.endOfFile
      Log.d(TAG, "  File size: $fileSize")

      file.close()
      diskShare.close()
      session.close()
      connection.close()
      smbClient.close()
      return fileSize
    } catch (e: Exception) {
      Log.e(TAG, "SMB getFileSize error: ${e.message}", e)
      return -1L
    }
  }

  /**
   * Get file size using FTP listFiles command
   */
  private suspend fun getFileSizeFTP(streamInfo: StreamInfo): Long {
    val ftpClient = org.apache.commons.net.ftp.FTPClient()

    // Set UTF-8 encoding for proper handling of non-English characters
    ftpClient.controlEncoding = "UTF-8"
    ftpClient.setConnectTimeout(10000)

    try {
      // Connect
      ftpClient.connect(streamInfo.connection.host, streamInfo.connection.port)

      if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(ftpClient.replyCode)) {
        ftpClient.disconnect()
        return -1L
      }

      // Login
      val loginSuccess = if (streamInfo.connection.isAnonymous) {
        ftpClient.login("anonymous", "")
      } else {
        ftpClient.login(streamInfo.connection.username, streamInfo.connection.password)
      }

      if (!loginSuccess) {
        ftpClient.disconnect()
        return -1L
      }

      // Set binary mode
      ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)

      // Try to enable UTF-8 mode on the server (RFC 2640)
      try {
        ftpClient.sendCommand("OPTS UTF8 ON")
      } catch (_: Exception) {
        // Server may not support UTF-8 mode, continue anyway
      }

      // Change to base directory if needed
      if (streamInfo.connection.path != "/" && streamInfo.connection.path.isNotEmpty()) {
        ftpClient.changeWorkingDirectory(streamInfo.connection.path)
      }

      // Determine the file path to use
      val pathsToTry = mutableListOf<String>()
      pathsToTry.add(streamInfo.filePath)
      if (streamInfo.filePath.startsWith("/")) {
        pathsToTry.add(streamInfo.filePath.substring(1))
      }
      if (streamInfo.connection.path != "/" && streamInfo.connection.path.isNotEmpty() &&
        streamInfo.filePath.startsWith(streamInfo.connection.path)
      ) {
        val relativePath = streamInfo.filePath.substring(streamInfo.connection.path.length).trimStart('/')
        if (relativePath.isNotEmpty()) {
          pathsToTry.add(relativePath)
        }
      }

      // Try to get file size using listFiles
      for (path in pathsToTry) {
        try {
          val files = ftpClient.listFiles(path)
          if (files.isNotEmpty() && !files[0].isDirectory) {
            val size = files[0].size
            ftpClient.disconnect()
            return size
          }
        } catch (e: Exception) {
          // Try next path
        }
      }

      ftpClient.disconnect()
      return -1L

    } catch (e: Exception) {
      try {
        ftpClient.disconnect()
      } catch (_: Exception) {
      }
      return -1L
    }
  }

  private fun getStream(streamInfo: StreamInfo): InputStream? {
    return runBlocking {
      try {
        // Connect if needed
        if (!streamInfo.client.isConnected()) {
          streamInfo.client.connect().getOrThrow()
        }

        // Get file stream
        val result = streamInfo.client.getFileStream(streamInfo.filePath)
        result.getOrNull()
      } catch (e: Exception) {
        Log.e(TAG, "Error getting stream", e)
        null
      }
    }
  }

  private fun getStreamWithOffset(streamInfo: StreamInfo, offset: Long): InputStream? {
    return runBlocking {
      try {
        when (streamInfo.client) {
          is app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.SmbClient -> {
            getStreamWithOffsetSMB(streamInfo, offset)
          }

          is app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.FtpClient -> {
            getStreamWithOffsetFTP(streamInfo, offset)
          }

          is app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.WebDavClient -> {
            getStreamWithOffsetWebDAV(streamInfo, offset)
          }

          else -> {
            getStreamWithOffsetGeneric(streamInfo, offset)
          }
        }
      } catch (e: Exception) {
        null
      }
    }
  }

  /**
   * Get FTP stream with offset using REST command (efficient seeking)
   */
  private suspend fun getStreamWithOffsetFTP(streamInfo: StreamInfo, offset: Long): InputStream? {
    // Create a new FTP client for this specific range request
    val ftpClient = org.apache.commons.net.ftp.FTPClient()

    // Set UTF-8 encoding for proper handling of non-English characters
    ftpClient.controlEncoding = "UTF-8"
    ftpClient.setConnectTimeout(10000)
    ftpClient.setDataTimeout(java.time.Duration.ofSeconds(30))
    ftpClient.setControlKeepAliveTimeout(java.time.Duration.ofSeconds(300))

    try {
      // Connect
      ftpClient.connect(streamInfo.connection.host, streamInfo.connection.port)

      if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(ftpClient.replyCode)) {
        ftpClient.disconnect()
        return null
      }

      // Login
      val loginSuccess = if (streamInfo.connection.isAnonymous) {
        ftpClient.login("anonymous", "")
      } else {
        ftpClient.login(streamInfo.connection.username, streamInfo.connection.password)
      }

      if (!loginSuccess) {
        ftpClient.disconnect()
        return null
      }

      // Set binary mode and passive mode
      ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
      ftpClient.enterLocalPassiveMode()

      // Try to enable UTF-8 mode on the server (RFC 2640)
      try {
        ftpClient.sendCommand("OPTS UTF8 ON")
      } catch (_: Exception) {
        // Server may not support UTF-8 mode, continue anyway
      }

      ftpClient.setBufferSize(1024 * 64)

      // Change to base directory if needed
      if (streamInfo.connection.path != "/" && streamInfo.connection.path.isNotEmpty()) {
        ftpClient.changeWorkingDirectory(streamInfo.connection.path)
      }

      // Set restart position (offset) - this is the key for efficient seeking!
      if (offset > 0) {
        ftpClient.setRestartOffset(offset)
      }

      // Determine the file path to use
      val pathsToTry = mutableListOf<String>()
      pathsToTry.add(streamInfo.filePath)
      if (streamInfo.filePath.startsWith("/")) {
        pathsToTry.add(streamInfo.filePath.substring(1))
      }
      if (streamInfo.connection.path != "/" && streamInfo.connection.path.isNotEmpty() &&
        streamInfo.filePath.startsWith(streamInfo.connection.path)
      ) {
        val relativePath = streamInfo.filePath.substring(streamInfo.connection.path.length).trimStart('/')
        if (relativePath.isNotEmpty()) {
          pathsToTry.add(relativePath)
        }
      }

      // Try to retrieve file stream
      var rawStream: java.io.InputStream? = null
      for (path in pathsToTry) {
        rawStream = ftpClient.retrieveFileStream(path)
        if (rawStream != null) {
          break
        }
      }

      if (rawStream == null) {
        ftpClient.disconnect()
        return null
      }

      // Wrap stream to handle cleanup
      val wrappedStream = object : java.io.InputStream() {
        override fun read(): Int = rawStream.read()
        override fun read(b: ByteArray): Int = rawStream.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = rawStream.read(b, off, len)
        override fun available(): Int = rawStream.available()

        override fun close() {
          try {
            rawStream.close()
          } catch (e: Exception) {
            // Ignore
          }
          try {
            if (ftpClient.isConnected) {
              ftpClient.completePendingCommand()
              ftpClient.logout()
              ftpClient.disconnect()
            }
          } catch (e: Exception) {
            // Ignore
          }
        }
      }

      return wrappedStream

    } catch (e: Exception) {
      try {
        ftpClient.disconnect()
      } catch (_: Exception) {
      }
      return null
    }
  }

  /**
   * Get WebDAV stream with offset using HTTP Range header (efficient seeking)
   */
  private suspend fun getStreamWithOffsetWebDAV(streamInfo: StreamInfo, offset: Long): InputStream? {
    try {
      val protocol = if (streamInfo.connection.useHttps) "https" else "http"
      val cleanBasePath = streamInfo.connection.path.trimEnd('/')
      val cleanFilePath = if (streamInfo.filePath.startsWith("/")) streamInfo.filePath else "/${streamInfo.filePath}"
      val url = "$protocol://${streamInfo.connection.host}:${streamInfo.connection.port}$cleanBasePath$cleanFilePath"

      Log.d(TAG, "WebDAV stream request - Protocol: $protocol, URL: $url")

      // Use OkHttp directly to add Range header support
      // ⚠️ 客户端复用 [webDavRangeClient]（见其注释）：原来在这个方法体内每次都new 一个，
      // 而拖动进度条会高频调它—— 每次都新建连接池 + 线程池，
      // 是「拖动时卡顿 / 连接数暴涨」的直接原因。
      //
      // Build the request
      val requestBuilder = okhttp3.Request.Builder()
        .url(url)
        // Range 每次请求都不同（拖动进度条 → offset 变），所以加在请求上而不是拦截器里
        .addHeader("Range", "bytes=$offset-")
        .get()

      // Add auth if needed
      if (!streamInfo.connection.isAnonymous) {
        val credentials = okhttp3.Credentials.basic(streamInfo.connection.username, streamInfo.connection.password)
        requestBuilder.addHeader("Authorization", credentials)
      }

      val request = requestBuilder.build()
      val response = webDavRangeClient.newCall(request).execute()

      if (!response.isSuccessful && response.code != 206) {
        response.close()
        return null
      }

      val rawStream = response.body.byteStream()

      // Wrap stream to handle cleanup
      val wrappedStream = object : java.io.InputStream() {
        override fun read(): Int = rawStream.read()
        override fun read(b: ByteArray): Int = rawStream.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = rawStream.read(b, off, len)
        override fun available(): Int = rawStream.available()

        override fun close() {
          try {
            rawStream.close()
          } catch (e: Exception) {
            // Ignore
          }
          try {
            response.close()
          } catch (e: Exception) {
            // Ignore
          }
        }
      }

      return wrappedStream

    } catch (e: Exception) {
      return null
    }
  }

  /**
   * Get SMB stream with offset using SMBJ (efficient seeking)
   */
  private suspend fun getStreamWithOffsetSMB(streamInfo: StreamInfo, offset: Long): InputStream? {
    try {
      Log.d(TAG, "SMB getStreamWithOffset called, offset=$offset")
      Log.d(TAG, "  Connection path: ${streamInfo.connection.path}")
      Log.d(TAG, "  File path: ${streamInfo.filePath}")

      // Extract share name from connection path (just the share name, no subfolders)
      val shareName = streamInfo.connection.path.trim('/')

      if (shareName.isEmpty() || shareName.contains('/')) {
        Log.e(TAG, "SMB: Invalid share name: $shareName")
        return null
      }

      // Parse filePath to extract the relative path within the share
      // filePath format: smb://host/shareName/folder/file.mkv
      val relativePath = when {
        streamInfo.filePath.startsWith("smb://", ignoreCase = true) -> {
          // Don't use URI parsing - just use string manipulation to avoid encoding issues
          // Format: smb://host/shareName/path/to/file.mkv
          val pathAfterProtocol = streamInfo.filePath.substring(6) // Remove "smb://"
          val firstSlash = pathAfterProtocol.indexOf('/')
          if (firstSlash == -1) {
            Log.e(TAG, "Invalid SMB path format")
            return null
          }

          // Skip past "host/shareName/" to get the file path
          val pathAfterHost = pathAfterProtocol.substring(firstSlash + 1) // Remove "host/"
          val secondSlash = pathAfterHost.indexOf('/')
          if (secondSlash == -1) {
            // Just "smb://host/shareName" with no file
            ""
          } else {
            // Get everything after "shareName/"
            val extracted = pathAfterHost.substring(secondSlash + 1)
            Log.d(TAG, "  Extracted from SMB URL: '$extracted'")
            extracted
          }
        }
        else -> {
          // Fallback: assume it's already a relative path
          val extracted = streamInfo.filePath.trim('/')
          Log.d(TAG, "  Using as relative path: '$extracted'")
          extracted
        }
      }

      Log.d(TAG, "  Final: share=$shareName, relativePath=$relativePath")

      val smbConfig = SmbConfig.builder()
        .withTimeout(120000, TimeUnit.MILLISECONDS) // Increase timeout for large seeks
        .withSoTimeout(120000, TimeUnit.MILLISECONDS)
        .withReadTimeout(120000, TimeUnit.MILLISECONDS)
        .build()
      val smbClient = SMBClient(smbConfig)
      val connection = smbClient.connect(streamInfo.connection.host, streamInfo.connection.port)

      val authContext = if (streamInfo.connection.isAnonymous) {
        AuthenticationContext.anonymous()
      } else {
        AuthenticationContext(
          streamInfo.connection.username,
          streamInfo.connection.password.toCharArray(),
          null,
        )
      }

      val session = connection.authenticate(authContext)
      val diskShare = session.connectShare(shareName) as DiskShare

      // Open file with read access
      val file = diskShare.openFile(
        relativePath,
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
        SMB2CreateDisposition.FILE_OPEN,
        null,
      )

      // Create a seekable stream that reads from file at specific offsets
      val seekableStream = object : InputStream() {
        private var currentPosition = offset
        private val fileHandle = file
        private var closed = false

        override fun read(): Int {
          if (closed) return -1
          val buf = ByteArray(1)
          val bytesRead = read(buf, 0, 1)
          return if (bytesRead == 1) buf[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray): Int {
          return read(b, 0, b.size)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (closed) return -1
          if (len == 0) return 0

          try {
            // SMBJ's file.read() signature: read(ByteArray, Long) -> Int
            // We need to read into a temp buffer then copy to the output buffer
            val readBuffer = ByteArray(len)
            val bytesRead = fileHandle.read(readBuffer, currentPosition)

            if (bytesRead <= 0) return -1

            // Copy the data to the output buffer
            System.arraycopy(readBuffer, 0, b, off, bytesRead)
            currentPosition += bytesRead
            return bytesRead
          } catch (e: Exception) {
            Log.e(TAG, "Error reading from SMB file: ${e.message}")
            return -1
          }
        }

        override fun available(): Int {
          if (closed) return 0
          return try {
            val remaining = fileHandle.fileInformation.standardInformation.endOfFile - currentPosition
            remaining.toInt().coerceAtLeast(0)
          } catch (e: Exception) {
            0
          }
        }

        override fun close() {
          if (!closed) {
            closed = true
            try {
              fileHandle.close()
            } catch (_: Exception) {
            }
            try {
              diskShare.close()
            } catch (_: Exception) {
            }
            try {
              session.close()
            } catch (_: Exception) {
            }
            try {
              connection.close()
            } catch (_: Exception) {
            }
            try {
              smbClient.close()
            } catch (_: Exception) {
            }
          }
        }
      }

      Log.d(TAG, "  Stream created successfully starting at offset $offset")
      return seekableStream
    } catch (e: Exception) {
      Log.e(TAG, "SMB getStreamWithOffset error: ${e.message}", e)
      return null
    }
  }

  /**
   * Generic stream with offset using skip (less efficient, for other protocols)
   */
  private suspend fun getStreamWithOffsetGeneric(streamInfo: StreamInfo, offset: Long): InputStream? {
    val client = NetworkClientFactory.createClient(streamInfo.connection)
    client.connect().getOrThrow()

    val stream = client.getFileStream(streamInfo.filePath).getOrNull()

    if (stream != null && offset > 0) {
      var remaining = offset
      val buffer = ByteArray(8192)

      while (remaining > 0) {
        val toSkip = minOf(remaining, buffer.size.toLong()).toInt()
        val skipped = stream.read(buffer, 0, toSkip)
        if (skipped <= 0) {
          stream.close()
          client.disconnect()
          return null
        }
        remaining -= skipped
      }
    }

    return stream
  }
}
