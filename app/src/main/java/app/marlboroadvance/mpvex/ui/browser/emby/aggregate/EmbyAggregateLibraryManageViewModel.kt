package app.marlboroadvance.mpvex.ui.browser.emby.aggregate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.database.entities.AggregateLibraryEntity
import app.marlboroadvance.mpvex.database.repository.AggregateLibraryRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

/**
 * 「聚合库」管理页（设置 → Emby 媒体库与元数据 → 聚合库 进入）。
 *
 * 职责：列出当前服务器的聚合库、新建 / 编辑 / 删除。
 * 校验规则（产品约束，不是技术限制）：
 * - **同类型**：成员库必须 CollectionType 一致（多个电影库、或多个剧集库）。
 *   混类型不是做不了，而是「电影 + 剧集 + 照片」铺在一屏里没有浏览语义；
 * - **至少 2 个成员**：1 个成员等于原库本身，建它没有意义。
 *
 * 失效成员（服务端上已删除的库）**不拦保存**：用户可能是先删了库再来收拾，
 * 拦在保存环节只会让「清理」这件事变得更麻烦。保存后详情页会给提示。
 */
class EmbyAggregateLibraryManageViewModel(application: Application) : AndroidViewModel(application) {
  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)
  private val aggregateRepository by inject<AggregateLibraryRepository>(AggregateLibraryRepository::class.java)

  private val _server = MutableStateFlow<EmbyServer?>(null)
  val server: StateFlow<EmbyServer?> = _server.asStateFlow()

  private val _aggregates = MutableStateFlow<List<AggregateLibraryEntity>>(emptyList())
  val aggregates: StateFlow<List<AggregateLibraryEntity>> = _aggregates.asStateFlow()

  /** 当前服务器上的真实媒体库（选择器的候选；成员库失效判断也靠它） */
  private val _libraries = MutableStateFlow<List<EmbyItem>>(emptyList())
  val libraries: StateFlow<List<EmbyItem>> = _libraries.asStateFlow()

  private val _loading = MutableStateFlow(false)
  val loading: StateFlow<Boolean> = _loading.asStateFlow()

  private val _toast = MutableStateFlow<String?>(null)
  val toast: StateFlow<String?> = _toast.asStateFlow()

  fun start() {
    viewModelScope.launch {
      val current = repository.ensureLoggedIn(repository.awaitCurrentServer())
      _server.value = current
      if (current == null) {
        _toast.value = "服务器未连接"
        return@launch
      }
      _loading.value = true
      try {
        _libraries.value = runCatching { repository.getVirtualFolders(current) }
          .getOrDefault(emptyList())
      } finally {
        _loading.value = false
      }
      observeAggregates(current.id)
    }
  }

  private suspend fun observeAggregates(serverId: Long) {
    aggregateRepository.observeByServer(serverId).collect { list ->
      _aggregates.value = list
    }
  }

  /**
   * 新建 / 保存一个聚合库。
   *
   * @param memberIds 勾选的成员库 Id（调用方已保证 ≥2 且同类型）
   * @param collectionType 第一个勾选库的类型，写入实体供详情页做一致性提示
   */
  fun save(editId: Long?, name: String, memberIds: List<String>, collectionType: String?) {
    val server = _server.value ?: run {
      _toast.value = "服务器未连接"
      return
    }
    if (name.isBlank()) {
      _toast.value = "请填写名称"
      return
    }
    if (memberIds.size < 2) {
      _toast.value = "至少选择 2 个媒体库"
      return
    }
    viewModelScope.launch {
      val existing = editId?.let { aggregateRepository.getById(it) }
      val entity = AggregateLibraryEntity(
        id = editId ?: 0L,
        serverId = server.id,
        name = name.trim(),
        memberLibraryIds = AggregateLibraryRepository.serializeMembers(memberIds),
        collectionType = collectionType,
        sortIndex = existing?.sortIndex ?: aggregateRepository.nextSortIndex(server.id),
        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
      )
      aggregateRepository.upsert(entity)
      _toast.value = if (editId == null) "已创建「$name」" else "已保存"
    }
  }

  fun delete(id: Long) {
    viewModelScope.launch {
      val target = aggregateRepository.getById(id)
      aggregateRepository.deleteById(id)
      _toast.value = target?.let { "已删除「${it.name}」" } ?: "已删除"
    }
  }

  fun consumeToast() {
    _toast.value = null
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { EmbyAggregateLibraryManageViewModel(application) }
    }
  }
}
