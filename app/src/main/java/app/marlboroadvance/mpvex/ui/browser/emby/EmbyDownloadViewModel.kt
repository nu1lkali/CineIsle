package app.marlboroadvance.mpvex.ui.browser.emby

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyDownloadManager
import app.marlboroadvance.mpvex.domain.emby.EmbyDownloadTask
import app.marlboroadvance.mpvex.domain.emby.EmbyEnqueueResult
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlinx.coroutines.flow.StateFlow
import org.koin.java.KoinJavaComponent.inject

/**
 * 下载模块的 ViewModel。
 *
 * 它只是 [EmbyDownloadManager]（Koin 单例）的一层薄壳：真正的下载状态、队列、
 * 持久化都在 manager 里，所以详情页、下载管理页、顶栏角标读到的是同一份数据。
 */
class EmbyDownloadViewModel(application: Application) : AndroidViewModel(application) {
  private val manager by inject<EmbyDownloadManager>(EmbyDownloadManager::class.java)

  val tasks: StateFlow<List<EmbyDownloadTask>> = manager.tasks

  fun enqueue(server: EmbyServer, item: EmbyItem): EmbyEnqueueResult = manager.enqueue(server, item)

  fun pause(itemId: String) = manager.pause(itemId)

  fun resume(itemId: String) = manager.resume(itemId)

  fun pauseAll() = manager.pauseAll()

  fun resumeAll() = manager.resumeAll()

  fun remove(itemId: String, deleteFile: Boolean) = manager.remove(itemId, deleteFile)

  fun clearCompleted() = manager.clearCompleted()

  fun taskFor(itemId: String): EmbyDownloadTask? = manager.taskFor(itemId)

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { EmbyDownloadViewModel(application) }
    }
  }
}
