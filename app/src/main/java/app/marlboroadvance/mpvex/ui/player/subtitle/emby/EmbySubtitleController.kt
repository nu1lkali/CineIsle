package app.marlboroadvance.mpvex.ui.player.subtitle.emby

import android.content.Context
import android.util.Log
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.EmbySubtitleDiscovery
import app.marlboroadvance.mpvex.domain.emby.EmbySubtitleFetcher
import app.marlboroadvance.mpvex.domain.emby.SubtitleDiscovery
import app.marlboroadvance.mpvex.domain.emby.SubtitleFetchResult
import app.marlboroadvance.mpvex.domain.emby.subtitle.EmbySubtitleChoices
import app.marlboroadvance.mpvex.domain.emby.subtitle.EmbySubtitleSelector
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleInfo
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleSelection
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTombstoneStore
import app.marlboroadvance.mpvex.domain.subtitle.SubtitleTombstones
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Emby 字幕的**控制器**：发现 → 选择 → 取字节 → 落缓存。
 *
 * 只管「字幕从哪来、选哪条、字节放在哪」，**不碰 mpv** —— 挂载与选中由
 * [app.marlboroadvance.mpvex.ui.player.PlayerViewModel] 完成。这样切分的好处：
 * 播放内核（mpv / GSY）换了、或者以后要加别的来源（本地目录、在线搜索），
 * 这一层都不用动。
 *
 * ## 与既有字幕体系的关系（不能互相踩）
 *
 * | 来源 | 谁负责 | 谁优先 |
 * |---|---|---|
 * | 用户手动绑定（`SubtitleBindings`） | 播放页恢复流程 | **最高** |
 * | 存档里的 `sid`（`PlaybackStateEntity`） | 播放页恢复流程 | 次高 |
 * | 每剧字幕指纹（`PlaybackMemory`） | `applyRememberedPlaybackSettings` | 中 |
 * | **Emby 自动发现/选择（本类）** | 本类 + PlayerViewModel | **最低** |
 *
 * 所以调用方必须在「没有存档、没有绑定、当前也没选着字幕」时才允许自动挂载；
 * 用户手动从面板里点一条时则走「显式选择」，不受上述限制。
 *
 * ## 状态是 StateFlow
 *
 * 面板要显示「Emby 认得哪些字幕、选了哪条、为什么」，这些都得驱动重组。
 */
