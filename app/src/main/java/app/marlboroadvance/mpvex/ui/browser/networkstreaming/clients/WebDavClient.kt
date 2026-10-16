package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import android.net.Uri
import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.DavResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

class WebDavClient(private val connection: NetworkConnection) : NetworkClient {
  companion object {
    private const val TAG = "WebDavClient"
  }

  // Note: Sardine-Android uses OkHttp which properly handles UTF-8 encoding by default
  private var sardine: Sardine? = null

  /**
   * 取流专用的 Sardine，**进程内只建一次、长期复用**。
   *
   * ⚠️ 这里原来写着「每次取流新建一个 Sardine 以避免连接冲突」—— 那是个反模式：
   * `OkHttpSardine()` 内部自带一个独立 OkHttpClient（独立连接池 + 线程池），
   * 于是**每张封面一条全新 TCP 连接**，keep-alive 完全不复用；再叠加错误路径上
   * 没关掉的响应体（日志里 `A resource failed to call response.body().close()`），
   * 连接与 fd 一路堆到耗尽，表现就是「前 30 个文件夹正常、之后全挂」。
   *
   * 现在改成复用：并发取流由 OkHttp 自己的连接池与分发器管理（它本来就是为并发
   * 设计的），不会冲突。
   */
  @Volatile
  private var streamSardine: Sardine? = null

  /** 取流用的 Sardine：惰性创建、长期复用（见 [streamSardine] 的说明） */
  private fun streamClient(): Sardine =
    streamSardine ?: synchronized(this) {
      streamSardine ?: OkHttpSardine().also { client ->
        if (!connection.isAnonymous) {
          client.setCredentials(connection.username, connection.password)
        }
        streamSardine = client
      }
    }

  /**
   * Build full WebDAV URL from connection and relative path
   * Standard approach: protocol://host:port/basePath/relativePath
   * relativePath should be relative to the basePath (connection.path)
   */
  private fun buildUrl(relativePath: String): String {
    val protocol = if (connection.useHttps) "https" else "http"
    val basePath = connection.path.trim('/')
    val cleanPath = relativePath.trim('/')
    
    // If relativePath is "/" or empty, it means we're at the root of the connection
    // In that case, just use the basePath (connection.path)
    return when {
      cleanPath.isEmpty() || cleanPath == "/" -> {
        if (basePath.isEmpty()) {
          "$protocol://${connection.host}:${connection.port}/"
        } else {
          "$protocol://${connection.host}:${connection.port}/$basePath"
        }
      }
      basePath.isEmpty() -> "$protocol://${connection.host}:${connection.port}/$cleanPath"
      else -> "$protocol://${connection.host}:${connection.port}/$basePath/$cleanPath"
    }
  }

  override suspend fun connect(): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val client = OkHttpSardine()
        if (!connection.isAnonymous) {
          client.setCredentials(connection.username, connection.password)
        }

        // Test connection by checking if base path exists
        val testUrl = buildUrl("")
        client.exists(testUrl)

        sardine = client
        Result.success(Unit)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  override suspend fun disconnect() {
    withContext(Dispatchers.IO) {
      sardine = null
      // 凭据 / 连接变了，取流那个也一起丢掉，下次按需重建
      streamSardine = null
    }
  }

  override fun isConnected(): Boolean = sardine != null

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      try {
        val client = sardine ?: return@withContext Result.failure(Exception("Not connected"))

        val url = buildUrl(path)
        val resources = client.list(url)

        val files =
          resources
            .drop(1) // Skip the directory itself
            .map { resource: DavResource ->
              val resourceName = resource.name ?: ""
              
              // Build child path by appending filename to current path
              val filePath = if (path.isEmpty() || path == "/") {
                resourceName
              } else {
                "${path.trimEnd('/')}/$resourceName"
              }

              NetworkFile(
                name = resourceName,
                path = filePath,
                isDirectory = resource.isDirectory,
                size = resource.contentLength ?: 0,
                lastModified = resource.modified?.time ?: 0,
                mimeType = if (!resource.isDirectory) getMimeType(resourceName) else null,
              )
            }

        Result.success(files)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  /**
   * Get file size for a specific file path
   * This is useful for the proxy server to support range requests
   */
  suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      try {
        val client = sardine ?: return@withContext Result.failure(Exception("Not connected"))

        val url = buildUrl(path)
        
        // Use PROPFIND to get file properties including size
        val resources = client.list(url, 0) // depth 0 = only the resource itself
        if (resources.isNotEmpty() && !resources[0].isDirectory) {
          val size = resources[0].contentLength ?: -1L
          Result.success(size)
        } else {
          Result.failure(Exception("File not found or is a directory"))
        }
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  override suspend fun getFileStream(path: String): Result<InputStream> =
    withContext(Dispatchers.IO) {
      try {
        // 复用长期存活的 Sardine（连接才能复用）；见 [streamSardine] 的说明
        val client = streamClient()

        val url = buildUrl(path)
        val rawStream = client.get(url)

        if (rawStream == null) {
          return@withContext Result.failure(Exception("Failed to open WebDAV stream"))
        }

        // Wrap the stream
        val wrappedStream = object : InputStream() {
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
          }
        }

        Result.success(wrappedStream)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  override suspend fun getFileUri(path: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        val protocol = if (connection.useHttps) "https" else "http"
        val basePath = connection.path.trim('/')
        val cleanPath = path.trim('/')
        
        val fullPath = when {
          cleanPath.isEmpty() -> basePath
          basePath.isEmpty() -> cleanPath
          else -> "$basePath/$cleanPath"
        }

        // Build WebDAV URI with credentials embedded for mpv
        val uriString = if (connection.isAnonymous) {
          "$protocol://${connection.host}:${connection.port}/$fullPath"
        } else {
          "$protocol://${connection.username}:${connection.password}@${connection.host}:${connection.port}/$fullPath"
        }

        Result.success(Uri.parse(uriString))
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  private fun getMimeType(fileName: String): String? =
    // 表收敛到 domain 层（原来这里各写一份同构 when，还都漏了图片格式）
    app.marlboroadvance.mpvex.domain.network.guessNetworkMimeType(fileName)
}
