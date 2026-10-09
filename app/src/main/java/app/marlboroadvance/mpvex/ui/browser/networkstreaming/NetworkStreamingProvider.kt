package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClientFactory
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * ContentProvider for streaming network files to MPV player
 */
class NetworkStreamingProvider : ContentProvider() {
  companion object {
    private const val TAG = "NetworkStreamingProvider"

    /**
     * 活动连接的缓存（清单 #20 建议项）。
     *
     * ⚠️ 必须是 `ConcurrentHashMap` 而不是 `mutableMapOf`：
     * 这个 ContentProvider 的方法（[openFile] 等）是**通过 Binder 被 mpv 进程/线程调的**，
     * 而 [setConnection] 是应用内（主线程）调的 —— 两边并发读写同一张表。
     * `mutableMapOf`（LinkedHashMap）在并发 put 时可能内部结构损坏，
     * 表现为偶发的 `ConcurrentModificationException` 或直接死循环。
     */
    private val connectionCache = ConcurrentHashMap<Long, NetworkConnection>()

    /** 同上，[clientCache] 也跨线程读写。 */
    private val clientCache =
   ConcurrentHashMap<Long, app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClient>()

    fun getUri(context: android.content.Context, connectionId: Long, filePath: String): Uri {
      val authority = "${context.packageName}.networkstreaming"
      return Uri.parse("content://$authority/$connectionId/${Uri.encode(filePath)}")
    }

    fun setConnection(connectionId: Long, connection: NetworkConnection) {
      connectionCache[connectionId] = connection
    }

    /**
     * 关闭并清空所有缓存的连接。
     *
     * 原来这个方法**零调用点**（死代码），而且它自带 `runBlocking` 等`disconnect()` ——
     * 一旦有人「顺手在 onDestroy 里调一下」，就是清单 #20 必修 3 的同一个坑。
     * 所以改成：清表是同步的（纯内存），关连接丢后台。
     */
    fun clearCache() {
   val clients = clientCache.values.toList()
      clientCache.clear()
      connectionCache.clear()
      if (clients.isEmpty()) return
      Thread({
        clients.forEach { client ->
       runCatching { runBlocking { client.disconnect() } }
            .onFailure { Log.w(TAG, "Error disconnecting client", it) }
        }
      }, "net-stream-disconnect").apply { isDaemon = true }.start()
    }
  }

  override fun onCreate(): Boolean {
    return true
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? {
    val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))

    try {
      val pathSegments = uri.pathSegments
      if (pathSegments.size >= 2) {
        val fileName = Uri.decode(pathSegments.last())
        cursor.addRow(arrayOf(fileName, -1L)) // Size unknown for network files
      }
    } catch (e: Exception) {
      // Ignore
    }

    return cursor
  }

  override fun getType(uri: Uri): String? {
    return "video/*"
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    try {
      val pathSegments = uri.pathSegments

      if (pathSegments.size < 2) {
        return null
      }

      val connectionId = pathSegments[0].toLongOrNull()
      if (connectionId == null) {
        return null
      }

      val filePath = Uri.decode(pathSegments.drop(1).joinToString("/"))

      val connection = connectionCache[connectionId]
      if (connection == null) {
        return null
      }

// Get or create client
      // ⚠️ 原来用 `clientCache.getOrPut(...)`：它是「先 get，再 put」，**不是原子的**。
      // 两个 Binder 线程同时为同一个 connectionId 建 client 时，
      // 会各自造出一个客户端实例，其中一个被丢进 map 之外 —— 那个孤儿 client
      // 已经在 [NetworkClientFactory.createClient] 里开了一部分资源，却再也拿不到
      // `disconnect()`（`clearCache` 只遍历 map），等于泄漏一条网络连接。
      // `ConcurrentHashMap.getOrPut`（注意这里用的是 `kotlin.collections` 之外的
      // `MutableMap.getOrPut` 对 ConcurrentMap 的重载）保证整个「取值或创建」原子化。
      val client = clientCache.getOrPut(connectionId) {
NetworkClientFactory.createClient(connection)
      }

      // Create a pipe for streaming
      val pipe = ParcelFileDescriptor.createPipe()
      val readFd = pipe[0]
      val writeFd = pipe[1]

      // Stream the file in a background thread
      Thread {
        try {
          // Ensure connected
          runBlocking {
            if (!client.isConnected()) {
              client.connect().getOrThrow()
            }
          }

          // Get file stream and copy to pipe
          runBlocking {
            client.getFileStream(filePath).onSuccess { inputStream ->
              ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { output ->
                inputStream.use { input ->
                  val buffer = ByteArray(8192)
                  var bytesRead: Int

                  while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                  }
                }
              }
            }.onFailure { error ->
              writeFd.closeWithError(error.message ?: "Unknown error")
            }
          }
        } catch (e: Exception) {
          try {
            writeFd.closeWithError(e.message ?: "Unknown error")
          } catch (closeError: Exception) {
            // Ignore
          }
        }
      }.start()


      return readFd
    } catch (e: Exception) {
      Log.e(TAG, "Error opening file", e)
      return null
    }
  }

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0
}
