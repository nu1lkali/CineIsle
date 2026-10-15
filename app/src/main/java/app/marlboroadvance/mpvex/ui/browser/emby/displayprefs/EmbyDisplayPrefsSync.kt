package app.marlboroadvance.mpvex.ui.browser.emby.displayprefs

import app.marlboroadvance.mpvex.domain.emby.EmbyApiException
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyDisplayPreferences
import app.marlboroadvance.mpvex.domain.emby.EmbyDisplayPrefsCodec
import app.marlboroadvance.mpvex.domain.emby.EmbyDisplayPrefsCodec.Snapshot
import app.marlboroadvance.mpvex.domain.emby.EmbyErrorKind
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 显示偏好在本地与服务端之间的搬运工（清单 #16）。
 *
 * ## 为什么要一个专门的「搬运工」而不是在网络层随手写一下
 *
 * 服务端这条通道的形状很别扭，三条都决定了它没法随手一写：
 *
 * 1. **粒度是「每个媒体库一份」**（`DisplayPreferencesId` 来自
 *    `BaseItemDto.DisplayPreferencesId`），所以一次同步 = 遍历所有库各写一次。
 * 2. **写是整体覆盖**，所以每次写之前必须先读，读回来把自己的键 merge 进去。
 * 3. **分区键 `Client` 必填**，本 App 用独立分区
 *    （[EmbyClient.DISPLAY_PREFS_CLIENT]），所以**不会**和 Emby Web 互相覆盖 ——
 *    代价是也不同步。这是刻意的取舍，理由见 [EmbyClient] 5.8 节。
 *
 * ## 「同步」到底是哪一句话
 *
 * 这里的同步是**本 App 多设备之间**的：手机上改了网格列数，平板上登录同一账号
 * 打开 App 会看到一样的设置。它**不是**「和网页端共用一套外观设置」。
 *
 * ## 首启不覆盖
 *
 * 用户还没上传过（[BrowserPreferences.embyDisplayPrefsPushed] 为 false）时，
 * 自动拉取**不会**把本地覆盖掉 —— 否则新装的 App 默认值会把服务端那份顶掉，
 * 之后用户就再也发现不了「换个设备设置会跟着走」这件事了。
 */