class EmbySubtitleController(
  private val context: Context,
  private val subtitlesPreferences: SubtitlesPreferences,
) {
  enum class Phase { IDLE, DISCOVERING, READY, DOWNLOADING, FAILED }

  data class State(
    val phase: Phase = Phase.IDLE,
    val serverId: String? = null,
    val itemId: String? = null,
    val mediaSourceId: String? = null,
    /** Emby 认得的字幕（已按服务端顺序） */
    val subtitles: List<SubtitleInfo> = emptyList(),
    /** 自动选择结果；null = 还没跑过 */
    val selection: SubtitleSelection? = null,
    /** 正在下载的那一条（面板显示 loading） */
    val downloadingKey: String? = null,
    /** 已经挂到 mpv 上的那条（面板打勾用） */
    val mountedKey: String? = null,
    /** 一句话提示（下载失败 / 格式不支持），null = 无事发生 */
    val notice: String? = null,
    val error: String? = null,
  )

  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state.asStateFlow()

  /**
   * 「第几次加载」的令牌。每次 [reset] 自增。
   *
   * 用途：换片 / 换集时上一集的发现或下载可能**还没回来**，它们回来时会照原样写
   * `_state`（比如写一条「服务端没有这条字幕的该格式」）。不清掉的话，新一集的字幕面板
   * 上会莫名挂着上一集的失败提示 —— 正是「状态互相覆盖」这类看不见的 bug。
   * 拿到旧令牌的回调**只写日志，不写状态**；已写好的缓存文件照旧留下（按 ItemId 分键，
   * 下次播那一集直接命中，不是浪费）。
   */
  private val generation = AtomicInteger(0)

  private val cache = EmbySubtitleCache(context)
  private val tombstones = SubtitleTombstoneStore(subtitlesPreferences)

  /** 换片 / 退出时清空。**必须调**，否则上一集的字幕会挂到新一集上。 */
  fun reset() {
    generation.incrementAndGet()
    _state.value = State()
  }

  /** 只在这一轮加载仍然有效时写状态 */
  private inline fun updateIfCurrent(gen: Int, block: (State) -> State) {
    if (gen == generation.get()) _state.update(block)
  }

  private inline fun updateIfCurrentTarget(
    gen: Int,
    serverId: String,
    itemId: String,
    mediaSourceId: String?,
    block: (State) -> State,
  ) {
    updateIfCurrent(gen) { state ->
      if (state.serverId == serverId && state.itemId == itemId && state.mediaSourceId == mediaSourceId) {
        block(state)
      } else {
        state
      }
    }
  }

  fun markMounted(key: String?) {
    _state.update { it.copy(mountedKey = key) }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 发现
  // ══════════════════════════════════════════════════════════════════════

  /**
   * 发现 + 选择。**不下载**。
   *
   * @param mediaSourceId 当前在播的媒体源；与 ItemId 一起决定「这份字幕清单属于谁」
   */
  suspend fun discoverAndSelect(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
  ): SubtitleSelection = withContext(Dispatchers.IO) {
    val gen = generation.get()
    updateIfCurrent(gen) {
      it.copy(
        phase = Phase.DISCOVERING,
        serverId = server.id.toString(),
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        subtitles = emptyList(),
        selection = null,
        mountedKey = null,
        notice = null,
        error = null,
      )
    }

    val discovery = runCatching {
      EmbySubtitleDiscovery.discover(server, itemId, mediaSourceId)
    }.getOrElse { t ->
      Log.w(TAG, "字幕发现失败：item=$itemId ${t.message}")
      SubtitleDiscovery(
        mediaSourceId = mediaSourceId,
        subtitles = emptyList(),
        error = t.message ?: "字幕发现失败",
      )
    }

    logDiscovery(itemId, mediaSourceId, discovery.subtitles, discovery.totalStreams, discovery.error)

    val resolvedMediaSourceId = discovery.mediaSourceId ?: mediaSourceId
    val serverId = server.id.toString()
    val visible = SubtitleTombstones.filterEmby(discovery.subtitles, tombstones.snapshot()) { subtitle ->
      SubtitleTombstones.embyKey(
        serverId = serverId,
        itemId = itemId,
        subtitle = normalizedInfo(resolvedMediaSourceId, subtitle),
      )
    }
    val preferred = preferredLanguageList()
    val allowed = allowedLanguageList()
    val localeHints = localeLanguageHints(preferred, allowed)
    // 这条日志是「为什么自动挂了/没挂」的第一现场：语言偏好整条链路只有这里能看到
    Log.i(TAG, "语言偏好：首选=$preferred 允许=$allowed 系统兜底=$localeHints")
    val selection = EmbySubtitleSelector.select(
      subtitles = visible,
      preferredLanguages = preferred,
      allowedLanguages = allowed,
      rememberedKey = rememberedKey(serverId, itemId, resolvedMediaSourceId),
      allowEmbyDefault = subtitlesPreferences.embyFollowDefault.get(),
      localeLanguages = localeHints,
    )
    logSelection(selection)

    updateIfCurrent(gen) {
      it.copy(
        phase = if (discovery.error != null && visible.isEmpty()) Phase.FAILED else Phase.READY,
        serverId = serverId,
        itemId = itemId,
        mediaSourceId = resolvedMediaSourceId,
        subtitles = visible,
        selection = selection,
        error = discovery.error,
      )
    }
    selection
  }

  // ══════════════════════════════════════════════════════════════════════
  // 取字节（缓存优先）
  // ══════════════════════════════════════════════════════════════════════

  /**
   * 拿到一条字幕的**本地文件**（缓存命中则直接返回，不发请求）。
   *
   * @return 成功时返回文件；失败返回 null，原因写进 [State.notice] / [State.error]
   */
  suspend fun localFile(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    subtitle: SubtitleInfo,
    forceRefresh: Boolean = false,
  ): File? = withContext(Dispatchers.IO) {
    // 这一轮加载的令牌：下载可能跨过一次换集（用户直接点了下一集），回来时状态不能乱写
    val gen = generation.get()
    val serverId = server.id.toString()
    val info = normalizedInfo(mediaSourceId, subtitle)
    if (isIgnored(serverId, itemId, info)) {
      Log.i(TAG, "字幕已删除，拒绝再次取字节：${info.describe()}")
      return@withContext null
    }
    if (info.externalRequestFormats.isEmpty()) {
      val reason = subtitle.unsupportedReason ?: "这条字幕没有可下载的格式"
      Log.w(TAG, "字幕不可下载：${subtitle.describe()} —— $reason")
      updateIfCurrent(gen) { it.copy(notice = reason) }
      return@withContext null
    }

    if (!forceRefresh) {
      cache.cached(serverId, itemId, info)?.let { file ->
        Log.i(TAG, "字幕缓存命中：${info.describe()} ${file.length()} B")
        return@withContext file
      }
    }

    updateIfCurrent(gen) { it.copy(phase = Phase.DOWNLOADING, downloadingKey = info.key, notice = null) }
    // ⚠️ 日志只打路径，**绝不打 URL 的 query**（里面有 api_key）
    Log.i(
      TAG,
      "字幕下载开始：item=$itemId ms=${info.mediaSourceId} " +
        "index=${info.streamIndex} 渠道=${info.sourceType} 候选=${info.externalRequestFormats}",
    )
    val result = EmbySubtitleFetcher.fetch(server, itemId, mediaSourceId, info)
    updateIfCurrent(gen) { it.copy(downloadingKey = null, phase = Phase.READY) }

    when (result) {
      is SubtitleFetchResult.Ok -> {
        if (info.isTextSubtitle && !EmbySubtitleFetcher.looksLikeText(result.bytes)) {
          Log.w(TAG, "字幕内容不是文本（含 NUL 字节）：${info.describe()}")
          cache.invalidate(serverId, itemId, info)
          updateIfCurrent(gen) { it.copy(notice = "字幕文件内容异常，已忽略") }
          return@withContext null
        }
        // 下载期间用户可能已经删除：绝不能把刚下载的文件重新塞进缓存 / 挂载流程。
        if (isIgnored(serverId, itemId, info)) {
          runCatching { cache.invalidate(serverId, itemId, info) }
          updateIfCurrent(gen) { it.copy(notice = "这条字幕已被删除") }
          return@withContext null
        }
        val file = cache.store(serverId, itemId, info, result.bytes, result.format)
        Log.i(
          TAG,
          "字幕下载完成：${info.describe()} 格式=${result.format} " +
            "降级=${result.degraded} 渠道=${if (result.viaDeliveryUrl) "DeliveryUrl" else "Stream"} " +
            "大小=${result.sizeBytes}B 耗时=${result.elapsedMs}ms 落盘=${file?.name ?: "失败"}",
        )
        if (file == null) {
          updateIfCurrent(gen) { it.copy(notice = "字幕缓存写入失败") }
          return@withContext null
        }
        if (isIgnored(serverId, itemId, info)) {
          runCatching { cache.invalidate(serverId, itemId, info) }
          updateIfCurrent(gen) { it.copy(notice = "这条字幕已被删除") }
          return@withContext null
        }
        if (result.degraded) {
          updateIfCurrent(gen) { it.copy(notice = "服务端只能提供 ${result.format.uppercase()}，样式可能丢失") }
        }
        runCatching { cache.trim() }
        file
      }
      is SubtitleFetchResult.Failed -> {
        Log.w(TAG, "字幕下载失败：${info.describe()} 原因=${result.reason} code=${result.httpCode}")
        // 404 = 这条流已经不存在（服务端删了/换源了），旧缓存必须清掉
        if (result.httpCode == 404) cache.invalidate(serverId, itemId, info)
        updateIfCurrent(gen) { it.copy(notice = result.reason) }
        null
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 用户手动选择（面板里点一条）
  // ══════════════════════════════════════════════════════════════════════

  /**
   * 记住「这部片子用这条字幕」，下次开播优先它（需求四 第一优先级）。
   *
   * 解析 / 拼装都走 [EmbySubtitleChoices]：详情页读的是同一份存储，
   * 两处各写一套格式必然对不上（见那个 object 的注释）。
   */
  fun rememberChoice(
    serverId: String,
    itemId: String,
    mediaSourceId: String?,
    subtitle: SubtitleInfo?,
  ) {
    val info = subtitle?.let { normalizedInfo(mediaSourceId, it) }
    if (info != null && isIgnored(serverId, itemId, info)) return
    val current = subtitlesPreferences.embySubtitleChoices.get()
    subtitlesPreferences.embySubtitleChoices.set(
      EmbySubtitleChoices.withChoice(current, itemId, info?.key),
    )
  }

  fun rememberedKey(serverId: String, itemId: String, mediaSourceId: String?): String? {
    val key = EmbySubtitleChoices.keyOf(subtitlesPreferences.embySubtitleChoices.get(), itemId)
      ?: return null
    val streamIndex = key.substringAfterLast('#').toIntOrNull() ?: return key
    val keyMediaSource = key.substringBeforeLast('#').takeIf { it.isNotBlank() } ?: mediaSourceId
    return key.takeUnless {
      tombstones.isEmbyIgnored(serverId, itemId, keyMediaSource, streamIndex)
    }
  }

  /** 清掉「这部片子记住的字幕」（用户选「自动选择」时）。 */
  fun forgetChoice(itemId: String) {
    val current = subtitlesPreferences.embySubtitleChoices.get()
    subtitlesPreferences.embySubtitleChoices.set(EmbySubtitleChoices.withChoice(current, itemId, null))
  }

  /**
   * 删除一条 Emby 字幕：**先同步持久化 tombstone**，再清选择 / 失效缓存 / 更新面板。
   *
   * 只操作本地缓存与偏好，绝不请求服务端修改媒体库。即使缓存文件删除失败，tombstone
   * 仍会让后续发现、选择与缓存读取拒绝它。
   */
  suspend fun delete(
    serverId: String,
    itemId: String,
    mediaSourceId: String?,
    subtitle: SubtitleInfo,
  ): Boolean {
    val gen = generation.get()
    return withContext(Dispatchers.IO) {
      val info = normalizedInfo(mediaSourceId, subtitle)
      val persisted = tombstones.rememberEmby(
        serverId = serverId,
        itemId = itemId,
        mediaSourceId = info.mediaSourceId,
        streamIndex = info.streamIndex,
      )
      if (!persisted) {
        Log.w(TAG, "Emby tombstone persist failed: server=$serverId item=$itemId index=${info.streamIndex}")
        updateIfCurrentTarget(gen, serverId, itemId, info.mediaSourceId) {
          it.copy(notice = "删除记录保存失败，字幕仍保留")
        }
        return@withContext false
      }

      // 选择账本保持详情页兼容的旧格式；清写失败也不能让已删除字幕复活，因为选择器还会
      // 按 tombstone 过滤，这里记录告警并继续完成本地删除。
      val currentChoice = subtitlesPreferences.embySubtitleChoices.get()
      val nextChoice = EmbySubtitleChoices.withChoice(currentChoice, itemId, null)
      val choiceCleared = runCatching {
        subtitlesPreferences.embySubtitleChoices.setBlocking(nextChoice) &&
          subtitlesPreferences.embySubtitleChoices.get() == nextChoice
      }.getOrDefault(false)
      if (!choiceCleared) Log.w(TAG, "Emby choice clear failed: item=$itemId")

      runCatching { cache.invalidate(serverId, itemId, info) }
        .onFailure { Log.w(TAG, "Emby cache invalidate failed: item=$itemId index=${info.streamIndex}") }

      val deletedKey = info.key
      updateIfCurrentTarget(gen, serverId, itemId, info.mediaSourceId) { state ->
        state.copy(
          subtitles = state.subtitles.filterNot { normalizedInfo(state.mediaSourceId, it).key == deletedKey },
          selection = state.selection.takeUnless { it?.chosen?.key == deletedKey },
          downloadingKey = state.downloadingKey.takeUnless { it == deletedKey },
          mountedKey = state.mountedKey.takeUnless { it == deletedKey },
          phase = Phase.READY,
          notice = "已删除这条 Emby 字幕（服务端媒体库未修改）",
        )
      }
      true
    }
  }

  fun isIgnored(serverId: String, itemId: String, subtitle: SubtitleInfo): Boolean {
    val info = normalizedInfo(subtitle.mediaSourceId, subtitle)
    return tombstones.isEmbyIgnored(serverId, itemId, info.mediaSourceId, info.streamIndex)
  }

  private fun normalizedInfo(mediaSourceId: String?, subtitle: SubtitleInfo): SubtitleInfo =
    if (subtitle.mediaSourceId != null) subtitle else subtitle.copy(mediaSourceId = mediaSourceId)

  // ══════════════════════════════════════════════════════════════════════
  // 偏好
  // ══════════════════════════════════════════════════════════════════════

  /** 首选语言（有序）。空 = 用户没设过，那就只跟 Emby 默认标记。 */
  fun preferredLanguageList(): List<String> =
    subtitlesPreferences.preferredLanguages.get()
      .split(',', ' ', ';')
      .map { it.trim() }
      .filter { it.isNotEmpty() }

  /** 允许的替补语言（为空 = 不限）。 */
  fun allowedLanguageList(): List<String> =
    subtitlesPreferences.embyAllowedLanguages.get()
      .split(',', ' ', ';')
      .map { it.trim() }
      .filter { it.isNotEmpty() }

  fun autoEnabled(): Boolean = subtitlesPreferences.embyAutoSubtitles.get()

  /**
   * 设备系统语言（有序，带地区，如 `zh-CN`），用作「用户什么都没配」时的兜底。
   *
   * ⚠️ **只**在用户一条语言偏好都没配过时才返回非空：用户自己配过就以他的设置为准，
   * 系统语言不能盖过用户设置（否则改系统语言会把用户的偏好悄悄顶掉）。
   *
   * 为什么需要这层兜底：偏好多语言、Emby 又没标 default 时，没有它就只能「保持关闭」，
   * 用户看到的是「服务端明明有字幕却一条都不挂」。手机是简中就选简中，这是最不容易
   * 选错的一种猜测。
   */
  private fun localeLanguageHints(
    preferred: List<String>,
    allowed: List<String>,
  ): List<String> {
    if (preferred.isNotEmpty() || allowed.isNotEmpty()) return emptyList()
    val locales = runCatching { context.resources.configuration.locales }.getOrNull()
      ?: return emptyList()
    return (0 until locales.size()).mapNotNull { i ->
      val locale = locales[i] ?: return@mapNotNull null
      val language = locale.language?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      if (locale.country.isNullOrBlank()) language else "$language-${locale.country}"
    }.distinct()
  }

  // ══════════════════════════════════════════════════════════════════════
  // 日志
  // ══════════════════════════════════════════════════════════════════════

  private fun logDiscovery(
    itemId: String,
    requestedMs: String?,
    subtitles: List<SubtitleInfo>,
    totalStreams: Int,
    error: String?,
  ) {
    Log.i(
      TAG,
      "字幕清单：item=$itemId 请求源=${requestedMs ?: "默认"} 流=$totalStreams 字幕=${subtitles.size}" +
        (error?.let { " 错误=$it" } ?: ""),
    )
    subtitles.forEach { s ->
      Log.i(
        TAG,
        "  · index=${s.streamIndex} lang=${s.language ?: "-"}→${s.languageKey} codec=${s.codec ?: "-"} " +
          "ext=${s.isExternal} text=${s.isTextSubtitle} 默认=${s.isDefault} 强制=${s.isForced} " +
          "听障=${s.isHearingImpaired} 交付=${s.deliveryMethod ?: "-"} 来源=${s.sourceType} " +
          "支持=${s.unsupportedReason ?: "是"}",
      )
    }
  }

  private fun logSelection(selection: SubtitleSelection) {
    selection.trace.forEach { Log.i(TAG, "选择过程：$it") }
    val chosen = selection.chosen
    Log.i(
      TAG,
      "自动选择结果：${chosen?.describe() ?: "关闭字幕"} 原因=${selection.reason} (${selection.detail})",
    )
  }

  companion object {
    const val TAG = "EmbySubtitle"
  }
}
