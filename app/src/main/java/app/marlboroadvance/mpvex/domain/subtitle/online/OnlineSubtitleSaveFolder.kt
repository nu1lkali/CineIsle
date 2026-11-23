package app.marlboroadvance.mpvex.domain.subtitle.online

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.marlboroadvance.mpvex.utils.media.ChecksumUtils
import app.marlboroadvance.mpvex.utils.media.MediaInfoParser
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把下载好的在线字幕**另存一份**到「设置 → 字幕 → 字幕保存目录」。
 *
 * ## 为什么不是「直接下载到那个目录」
 *
 * mpv 只认普通文件路径，不认 `content://`（SAF 目录给的就是 content URI）。让在线字幕
 * 的**权威副本**留在应用私有目录，播放 / 预览才能稳定拿到一条真实路径；同时往用户的
 * 字幕目录再写一份，解决另外三件事：
 *
 * · 用户在文件管理器里看得到、能自己备份或搬到别处；
 * · 播放器的 `scanLocalSubtitles` 会按目录口径扫到它 ⇒ 换客户端 / 重进播放页仍然可用；
 * · 卸载重装后，公共目录里那份还在（私有目录会被清掉）。
 *
 * ## 命名口径必须与既有实现一致
 *
 * 目录名依次尝试 `CRC32(媒体名)` / 全名 / 净化名 —— 与
 * [app.marlboroadvance.mpvex.ui.browser.emby.subtitle.SubtitleSearchViewModel] 落 Emby 字幕时
 * 完全一致，也与 `PlayerViewModel.scanLocalSubtitles` 的扫描顺序一致。
 * 文件名带 `.online.` 标记，便于用户在字幕目录里一眼认出「这是客户端在线下的」。
 *
 * ⚠️ 没有配置保存目录时**什么都不做**（返回 null），不弹错 —— 私有目录那份已经够用。
 */
object OnlineSubtitleSaveFolder {
  private const val TAG = "OnlineSubtitleSaveFolder"

  /** 文件名里的标记段。与 Emby 字幕搜索共用同一套（`xxx.online.srt`）。 */
  const val ONLINE_MARK = "online"

  /**
   * 复制一份到字幕保存目录。
   *
   * @param saveFolderUri `SubtitlesPreferences.subtitleSaveFolder`（SAF tree URI 字符串）
   * @param mediaTitle 媒体文件名（目录名与文件名都由它推导）
   * @return 成功时是新建文件的 Uri；未配置 / 无权限 / 写失败都返回 null
   */
  suspend fun mirror(
    context: Context,
    saveFolderUri: String,
    mediaTitle: String,
    source: File,
    format: String,
  ): Uri? =
    withContext(Dispatchers.IO) {
      if (saveFolderUri.isBlank()) return@withContext null
      if (!source.isFile) return@withContext null
      runCatching {
        val app = context.applicationContext
        val parentDir = DocumentFile.fromTreeUri(app, Uri.parse(saveFolderUri))
          ?: return@runCatching null

        val sanitized = MediaInfoParser.parse(mediaTitle).title
        val full = mediaTitle.substringBeforeLast(".")
        val candidates = listOf(ChecksumUtils.getCRC32(mediaTitle), full, sanitized).distinct()

        // 三个候选目录里**已有任何一个就复用**，都不存在才新建净化名（用户看得懂的那个）
        val movieDir =
          candidates.asSequence()
            .map { parentDir.findFile(it) }
            .filterNotNull()
            .firstOrNull { it.isDirectory }
            ?: parentDir.findFile(sanitized)?.takeIf { it.isDirectory }
            ?: parentDir.createDirectory(sanitized)
        if (movieDir == null || !movieDir.canWrite()) return@runCatching null

        val base = sanitized.ifBlank { full }.ifBlank { "subtitle" }
        val suffix = format.lowercase().ifBlank { "srt" }
        val fileName = "$base.$ONLINE_MARK.$suffix"
        // 同名先删再建：SAF 的 createFile 会自动改名成 "xxx (1).srt"，
        // 那样每下一次就多一份，且扫描时按文件名认不出是同一条。
        movieDir.findFile(fileName)?.delete()
        val created = movieDir.createFile("application/octet-stream", fileName) ?: return@runCatching null

        app.contentResolver.openOutputStream(created.uri)?.use { out ->
          source.inputStream().use { input -> input.copyTo(out) }
        } ?: return@runCatching null

        created.uri
      }.onFailure { Log.w(TAG, "mirror failed: ${it.message}") }.getOrNull()
    }
}
