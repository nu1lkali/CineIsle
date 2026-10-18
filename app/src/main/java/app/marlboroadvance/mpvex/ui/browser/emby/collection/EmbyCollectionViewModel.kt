package app.marlboroadvance.mpvex.ui.browser.emby.collection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

/**
 * 「合集（BoxSet）」管理的 ViewModel（清单 #15）。
 *
 * ## 合集 vs 播放列表：为什么两个都要做
 *
 * | | 合集（Collection / BoxSet） | 播放列表（Playlist） |
 * |---|---|---|
 * | 语义 | 「星球大战合集」——**同一部作品的不同版本** | 「周五夜连播」——**任意挑选的片单** |
 * | 条目 | 电影 / 剧集 | 任意 Item |
 * | 成员去重 | 同一版本**不能重复** | 可以重复（同一集加两次是常态） |
 * | 排序 | **没有 Move 端点**（只能改名字 / 增删） | 有 `Move` |
 *
 * 关键差异在最后一行：合集**无法排序**，所以本类没有 `moveEntry`，
 * UI 也就没有拖拽手柄 —— 不是没做，是服务端做不到。
 *
 * ## 「合并多个媒体库」为什么不在这里
 *
 * 需求原文是「可以合并多个媒体库为一个虚拟库」。这件事**已经做了**，
 * 但不是在这个类里，也不走服务端 —— 见
 * [app.marlboroadvance.mpvex.ui.browser.emby.aggregate.EmbyAggregateLibraryScreen]：
 * 它把多个同类型媒体库在**本 App 内**聚合成一个视图（本地 Room 定义 +
 * 多泳道游标归并），不写服务器、即时生效、普通用户密钥即可。
 *
 * 为什么当年没走服务端路线（2026-10-04 的旧结论，**其中一半是错的**，记在这里以免再被误导）：
 * - ~~「Emby REST API 没有虚拟库的写接口」~~ —— **错**。`POST /Library/VirtualFolders`
 *   建库、`.../Delete` / `.../Paths` / `.../LibraryOptions` / `.../Name` 管理都在
 *   （`LibraryStructureService`，2026-10-18 联网核实）。
 * - 「服务端没有『合并库』原生概念」 —— **对**。那些接口只能建一个**带路径并集的新
 *   物理库**：单 CollectionType、要管理员密钥、动的是服务器目录结构、还要重新刮削。
 *
 * 两害相权，本地聚合是唯一「不动服务器、还能混着看、普通密钥就够」的形态，
 * 所以合并需求归聚合库，合集归这里，各自安好。
 */
