package app.marlboroadvance.mpvex.domain.subtitle.online

import android.content.Context
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「下载一条候选 → 落进本地库」这一步的统一实现。
 *
 * ## 为什么单独抽出来
 *
 * 这段逻辑原本只写在 [app.marlboroadvance.mpvex.ui.subtitle.online.OnlineSubtitleViewModel] 里，
 * 字幕实验室的「重新下载」要再用一次。需求 11.1 明确禁止两个入口各写一套下载 / 落盘，
 * 所以把它收在这里：**建档 + 另存字幕目录 + 写索引**，调用方只管下载字节和界面状态。
 *
 * ## 它不做什么
 *
 * · 不发网络请求（字节由 [OnlineSubtitleDownloader] 负责）；
 * · 不写手动绑定（那是播放侧的事，见 `SubtitleBindings`，由调用的 ViewModel 决定要不要绑）。
 */
object OnlineSubtitlePersist {
  /**
   * @param mediaTitle 媒体标题（同时作为绑定键与字幕目录的目录名来源）
   * @param result 下载器返回的落盘结果
   * @param saveFolderUri 「设置 → 字幕」里的字幕保存目录；空串表示不另存
   * @return 写进索引的那条记录（`archived` 表示是否已另存到字幕目录）
   */
  suspend fun save(
    context: Context,
    library: OnlineSubtitleLibrary,
    preferences: SubtitlesPreferences,
    serverKey: String,
    itemId: String,
    mediaTitle: String,
    item: OnlineSubtitle,
    result: OnlineSubtitleDownloader.Result,
    saveFolderUri: String,
  ): DownloadedSubtitle =
    withContext(Dispatchers.IO) {
      val bindTitle = mediaTitle.ifBlank { item.title }
      // 保存目录：绝对路径优先，SAF 树 URI 是早期版本遗留。只打开一次，
      // 另存与清单写的是同一个目录句柄 —— 两次 open 有可能命中不同形态（用户刚改过设置）。
      val saveRoot =
        SubtitleSaveStore.open(
          context = context,
          dirPath = preferences.subtitleSaveDirPath.get(),
          legacyTreeUri = saveFolderUri,
        )
      // 另存一份到用户配置的字幕目录（未配置则跳过）。失败不影响主记录 ——
      // 私有目录那份才是播放真正读的文件。
      val mirrored =
        OnlineSubtitleSaveFolder.mirror(
          root = saveRoot,
          mediaTitle = bindTitle,
          source = result.file,
          format = result.format,
        )
      val record =
        DownloadedSubtitle(
          id = "$serverKey|$itemId|${item.id}",
          serverKey = serverKey,
          itemId = itemId,
          mediaTitle = bindTitle,
          source = item.source,
          title = item.title,
          fileName = library.relativePathOf(result.file),
          format = result.format,
          language = item.language,
          sizeBytes = result.sizeBytes,
          downloadedAt = System.currentTimeMillis(),
          offsetMs = 0L,
          preferred = false,
          originId = item.id,
          originUrl = item.downloadUrl,
          archived = mirrored != null,
        )
      val saved = library.upsert(record)
      // 清单写的是「哪条记录对应用户目录里的哪个文件」，所以必须用落库后的记录
      // （偏移量 / 优先标记可能被 upsert 合并过），而不是刚构造的这份。
      if (mirrored != null) {
        SubtitleSaveManifest.upsert(
          root = saveRoot,
          entry =
            SubtitleSaveManifest.Entry(
              serverKey = saved.serverKey,
              itemId = saved.itemId,
              originId = saved.originId,
              mediaTitle = saved.mediaTitle,
              title = saved.title,
              sourceKey = saved.source.key,
              dirName = mirrored.dirName,
              fileName = mirrored.fileName,
              format = saved.format,
              language = saved.language,
              sizeBytes = saved.sizeBytes,
              downloadedAt = saved.downloadedAt,
              offsetMs = saved.offsetMs,
              preferred = saved.preferred,
              originUrl = saved.originUrl,
            ),
        )
      }
      saved
    }

  /**
   * 重新下载一条已存在的记录。
   *
   * 用记录里存下的 [DownloadedSubtitle.originUrl] 直接重放一次，不再走搜索 ——
   * 站点换了关键词口径或排序变了，也不影响「我之前下的就是这条」。
   *
   * @return 更新后的记录（偏移量 / 优先标记由 [OnlineSubtitleLibrary.upsert] 保留）
   * @throws IllegalStateException 旧记录没有下载地址时
   */
  suspend fun redownloadAndSave(
    context: Context,
    library: OnlineSubtitleLibrary,
    preferences: SubtitlesPreferences,
    record: DownloadedSubtitle,
    downloader: OnlineSubtitleDownloader,
    saveFolderUri: String,
    onProgress: (Float?) -> Unit = {},
  ): DownloadedSubtitle {
    if (record.originUrl.isBlank()) {
      throw IllegalStateException("这条记录没有保存下载地址，请重新搜索后再下载")
    }
    val item =
      OnlineSubtitle(
        id = record.originId.ifBlank { record.id },
        source = record.source,
        title = record.title,
        fileName = record.title,
        format = record.format,
        language = record.language,
        downloadUrl = record.originUrl,
      )
    val result =
      downloader.download(
        item = item,
        targetDir = library.mediaDir(record.serverKey, record.itemId),
        baseName = OnlineSubtitleLibrary.downloadBaseName(item),
        onProgress = onProgress,
      )
    return save(
      context = context,
      library = library,
      preferences = preferences,
      serverKey = record.serverKey,
      itemId = record.itemId,
      mediaTitle = record.mediaTitle,
      item = item,
      result = result,
      saveFolderUri = saveFolderUri,
    )
  }
}