class EmbyDisplayPrefsSync(
  private val repository: EmbyRepository,
  private val preferences: BrowserPreferences,
) {

  /** 同步结果。UI 要能区分「真同步了」和「服务端本来就是空的」。 */
  sealed interface Outcome {
    /** 成功处理的目标数（上传时是写成功数，拉取时是扫描过的库数） */
    data class Done(val targets: Int) : Outcome

    /** 服务端一份可识别的偏好都没有 —— 不动本地，等用户手动上传 */
    data object NothingOnServer : Outcome

    /** 一个媒体库都没拿到（没登录 / 库列表为空） */
    data object NoTargets : Outcome

    /** 失败。[reason] 是能直接给人看的中文 */
    data class Failed(val reason: String) : Outcome
  }

  // ─── 本地快照 ───

  /**
   * 读当前本地偏好，编成快照。
   *
   * 「只同步显示类」是刻意的：算法选择、预解析直链这些**行为**开关不进服务端 ——
   * 它们是「这台设备怎么播」而不是「界面长什么样」，跟着账号跑到平板上
   * 反而会让平板莫名其妙开始预解析直链。
   */
  fun currentSnapshot(): Snapshot = Snapshot(
    viewMode = preferences.embyLibraryViewMode.get(),
    gridColumns = preferences.embyLibraryGridColumns.get(),
    cardStyle = preferences.embyLibraryCardStyle.get(),
    cardCorner = preferences.embyCardCorner.get(),
    cardSpacing = preferences.embyCardSpacing.get(),
    showCardBadges = preferences.embyShowCardBadges.get(),
    sortBy = preferences.embyLibrarySortBy.get(),
    sortOrder = preferences.embyLibrarySortOrder.get(),
  )

  /** 把快照写回本地偏好。服务端缺项时保留本地原值（[Snapshot] 里已回落好）。 */
  private fun applySnapshot(snapshot: Snapshot) {
    preferences.embyLibraryViewMode.set(snapshot.viewMode)
    preferences.embyLibraryGridColumns.set(snapshot.gridColumns)
    preferences.embyLibraryCardStyle.set(snapshot.cardStyle)
    preferences.embyCardCorner.set(snapshot.cardCorner)
    preferences.embyCardSpacing.set(snapshot.cardSpacing)
    preferences.embyShowCardBadges.set(snapshot.showCardBadges)
    preferences.embyLibrarySortBy.set(snapshot.sortBy)
    preferences.embyLibrarySortOrder.set(snapshot.sortOrder)
  }

  // ─── 上传 ───

  /**
   * 把本地显示偏好写到服务端**所有媒体库**。
   *
   * 返回成功写入的目标数；一个都没写成也算失败 —— 否则 UI 会显示「已同步」
   * 但其实一个库都没写进去。
   */
  suspend fun push(server: EmbyServer): Outcome = withContext(Dispatchers.IO) {
    val targets = targetsOf(server)
    if (targets.isEmpty()) return@withContext Outcome.NoTargets

    val snapshot = currentSnapshot()
    var ok = 0
    var lastError: String? = null
    for (id in targets) {
      runCatching {
        val existing = readOrEmpty(server, id)
        repository.postDisplayPreferences(
          server = server,
          displayPreferencesId = id,
          client = EmbyClient.DISPLAY_PREFS_CLIENT,
          preferences = EmbyDisplayPrefsCodec.merge(
            existing = existing,
            snapshot = snapshot,
            client = EmbyClient.DISPLAY_PREFS_CLIENT,
          ),
        )
      }.onSuccess { ok++ }
        .onFailure { lastError = it.readableReason() }
    }
    if (ok == 0) {
      Outcome.Failed(lastError ?: "所有媒体库都没写成功")
    } else {
      preferences.embyDisplayPrefsPushed.set(true)
      Outcome.Done(ok)
    }
  }

  // ─── 拉取 ───

  /**
   * 从服务端读回显示偏好并覆盖本地。
   *
   * 多个库各存一份，取**第一个带本 App 键的**那份为准：它们本该一样，
   * 出现不一样只可能是某台设备只同步了一部分，这时候取先读到的
   * 比「取最后一个覆盖前面」更可预期。
   *
   * @param libraries 调用方已经拉到的媒体库列表。传进来就**不再自己发一次 `/Views`**
   *   （首页加载时手上本来就有，见 [EmbyViewModel.loadHome]）；传 null 时按老路自己拉
   *   （设置页手动同步这类场景）。
   */
  suspend fun pull(server: EmbyServer, libraries: List<EmbyItem>? = null): Outcome =
    withContext(Dispatchers.IO) {
      val targets = libraries?.let { list ->
        list.mapNotNull { it.displayPrefsId() }.distinct()
      } ?: targetsOf(server)
      if (targets.isEmpty()) return@withContext Outcome.NoTargets

    val fallback = currentSnapshot()
    var found: Snapshot? = null
    var lastError: String? = null

    for (id in targets) {
      val prefs = runCatching { readOrEmpty(server, id) }
        .onFailure { lastError = it.readableReason() }
        .getOrNull() ?: continue
      if (prefs.CustomPrefs.keys.none { it.startsWith(PREF_PREFIX) }) continue
      found = EmbyDisplayPrefsCodec.decode(
        prefs = prefs.CustomPrefs,
        sortBy = prefs.SortBy,
        sortOrder = prefs.SortOrder,
        fallback = fallback,
      )
      break
    }

    val snapshot = found ?: run {
      // 一个带本 App 键的都没有：服务端是空的（第一次用、或只有网页端写过）。
      // 此时不动本地 —— 见类注释的「首启不覆盖」。
      return@withContext if (lastError != null) Outcome.Failed(lastError!!) else Outcome.NothingOnServer
    }
    applySnapshot(snapshot)
    Outcome.Done(targets.size)
  }

  /**
   * 冷启动 / 切服务器时调用：**只有用户主动上传过、且开关打开**才拉。
   *
   * 三个条件缺一不可 —— 见类注释「首启不覆盖」。
   *
   * @param libraries 调用方已经拉到的媒体库列表（见 [pull]）；传 null 则内部自己拉。
   */
  suspend fun pullIfEnabled(server: EmbyServer, libraries: List<EmbyItem>? = null): Outcome? {
    if (!preferences.embyDisplayPrefsAutoSync.get()) return null
    if (!preferences.embyDisplayPrefsPushed.get()) return null
    return pull(server, libraries)
  }

  // ─── 内部 ───

  /**
   * 所有可写的目标分区 Id。
   *
   * 取 `DisplayPreferencesId`，**取不到就退回条目自己的 `Id`** —— Emby 本身
   * 接受用条目 Id 当分区 Id，所以这条退化路径是有效的（不是随便猜的）。
   * 去重是必需的：多个库视图可能指向同一个 `DisplayPreferencesId`。
   */
  private suspend fun targetsOf(server: EmbyServer): List<String> {
    val libraries = runCatching { repository.getVirtualFolders(server) }
      .getOrDefault(emptyList())
    return libraries.mapNotNull { it.displayPrefsId() }.distinct()
  }

  private fun EmbyItem.displayPrefsId(): String? = DisplayPreferencesId?.takeIf { it.isNotBlank() }
    ?: Id?.takeIf { it.isNotBlank() }

  /** 读一份偏好；404 / 空响应都当「空偏好」，不是错误。 */
  private suspend fun readOrEmpty(
    server: EmbyServer,
    displayPreferencesId: String,
  ): EmbyDisplayPreferences = runCatching {
    repository.getDisplayPreferences(server, displayPreferencesId)
  }.getOrNull() ?: EmbyDisplayPreferences(Id = displayPreferencesId)

  private fun Throwable.readableReason(): String = when (this) {
    is EmbyApiException -> when (errorKind) {
      EmbyErrorKind.NETWORK -> "连不上服务器，检查网络"
      EmbyErrorKind.AUTH -> "登录已失效，请重新登录"
      else -> "服务器返回 $code"
    }
    else -> message ?: "未知错误"
  }

  private companion object {
    /** 本 App 写入 `CustomPrefs` 的键前缀，见 [EmbyDisplayPrefsCodec] */
    const val PREF_PREFIX = "mpvex."
  }
}