class EmbyCollectionViewModel(application: Application) : AndroidViewModel(application) {

  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)

  // ── 合集列表 ──

  private val _collections = MutableStateFlow<List<EmbyItem>>(emptyList())
  val collections: StateFlow<List<EmbyItem>> = _collections.asStateFlow()

  private val _listLoading = MutableStateFlow(false)
  val listLoading: StateFlow<Boolean> = _listLoading.asStateFlow()

  // ── 单个合集的条目 ──

  private val _items = MutableStateFlow<List<EmbyItem>>(emptyList())
  val items: StateFlow<List<EmbyItem>> = _items.asStateFlow()

  private val _itemsLoading = MutableStateFlow(false)
  val itemsLoading: StateFlow<Boolean> = _itemsLoading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  private val _toast = MutableStateFlow<String?>(null)
  val toast: StateFlow<String?> = _toast.asStateFlow()

  fun consumeToast() {
    _toast.value = null
  }

  fun clearError() {
    _error.value = null
  }

  /** 条目数文案。做成 [StateFlow] 而非 `fun`，理由见 `EmbyPlaylistViewModel` 的同名成员。 */
  val itemCountText: StateFlow<String> =
    combine(_items) { list -> "${list.size} 项" }
      .stateIn(viewModelScope, SharingStarted.Eagerly, "0 项")

  // ==================== 列表页 ====================

  fun loadCollections(server: EmbyServer, force: Boolean = false) {
    // 已经有数据且不是强制刷新时不重复请求：这张列表不会自己变
    if (!force && _collections.value.isNotEmpty()) return
    _listLoading.value = true
    viewModelScope.launch {
      runCatching { repository.getCollections(server) }
        .onSuccess { _collections.value = it.Items }
        .onFailure { _error.value = it.message ?: "读取合集失败" }
      _listLoading.value = false
    }
  }

  /**
   * 新建合集。
   *
   * ⚠️ **拿不到 Id 不等于失败** —— 部分部署的 `POST /Collections` 只在 `Location` 头里
   * 回新资源地址，body 是空的。所以这里的处理是：
   * - 拿到 Id：直接进详情页继续加条目
   * - 没拿到：提示「已创建（服务端没返回 ID），请刷新列表确认」并回列表页
   *
   * 写成「创建失败」会让用户反复点、反复建出一堆空合集。
   */
  fun createCollection(
    server: EmbyServer,
    name: String,
    itemIds: List<String> = emptyList(),
    onCreated: (String?) -> Unit,
  ) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
      _error.value = "合集名称不能为空"
      onCreated(null)
      return
    }
    viewModelScope.launch {
      runCatching { repository.createCollection(server, trimmed, itemIds) }
        .onSuccess { result ->
          val id = result.Id
          if (id == null) {
            _toast.value = "已创建「$trimmed」，但服务端没返回 ID，请刷新列表确认"
            // 重新拉而不是手工插一条：创建接口回的字段很少，
            // 插进去的对象缺 ChildCount / ImageTags，列表会渲染成半空白
            loadCollections(server, force = true)
          }
          onCreated(id)
        }
        .onFailure {
          _error.value = it.message ?: "创建失败"
          onCreated(null)
        }
    }
  }

  /**
   * 删除合集：`DELETE /Items/{Id}`。
   *
   * ⚠️ **Emby 没有删除合集的专用端点**。合集本身就是一种 Item，
   * 所以复用通用删除（和删电影、删播放列表是同一个接口）。
   *
   * 删的只是**合集这个容器**，里面的电影/剧集不受影响。
   */
  fun deleteCollection(server: EmbyServer, collection: EmbyItem) {
    val id = collection.Id ?: return
    viewModelScope.launch {
      runCatching { repository.deleteItem(server, id) }
        .onSuccess {
          _toast.value = "已删除「${collection.Name ?: "合集"}」"
          // 本地先移除，让列表立刻反映操作结果；
          // 失败会走 onFailure 分支提示用户，不会出现「删了但还在」的情况
          _collections.value = _collections.value.filter { it.Id != id }
        }
        .onFailure { _error.value = it.message ?: "删除合集失败" }
    }
  }

  // ==================== 条目页 ====================

  fun loadItems(server: EmbyServer, collectionId: String) {
    _itemsLoading.value = true
    viewModelScope.launch {
      runCatching { repository.getCollectionItems(server, collectionId) }
        .onSuccess { _items.value = it.Items }
        .onFailure { _error.value = it.message ?: "读取合集内容失败" }
      _itemsLoading.value = false
    }
  }

  /**
   * 往合集加条目。
   *
   * ⚠️ 追加接口**返回体是空的**，拿不到「实际加了几条」——
   * 重复添加是幂等的，加没加上都返回成功。所以本地先乐观更新，
   * 失败时回滚（而不是保持原样，那样界面会说谎）。
   */
  fun addItems(server: EmbyServer, collectionId: String, newItems: List<EmbyItem>) {
    val ids = newItems.mapNotNull { it.Id }
    if (ids.isEmpty()) return
    val snapshot = _items.value
    _items.value = snapshot + newItems
    viewModelScope.launch {
      runCatching { repository.addToCollection(server, collectionId, ids) }
        .onSuccess { _toast.value = "已加入 ${newItems.size} 项" }
        .onFailure {
          _items.value = snapshot
          _error.value = it.message ?: "加入失败，已恢复原状"
        }
    }
  }

  /**
   * 从合集移除条目。
   *
   * ⚠️ `Ids` 传的是**媒体 Id**，不是「合集内的条目 Id」——
   * 正好和 `POST /Playlists/{Id}/Items/Move` 的 `ItemId` 相反，两处极易搞混。
   */
  fun removeItems(server: EmbyServer, collectionId: String, targets: List<EmbyItem>) {
    val ids = targets.mapNotNull { it.Id }
    if (ids.isEmpty()) return
    val snapshot = _items.value
    val idSet = ids.toSet()
    _items.value = snapshot.filter { it.Id !in idSet }
    viewModelScope.launch {
      runCatching { repository.removeFromCollection(server, collectionId, ids) }
        .onSuccess { _toast.value = "已移出 ${targets.size} 项" }
        .onFailure {
          _items.value = snapshot
          _error.value = it.message ?: "移出失败，已恢复原状"
        }
    }
  }

  companion object {
    /**
     * 「加条目」对话框里一次要拉多少候选。
     *
     * 500 是个折中：太少会让用户翻半天，太多则一次响应可能十几 MB
     * （每条都带 Overview / MediaSources）。
     * 用 `Fields` 精简过的列表，见 `EmbyLibraryScreen` 的搜索做法。
     */
    const val PICKER_LIMIT = 500

    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { EmbyCollectionViewModel(application) }
    }
  }
}
