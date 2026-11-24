package app.marlboroadvance.mpvex.domain.subtitle.online

import android.util.Log
import app.marlboroadvance.mpvex.utils.media.ChecksumUtils
import app.marlboroadvance.mpvex.utils.media.MediaInfoParser
import java.io.File

/**
 * 把下载好的在线字幕**另存一份**到「设置 → 字幕 → 字幕保存目录」。
 *
 * ## 为什么不是「直接下载到那个目录」
 *
 * mpv 只认普通文件路径，而历史形态里的 SAF 目录给的是 `content://`。让在线字幕的
 * **权威副本**留在应用私有目录，播放 / 预览才能稳定拿到一条真实路径；同时往用户的
 * 字幕目录再写一份，解决另外三件事：
 *
 * · 用户在文件管理器里看得到、能自己备份或搬到别处；
 * · 播放器的 `scanLocalSubtitles` 会按目录口径扫到它 ⇒ 换客户端 / 重进播放页仍然可用；
 * · 卸载重装后，公共目录里那份还在（私有目录会被清掉），配合清单可精确还原索引。
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
   * 另存结果。
   *
   * [dirName] / [fileName] 一定要带出来：恢复流程要按「子目录 + 文件名」在用户目录里
   * 找回这份文件，而这两个名字是按「已有目录优先复用」的规则算出来的，调用方不该再猜
   * 一遍（重算就会因为命中不同候选目录而错位）。
   */
  data class Mirrored(
    val dirName: String,
    val fileName: String,
  )

  /**
   * 复制一份到字幕保存目录。
   *
   * @param root 已打开的保存目录（见 [SubtitleSaveStore.open]）；null 表示未配置，直接跳过
   * @param mediaTitle 媒体文件名（目录名与文件名都由它推导）
   * @return 成功时是落点位置与文件名；未配置 / 写失败返回 null
   *
   * 阻塞 IO：调用方需在 `Dispatchers.IO` 上执行。
   */
  fun mirror(
    root: SaveNode?,
    mediaTitle: String,
    source: File,
    format: String,
  ): Mirrored? {
    if (root == null) return null
    if (!source.isFile) return null
    return runCatching {
      val sanitized = MediaInfoParser.parse(mediaTitle).title
      val full = mediaTitle.substringBeforeLast(".")
      val candidates = listOf(ChecksumUtils.getCRC32(mediaTitle), full, sanitized).distinct()

      // 三个候选目录里**已有任何一个就复用**，都不存在才新建净化名（用户看得懂的那个）
      val movieDir =
        candidates.asSequence()
          .mapNotNull { root.child(it) }
          .firstOrNull { it.isDirectory }
          ?: root.mkdir(sanitized)
          ?: return@runCatching null

      val base = sanitized.ifBlank { full }.ifBlank { "subtitle" }
      val suffix = format.lowercase().ifBlank { "srt" }
      val fileName = "$base.$ONLINE_MARK.$suffix"
      // write() 对同名文件是「先删后建」/「覆盖」，不会像裸 createFile 那样生成 "xxx (1).srt"
      movieDir.write(fileName, source.readBytes()) ?: return@runCatching null

      Mirrored(dirName = movieDir.name, fileName = fileName)
    }.onFailure { Log.w(TAG, "mirror failed: ${it.message}") }.getOrNull()
  }
}
