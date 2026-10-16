package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.domain.network.isVideoFile
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.repository.NetworkRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * ViewModel for browsing files on a network share
 * Follows MVVM pattern with proper separation of concerns
 */
class NetworkBrowserViewModel(
  private val application: Application,
  private val connectionId: Long,
  private val currentPath: String,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: NetworkRepository by inject()
  private val browserPreferences: BrowserPreferences by inject()

  private val _files = MutableStateFlow<List<NetworkFile>>(emptyList())
  val files: StateFlow<List<NetworkFile>> = _files.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  /** 当前目录是否已收藏（顶栏星形按钮的状态）。loadFiles 时刷新 */
  private val _isCurrentFavorite = MutableStateFlow(false)
  val isCurrentFavorite: StateFlow<Boolean> = _isCurrentFavorite.asStateFlow()

  /**
   * Load files in the current directory
   */
  fun loadFiles() {
    viewModelScope.launch {
      _isLoading.value = true
      _error.value = null

      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        repository.listFiles(connection, currentPath)
          .onSuccess { fileList ->
            _files.value = fileList.sortedWith(
              compareBy<NetworkFile> { !it.isDirectory }
                .thenBy { it.name.lowercase() },
            )
          }
          .onFailure { e ->
            _error.value = e.message ?: "Unknown error"
          }
        // 收藏状态与列表同源刷新：取消收藏后返回 / 重新进入都能看到正确星形
        _isCurrentFavorite.value = repository.isFavorite(connectionId, currentPath)
      } catch (e: Exception) {
        _error.value = e.message ?: "Unknown error"
      } finally {
        _isLoading.value = false
      }
    }
  }

  /**
   * 收藏 / 取消收藏**当前目录**（顶栏星形）。已收藏时再调 = 取消。
   *
   * 收藏名取目录名（根目录退化为连接名）—— 与 NetworkFavorite 的注释一致：
   * 这是快照，连接改名 / 目录挪走都不影响已存快捷入口的显示。
   */
  fun toggleFavoriteCurrent() {
    viewModelScope.launch {
      val connection = repository.getConnectionById(connectionId) ?: return@launch
      val dirName = currentPath
        .trimEnd('/')
        .substringAfterLast('/', "")
        .ifBlank { connection.name }
      val added = repository.toggleFavorite(connection, currentPath, dirName)
      _isCurrentFavorite.value = added
    }
  }



  /**
   * 队列播放一批视频（普通连播 / 随机播放共用）。
   *
   * @param videos 完整队列，顺序即播放顺序
   * @param startIndex 从哪一条开始
   *
   * 队列经 intent extras 传给播放器：`playlist`（Uri 表）+ `playlist_titles` +
   * `playlist_index`。⚠️ 另外传 `network_file_paths`（与 playlist 同序的原始网络
   * 路径表）—— 播放器靠它按当前下标算每片的进度键 / 找字幕，否则连播换片后
   * 进度全串到第一片头上（PlayerActivity.networkFilePaths 的注释有详述）。
   */
  fun playVideos(videos: List<NetworkFile>, startIndex: Int) {
    if (videos.isEmpty() || startIndex !in videos.indices) return
    viewModelScope.launch {
      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        // Use proxy server for protocols that need seeking support
        val useProxy = connection.protocol in PROXY_PROTOCOLS
        val stamp = System.currentTimeMillis()

        val uris = ArrayList<android.net.Uri>(videos.size)
        val titles = ArrayList<String>(videos.size)
        val networkPaths = ArrayList<String>(videos.size)
        videos.forEachIndexed { index, file ->
          val uri = if (useProxy) {
            val proxy = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.getInstance()
            val streamId = "${connectionId}_${stamp}_$index"
            val proxyUrl = proxy.registerStream(
              streamId = streamId,
              connection = connection,
              filePath = file.path,
              fileSize = file.size,
              mimeType = file.mimeType ?: "video/mp4",
            )
            android.net.Uri.parse(proxyUrl)
          } else {
            NetworkStreamingProvider.setConnection(connectionId, connection)
            NetworkStreamingProvider.getUri(application, connectionId, file.path)
          }
          uris.add(uri)
          titles.add(file.name)
          networkPaths.add(file.path)
        }

        val intent = Intent(Intent.ACTION_VIEW, uris[startIndex])
        intent.setClass(application, app.marlboroadvance.mpvex.ui.player.PlayerActivity::class.java)
        intent.putExtra("internal_launch", true)
        intent.putExtra("launch_source", "network_stream")
        intent.putParcelableArrayListExtra("playlist", uris)
        intent.putStringArrayListExtra("playlist_titles", titles)
        intent.putStringArrayListExtra("network_file_paths", networkPaths)
        intent.putExtra("playlist_index", startIndex)
        // 单值 extra 仍写第一条：播放器非队列路径（字幕/标识）会回退到它
        intent.putExtra("title", videos[startIndex].name)
        intent.putExtra("filename", videos[startIndex].name)
        intent.putExtra("network_file_path", videos[startIndex].path)
        intent.putExtra("network_connection_id", connectionId)
        intent.setDataAndType(uris[startIndex], videos[startIndex].mimeType ?: "video/*")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (!useProxy) {
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        application.startActivity(intent)
      } catch (e: Exception) {
        Log.e(TAG, "Error playing videos", e)
        _error.value = e.message ?: "Unknown error"
      }
    }
  }

  /**
   * 随机播放当前目录的视频：洗牌后按「随机播放数量」设置截断（1~500 条护栏）。
   *
   * 与 Emby 媒体库工具行的随机播放同一口径（同一个设置项）。没有可播视频时
   * 由调用方给 toast 提示，这里静默返回。
   */
  fun playRandomVideos() {
    viewModelScope.launch {
      val pool = _files.value.filter { it.isVideoFile() }
      if (pool.isEmpty()) return@launch
      val limit = browserPreferences.randomPlayCount.randomPlayLimit()
      val picked = pool.shuffled().take(limit)
      playVideos(picked, 0)
    }
  }

  /** 单文件播放（保留旧入口语义）：队列就是它自己 */
  fun playVideo(file: NetworkFile) {
    playVideos(listOf(file), 0)
  }

  companion object {
    private const val TAG = "NetworkBrowserVM"

    // Protocols that require proxy server for seeking support
    private val PROXY_PROTOCOLS = setOf(
      NetworkProtocol.SMB,
      NetworkProtocol.FTP,
      NetworkProtocol.WEBDAV,
    )

    fun factory(
      application: Application,
      connectionId: Long,
      currentPath: String,
    ): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          NetworkBrowserViewModel(application, connectionId, currentPath)
        }
      }
  }
}

/**
 * 「随机播放数量」设置、没取到就明确护栏 —— 与 Emby 媒体库同一设置项同一口径
 * （EmbyLibraryScreen 里那份是同名私有扩展，值必须一致：改这里记得改那边）。
 */
private fun Preference<Int>.randomPlayLimit(): Int = get().coerceIn(1, 500)
