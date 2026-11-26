package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClient
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClientFactory
import app.marlboroadvance.mpvex.ui.player.PlayerDiagLog
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

    /**
     * 一条响应体内部最多续流几次。
     *
     * 给 3：网络掐一下、NAS 丢一下包，重开一次通常就过去了；连续三次还断，
     * 说明源真的没了（文件被移走 / 会话彻底失效），继续重试只是把播放器卡在缓冲上。
     */
    private const val MAX_REOPENS = 3

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
          // ⚠️ 读超时必须是 0（= 永不超时），与 `IsoRangeProxy` 一致。
          // `start()` 不传参时用的是 NanoHTTPD 的默认 5 秒；而 mpv 的缓冲一满就会
          // **停一会儿不再从 socket 读**（`cache-secs=8` / `demuxer-max-bytes` 打满时），
          // 5 秒一到这条长连接就废了 —— 表现是「播着播着突然停，重开又好」。
          it.start(0, true)
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
    // Extract stream ID from URI (format: /streamId)
    val streamId = session.uri.removePrefix("/").split("/").firstOrNull()
    if (streamId.isNullOrEmpty()) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")
    }

    val streamInfo = activeStreams[streamId]
      ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")

    return try {
      serveStream(session, streamInfo, streamId)
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

  /**
   * 把一次请求翻译成「从哪读到哪」，然后交给 [ResumableStream] 去读。
   *
   * ## 这里原来有三个错，都会让播放器「播一半就停 / 时长不对」
   *
   * ① **`bytes=-N` 被当成 `bytes=0-N`**。后缀区间是「要**最后** N 个字节」，
   *    而老代码 `parts[0].toLongOrNull()` 解析失败后回落成 0 ⇒ 客户端要尾巴、
   *    拿到的是开头。ffmpeg 探测 MPEG-TS 时长时会往文件尾 seek，
   *    拿回来的却是文件头 ⇒ **时长退化成按码率估算**，进度条自然不准。
   * ② **`end` 没有夹到 `fileSize-1`**。ffmpeg 探测时会发 `bytes=0-999999999`，
   *    原样回 `Content-Range: bytes 0-999999999/<size>` 是**非法响应头**。
   * ③ **起点越界没有回 416**，一律当「没有 Range」从头开流。
   *
   * 另外：文件大小拿不到时不再编造 `Content-Range: bytes 0--2/-1`（同样是非法头），
   * 改成 chunked —— 代价是拖不了进度，但至少能正常播完。
   */
  private fun serveStream(
    session: IHTTPSession,
    streamInfo: StreamInfo,
    streamId: String,
  ): Response {
    // Get file size if not known
    if (streamInfo.fileSize <= 0) {
      streamInfo.fileSize = getFileSize(streamInfo)
    }
    val total = streamInfo.fileSize
    val rangeHeader = session.headers["range"]

    diag(
      "proxy-req",
      "stream=$streamId proto=${streamInfo.connection.protocol} " +
        "range=${rangeHeader ?: "none"} total=$total",
    )

    if (total <= 0) {
      // 大小未知：只能当「不可拖动的流」用。chunked 回，不要 Content-Range。
      val wantsFromZero = rangeHeader == null || rangeHeader.removePrefix("bytes=").startsWith("0-")
      if (wantsFromZero) {
        val stream = ResumableStream(streamInfo, 0L, Long.MAX_VALUE, streamId)
        return newChunkedResponse(Response.Status.OK, streamInfo.mimeType, stream)
      }
      return newFixedLengthResponse(
        Response.Status.RANGE_NOT_SATISFIABLE,
        MIME_PLAINTEXT,
        "size unknown",
      )
    }

    // 没有 Range ⇒ 整段（200 + 完整长度）。有 Range ⇒ 按区间（206）。
    val from: Long
    val to: Long
    if (rangeHeader == null || rangeHeader.isBlank()) {
      from = 0L
      to = total - 1
    } else {
      val range =
        parseRange(rangeHeader, total)
          ?: return newFixedLengthResponse(
            Response.Status.RANGE_NOT_SATISFIABLE,
            MIME_PLAINTEXT,
            "range not satisfiable",
          )
      from = range.first
      to = range.last
    }

    val length = to - from + 1
    val status =
      if (rangeHeader == null || rangeHeader.isBlank()) Response.Status.OK else Response.Status.PARTIAL_CONTENT

    val stream = ResumableStream(streamInfo, from, to + 1, streamId)
    val response = newFixedLengthResponse(status, streamInfo.mimeType, stream, length)
    response.addHeader("Accept-Ranges", "bytes")
    response.addHeader("Content-Length", length.toString())
    if (status == Response.Status.PARTIAL_CONTENT) {
      response.addHeader("Content-Range", "bytes $from-$to/$total")
    }
    return response
  }

  /**
   * 解析 `Range: bytes=a-b`，返回**闭区间**。
   *
   * 支持的三种形态（HTTP/1.1 全集）：
   *  - `bytes=a-b`：正常区间；
   *  - `bytes=a-`：a 到文件尾（拖进度条时最常见）；
   *  - `bytes=-n`：**最后** n 个字节（ffmpeg 探尾求时长会用）。
   *
   * @return null = 语法不认识 / 不可满足 ⇒ 调用方回 416
   */
  private fun parseRange(
    header: String,
    total: Long,
  ): LongRange? {
    if (!header.startsWith("bytes=")) return null
    // 多段 Range（`bytes=0-1,5-6`）只取第一段：播放器从不用多段
    val spec = header.removePrefix("bytes=").substringBefore(',').trim()
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()

    val from: Long
    val to: Long
    if (startText.isEmpty()) {
      // `bytes=-N` = 最后 N 个字节。⚠️ 这就是老代码最大的错
      val n = endText.toLongOrNull() ?: return null
      if (n <= 0) return null
      from = (total - n).coerceAtLeast(0L)
      to = total - 1
    } else {
      from = startText.toLongOrNull() ?: return null
      to = if (endText.isEmpty()) total - 1 else (endText.toLongOrNull() ?: return null)
    }
    if (from < 0 || from >= total) return null
    return from..to.coerceAtMost(total - 1)
  }

  /**
   * 上游流 + **断流自动续**。
   *
   * ## 为什么必须有它
   *
   * 老代码在读失败时 `return -1` —— 也就是**当成正常结束**。而响应头里已经写死了
   * `Content-Length`，于是 ffmpeg 认为「文件就这么长」，**不会重连**
   * （`stream-lavf-o` 里那些 reconnect 只作用于 http 协议层，管不到我们这层代理）。
   *
   * 结果就是：SMB / WebDAV 中途抖一下（NAS 掉一下包、会话被服务器回收、
   * 网盘限速掐连接）⇒ **画面停住 + 进度条卡在那儿 + 自动跳下一集**。
   * 大文件最容易撞上（`.mts`、蓝光原盘），因为播放窗口长、中间断的概率高；
   * 如果这一下正好发生在 ffmpeg「seek 到尾部算时长」那一步，
   * 连 `duration` 都会退化成按码率估算 —— 进度条跟着一起错。
   *
   * ## 现在
   *
   * 读到 `-1` 或抛异常、且**还没到声明的上界**，就拿当前偏移重新开一条上游流接着读，
   * 最多 [MAX_REOPENS] 次；真续不上才如实回 EOF（并且写一行诊断日志，
   * 下次一眼能看出是不是源的问题）。
   */
  private inner class ResumableStream(
    private val streamInfo: StreamInfo,
    private val startOffset: Long,
    private val limitExclusive: Long,
    private val streamId: String,
  ) : InputStream() {
    private var pos = startOffset
    private var upstream: InputStream? = null
    private var reopens = 0
    private var closed = false

    private fun ensure(): InputStream? {
      upstream?.let { return it }
      if (closed) return null
      return getStreamWithOffset(streamInfo, pos).also { upstream = it }
    }

    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(
      b: ByteArray,
      off: Int,
      len: Int,
    ): Int {
      if (closed) return -1
      if (len == 0) return 0
      if (pos >= limitExclusive) return -1
      // 一次最多读到声明区间的末尾：多读的字节会被 NanoHTTPD 丢掉，还白白多拉数据
      val want = minOf(len.toLong(), limitExclusive - pos).toInt()

      while (true) {
        val stream = ensure()
        if (stream == null) {
          if (!reopen()) return -1
          continue
        }
        val n =
          try {
            stream.read(b, off, want)
          } catch (e: Exception) {
            Log.w(TAG, "upstream read failed at $pos (stream=$streamId), will reopen", e)
            -1
          }
        if (n > 0) {
          pos += n
          return n
        }
        // 上游说「没了」：真尾声，还是被掐了？没到上界就当成被掐了再续一次
        if (pos >= limitExclusive) return -1
        if (!reopen()) return -1
      }
    }

    private fun reopen(): Boolean {
      if (reopens >= MAX_REOPENS) {
        diag("proxy-drop", "stream=$streamId 续流 $reopens 次仍失败，pos=$pos limit=$limitExclusive")
        return false
      }
      reopens++
      runCatching { upstream?.close() }
      upstream = null
      diag("proxy-reopen", "stream=$streamId pos=$pos 第 $reopens 次续流")
      return true
    }

    override fun available(): Int = 0

    override fun close() {
      if (closed) return
      closed = true
      runCatching { upstream?.close() }
      upstream = null
    }
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

  /**
   * 写一行代理侧的诊断日志。
   *
   * 走 [PlayerDiagLog]：只有设置里开了「详细日志」才落盘（关着时零开销），
   * 落在 `Android/data/<包名>/files/cineisle-diag.log`，与 mpv 自己的 `mpv-log.txt`
   * 同一时间基准、可以直接按时间戳对齐。
   *
   * 记什么：每个请求的 Range / 声明长度、续流、以及「续不上了」的那一刻。
   * 这三样齐了，「播一半停」到底是源断、是被掐、还是我们回错了长度，一眼能分开。
   */
  private fun diag(
    tag: String,
    message: String,
  ) {
    runCatching { PlayerDiagLog.log(tag, message) }
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
        // 403 = 凭据/会话过期、503 = 上游暂时没数据。都如实失败，
        // 上层 [ResumableStream] 会重开一次，仍然不行才回 EOF
        diag("proxy-webdav", "HTTP ${response.code} offset=$offset path=${streamInfo.filePath}")
        response.close()
        return null
      }

      val rawStream = response.body.byteStream()

      // ⚠️ 有些 WebDAV 服务器会**无视 Range**，直接回 200 + 整个文件。
      // 此时 body 是从 0 开始的，而我们对外声明的是「从 offset 开始」——
      // 不把这 offset 个字节就地跳掉，客户端拿到的就是**错位的数据**：
      // 轻则花屏，重则 demuxer 找不到 MPEG-TS 的 `0x47` 同步头，当场判 EOF
      // （症状正是「播到一半画面停住 / 时长算错」）。
      if (response.code == 200 && offset > 0L) {
        diag("proxy-webdav", "服务器忽略了 Range（回 200），本地跳过 $offset 字节")
        var remaining = offset
        val skipBuffer = ByteArray(64 * 1024)
        while (remaining > 0) {
          val skipped = rawStream.read(skipBuffer, 0, minOf(remaining, skipBuffer.size.toLong()).toInt())
          if (skipped <= 0) {
            response.close()
            return null
          }
          remaining -= skipped
        }
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
