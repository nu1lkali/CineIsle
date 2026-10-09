package app.marlboroadvance.mpvex.ui.browser.emby.subtitle

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyRemoteSubtitle
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.utils.media.ChecksumUtils
import app.marlboroadvance.mpvex.utils.media.MediaInfoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject

/**
 * 「在线字幕搜索 + 下载落盘」的 ViewModel。
 *
 * ## 三段式流程（不能省中间那一步）
 *
 * 1. [search] → `GET /Items/{Id}/RemoteSearch/Subtitles/{Lang}` 拿候选列表。
 *    候选里的 `Id` 是**字幕提供方内部的 ID**，**没有可直连的下载地址**
 *    （要自己下就得实现各提供方协议：OpenSubtitles 要 ApiKey，有些要登录态）。
 * 2. [download] → `POST /Items/{Id}/RemoteSearch/Subtitles/{SubtitleId}`
 *    **让服务端去下并存进媒体库**，返回新的字幕轨索引。
 *    顺带的好处：存完之后网页端 / 电视端也能用到这条字幕。
 * 3. [EmbyRepository.downloadSubtitleStream] 拿字节，[saveToLocalSubtitleDir] 落盘，
 *    再把 `content://` Uri 交给播放器挂载。
 *
 * ## 为什么落盘而不是直接给 mpv 一个 http URL
 *
 * mpv 确实能吃 URL（`SubtitleOps.autoloadNetworkSubtitles` 就是那么干的），
 * 但那条路是**盲猜 URL + 无鉴权头** —— Emby 要 `api_key` query 且每次都要走网络。
 * 落盘后有三个好处：
 *  · 离线可播（关掉网络也能显示字幕）；
 *  · 被既有的 `PlayerViewModel.scanLocalSubtitles()` **自动扫到**（下次开播即挂载）；
 *  · 被 `PlaybackStateEntity.externalSubtitles` 记住。
 *
 * ## 落盘位置沿用既有的 `subtitleSaveFolder`
 *
 * 目录名按 [PlayerViewModel.scanLocalSubtitles] 的三种口径依次尝试
 * （CRC32 / 全名 / 净化名），复用哪个算哪个 —— **只新建最后一个**，
 * 避免同一片子在字幕目录里散出三个文件夹。
 */
class SubtitleSearchViewModel(application: Application) : AndroidViewModel(application) {
  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)
  private val subtitlesPreferences by inject<SubtitlesPreferences>(SubtitlesPreferences::class.java)

  private val app: Application get() = getApplication()

  /**
   * 字幕保存目录是否已配置。
   *
   * 暴露成 StateFlow 而不是让 UI 去 `koinInject` 自己读：读偏好要回主线程，
   * 而这里的 ViewModel 已经有注入好的 [subtitlesPreferences]。
   */
  private val _saveFolderConfigured = MutableStateFlow(true)
  val saveFolderConfigured: StateFlow<Boolean> = _saveFolderConfigured.asStateFlow()

  private val _searching = MutableStateFlow(false)
  val searching: StateFlow<Boolean> = _searching.asStateFlow()

  init {
    // 初始化时读一次字幕保存目录，让 UI 能提前提示「还没配目录，下了也没地方放」
    runCatching { _saveFolderConfigured.value = subtitlesPreferences.subtitleSaveFolder.get().isNotBlank() }
  }

  private val _candidates = MutableStateFlow<List<EmbyRemoteSubtitle>>(emptyList())
  val candidates: StateFlow<List<EmbyRemoteSubtitle>> = _candidates.asStateFlow()

  private val _searchedLanguages = MutableStateFlow<Set<String>>(emptySet())
  val searchedLanguages: StateFlow<Set<String>> = _searchedLanguages.asStateFlow()

  /** 非 null 表示搜索失败。空列表 ≠ 失败：没配字幕源就是空。 */
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

  /** 重置成「还没搜过」的状态（换片子 / 换内核时调）。 */
  fun reset() {
    _candidates.value = emptyList()
    _searchedLanguages.value = emptySet()
    _error.value = null
  }

  /**
   * 搜在线字幕候选。
   *
   * @param languages 三字母语言码列表（如 `listOf("chi")`）。
   *   ⚠️ **Emby 没有一次搜多语言的端点**，每个语言各发一次请求 —— 所以串行跑。
   *
   * @return 合并后的候选列表。**已按质量排序**（精确匹配 > 评分 > 下载量），
   *   见 [rank]。服务端没配任何字幕提供方时返回**空列表**而不是报错 ——
   *   那是正常状态，UI 要显示「没找到字幕」而不是「搜索失败」。
   */
  suspend fun search(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    languages: List<String>,
    perfectMatchOnly: Boolean = false,
  ): List<EmbyRemoteSubtitle> {
    if (languages.isEmpty()) return emptyList()
    _searching.value = true
    _error.value = null
    try {
      // 串行而非并发：并发搜 3 个语种在弱网服务器上会互相拖慢，
      // 而且这类请求本来就带 provider 的外部调用，串行更稳。
      val merged = LinkedHashMap<String, EmbyRemoteSubtitle>()
      val done = mutableSetOf<String>()
      for (language in languages) {
        val result = runCatching {
          repository.searchRemoteSubtitles(
            server = server,
            itemId = itemId,
            language = language,
            mediaSourceId = mediaSourceId,
            isPerfectMatch = perfectMatchOnly.takeIf { it },
          )
        }
        if (result.isSuccess) {
          // 按 Id 去重：两个语种可能搜到同一条（提供方偶尔归错语言）
          result.getOrDefault(emptyList()).forEach { subtitle ->
            subtitle.Id?.let { merged.putIfAbsent(it, subtitle) }
          }
          done += language
        } else {
          // 单语种失败**不影响其它语种** —— 搜中英双语时，中文源挂了也还能给出英文。
          // 用 android.util.Log 而非 EmbyLog：后者是 domain.emby 包内的 internal object。
          android.util.Log.w(
            "SubtitleSearch",
            "搜 $language 失败：${result.exceptionOrNull()?.message}",
          )
        }
      }
      val ranked = rank(merged.values, perfectMatchOnly)
      _searchedLanguages.value = done
      _candidates.value = ranked
      if (done.isEmpty() && ranked.isEmpty()) {
        // 一个语种都没成功 ⇒ 是真的搜不了（而不是「搜到了 0 条」）
        _error.value = "字幕搜索失败，检查服务器是否配置了字幕提供方"
      }
      return ranked
    } finally {
      _searching.value = false
    }
  }

  /**
   * 字幕候选排序：**精确匹配 > 评分 > 下载量**，同档再按名字稳定排。
 *
   * 为什么这么排：Emby 搜出来的候选质量差异极大 —— 有些提供方不校验文件名，
 * 会把「同一部电影 2019 公映版」的字幕混进来，用户不逐条看根本认不出来。
 * `IsHashMatch` 是唯一可靠的机器判据，所以放第一位。
 *
 * 抽成**顶层函数**（而不是 private 方法）是为了能在 JVM 单测里直接测它 ——
 * 见 [app.marlboroadvance.mpvex.ui.browser.emby.subtitle.SubtitleRankingTest]。
 */
  private fun rank(
    candidates: Collection<EmbyRemoteSubtitle>,
    perfectMatchOnly: Boolean,
  ): List<EmbyRemoteSubtitle> = rankSubtitles(candidates, perfectMatchOnly)

  /**
   * 搜在线字幕。
   *
   * @param languages 三字母语言码列表（如 `listOf("chi")`）。
   *   ⚠️ **Emby 没有一次搜多语言的端点**，每个语言各发一次请求 —— 所以串行跑，
   *   且单语言失败不影响其它语言（一个语种没结果很常见）。
   * @param perfectMatchOnly 只看哈希精确匹配 —— 开了以后结果通常只剩 0~3 条但质量最高。
   * @param mediaTitle **媒体标题**（用于落盘目录命名，含扩展名，如
   *   `Movie.2020.1080p.mkv`）。落盘目录名要与 `scanLocalSubtitles` 的口径一致。
   * @return 落盘后的字幕 `Uri`；null 表示失败（错误已进 [error] / [toast]）。
   *
   * 之所以不直接返回 Uri 给播放器的 sheet（那样要跨 Composable 传回调），
   * 而是把结果塞进 [_downloadedUri]，是因为下载 + 落盘 + 挂载是一串异步动作，
   * 中间任何一步都可能失败，需要在**协程内**跑完。
   */
  suspend fun searchAndDownload(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String?,
    mediaTitle: String,
    language: String,
    subtitle: EmbyRemoteSubtitle,
    perfectMatchOnly: Boolean = false,
  ): Uri? {
    val subtitleId = subtitle.Id
    if (subtitleId.isNullOrBlank()) {
      _error.value = "这条字幕没有可用 ID（提供方返回的数据不完整）"
      return null
    }
    val currentServer = server
    _searching.value = true
    _error.value = null
    return try {
      // ── 第2 步：让服务端下载并入库 ──
      val result = repository.downloadRemoteSubtitle(currentServer, itemId, subtitleId, mediaSourceId)
      val newIndex = result.NewIndex
      if (newIndex == null) {
        _error.value = "服务端没能存下这条字幕（字幕源可能超时或格式不支持）"
        return null
      }

      // ── 第 3 步：取字节。优先用提供方给的原格式，取不到就退回 srt（最通用） ──
      val msId = mediaSourceId
      if (msId == null) {
        // 理论上不该发生：搜的时候就传了 mediaSourceId 会拿不到结果。
        _error.value = "缺少媒体源 Id，无法下载"
        return null
      }
      val bytes = downloadBytesRobustly(currentServer, itemId, msId, newIndex, subtitle.Format)

      // ── 第 4 步：落盘 ──
      val uri = saveToLocalSubtitleDir(bytes, mediaTitle, suffix = formatSuffix(subtitle.Format))
      _toast.value = "字幕已下载并挂载"
      uri
    } catch (t: Throwable) {
      _error.value = t.message ?: "字幕下载失败"
      null
    } finally {
      _searching.value = false
    }
  }

  /**
   * 按候选格式取字节，失败则回退 `srt`。
   *
   * Emby 的字幕格式转换能力**因服务端版本而异**，官方 OpenAPI 里也标了
   * `ass`/`ssa` 等格式「需要服务端插件支持」。直接要原格式在老服务端上
   * 会 404，所以必须有回退 —— 否则「有候选却下不下来」。
   */
  private suspend fun downloadBytesRobustly(
    server: EmbyServer,
    itemId: String,
    mediaSourceId: String,
    index: Int,
    preferFormat: String?,
  ): ByteArray {
    val preferred = preferFormat?.lowercase()?.takeIf { it in SUPPORTED_FORMATS }
    if (preferred != null) {
      runCatching { repository.downloadSubtitleStream(server, itemId, mediaSourceId, index, preferred) }
        .getOrNull()
        ?.let { return it }
    }
    // 回退 srt
    return repository.downloadSubtitleStream(server, itemId, mediaSourceId, index, "srt")
  }

  /**
   * 把字幕字节写进既有的字幕保存目录，返回可交给播放器的 `content://` Uri。
   *
   * 目录命名口径**必须**与 [app.marlboroadvance.mpvex.ui.player.PlayerViewModel.scanLocalSubtitles]
   * 一致（CRC32 / 全名 / 净化名三者依次尝试），否则下次开播扫不到、
   * 字幕就成了「下载了但没生效」。
   *
   * 三个目录里**已有任何一个存在就复用**，都不存在才新建最后一个（净化名）——
   * 新建第一个（CRC32）虽然扫的时候优先命中，但目录名是纯哈希，
   * 用户在文件管理器里完全看不懂是什么。
   *
   * @return 落盘后的 Uri；未配置保存目录时返回 null（错误已写 [error]）
   */
  private suspend fun saveToLocalSubtitleDir(
    bytes: ByteArray,
    mediaTitle: String,
    suffix: String,
  ): Uri? = withContext(Dispatchers.IO) {
    val saveFolderUri = subtitlesPreferences.subtitleSaveFolder.get()
    _saveFolderConfigured.value = saveFolderUri.isNotBlank()
    if (saveFolderUri.isBlank()) {
      _error.value = "还没设置字幕保存目录，去「设置 → 字幕」里选一个"
      return@withContext null
    }
    try {
      val parentDir =
        DocumentFile.fromTreeUri(app, Uri.parse(saveFolderUri))
          ?: return@withContext null.also { _error.value = "字幕目录已失效，请重新选择" }

      val sanitized = MediaInfoParser.parse(mediaTitle).title
      val full = mediaTitle.substringBeforeLast(".")
      val candidates = listOf(
        ChecksumUtils.getCRC32(mediaTitle),
        full,
        sanitized,
      ).distinct()

      var movieDir: DocumentFile? = candidates.asSequence()
        .map { parentDir.findFile(it) }
        .filterNotNull()
        .firstOrNull { it.isDirectory }

      if (movieDir == null) {
        // 都不存在 ⇒ 新建净化名那个（用户可读）
        movieDir = parentDir.findFile(sanitized)?.takeIf { it.isDirectory }
          ?: parentDir.createDirectory(sanitized)
      }
      val dir = movieDir
      if (dir == null || !dir.canWrite()) {
        _error.value = "字幕目录不可写，检查一下权限"
        return@withContext null
      }

      // 文件名带来源与语言，便于用户辨认；后缀决定播放器能不能认（见 isValidSubtitleFile）
      val fileName = buildString {
        append(sanitized.ifBlank { full })
        append('.')
        append(onlineSubtitleSuffix)
        append('.')
        append(suffix)
      }
      val existing = dir.findFile(fileName)
      if (existing != null) existing.delete() // 同名覆盖，避免旧内容残留
      val created = dir.createFile("application/octet-stream", fileName)
        ?: return@withContext null.also { _error.value = "字幕文件创建失败" }

      app.contentResolver.openOutputStream(created.uri)?.use { out ->
        out.write(bytes)
      } ?: return@withContext null.also { _error.value = "字幕写入失败" }

      created.uri
    } catch (t: Throwable) {
      _error.value = t.message ?: "字幕保存失败"
      null
    }
  }

  companion object {
    /**
     * 在线下载字幕的文件名标记。
     *
     * 放在文件名里是为了让用户在字幕目录里一眼认出「哪些是在线下的」。
     * 不参与播放判定 —— 播放只看扩展名（`isValidSubtitleFile`）。
     */
    const val onlineSubtitleSuffix = "online"

    /** Emby 服务端能转码输出的格式。别的（如 `smi`）一律走 srt 回退。 */
    val SUPPORTED_FORMATS = setOf("srt", "ass", "ssa", "vtt", "sub")

    /** 归一化后缀；不在白名单里就落到 srt。 */
    fun formatSuffix(format: String?): String =
      format?.lowercase()?.takeIf { it in SUPPORTED_FORMATS } ?: "srt"

    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { SubtitleSearchViewModel(application) }
    }
  }
}

/**
 * 字幕候选排序（顶层版，供单测直接调）。
 *
 * @param perfectMatchOnly 只保留哈希精确匹配项 —— 开了通常只剩 0~3 条但质量最高。
 * @see SubtitleSearchViewModel.rank
 */
fun rankSubtitles(
  candidates: Collection<EmbyRemoteSubtitle>,
  perfectMatchOnly: Boolean,
): List<EmbyRemoteSubtitle> =
  candidates
    .filter { !perfectMatchOnly || it.IsHashMatch }
    .sortedWith(
      compareByDescending<EmbyRemoteSubtitle> { it.IsHashMatch }
        .thenByDescending { it.CommunityRating ?: 0f }
        .thenByDescending { it.DownloadCount ?: 0 }
        .thenBy { it.Name ?: "" },
    )