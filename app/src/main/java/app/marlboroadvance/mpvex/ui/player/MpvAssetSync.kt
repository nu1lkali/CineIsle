package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import com.github.k1rakishou.fsaf.FileManager
import java.io.File

/**
 * mpv 外部资源的同步器（清单 1.5 巨型类拆分，第 1 刀）。
 *
 * ## 为什么要拆出来
 *
 * 这块逻辑原先内嵌在 `PlayerActivity` 里（连注释带方法约 230 行），特征是：
 * **只在 `initializeMpvAndFinishSetup` 的后台任务里被调一次，不碰任何播放器状态**。
 * 它要的东西也很少 —— `filesDir`、`contentResolver`、两个 Preferences、`FileManager`，
 * 全部可以从构造函数显式注入。
 *
 * 留在 Activity 里的代价是：读播放页代码的人要跨过这一大段与播放毫无关系的
 * 文件拷贝逻辑才能看到真正的初始化流程；而这段逻辑恰恰是「配置从哪来」
 * 这个问题的唯一答案所在，独立成文件后反而更好找。
 *
 * ## 刻意保留的行为（不要「顺手优化」）
 *
 * - **字体已存在就跳过**（`if (target.exists()) return@forEach`）。
 *   字体动辄几十 MB，每次进播放页全量重拷一遍是不可接受的。这里是
 *   「首次导入」语义，不是「保持一致」语义 —— 用户改了字体文件也不会
 *   自动更新，这是已知取舍。
 * - **单个文件失败不中断整批**：每个 `runCatching` 只包一个文件。
 *   目录里有一个坏文件不该让所有配置都同步失败。
 * - **配置双写**：既落到 `filesDir`（给 mpv 读），也缓存进 Preferences
 *   （给配置编辑器读）。少写一处，对应功能就会显示空白。
 */
class MpvAssetSync(
  private val context: Context,
  private val advancedPreferences: AdvancedPreferences,
  private val subtitlesPreferences: SubtitlesPreferences,
  private val fileManager: FileManager,
) {

  /**
   * 同步全部 mpv 资源：mpv.conf、input.conf、fonts/。
   *
   * 用户没配 mpv 目录时回落到 Preferences 里的配置内容。
   * 必须在 `player.initialize()` **之前**调用：`glsl-shaders` / `input-conf` 这类
   * 是 setOptionString，过了初始化期再设不生效。
   */
  fun syncFromUserMpvDirectory() {
    val storageUri = advancedPreferences.mpvConfStorageUri.get()

    // 用户目录可能没配、可能已失效（SD 卡拔了 / 授权被回收）
    val tree = if (storageUri.isNotBlank()) {
      runCatching { DocumentFile.fromTreeUri(context, storageUri.toUri()) }
        .getOrNull()
        ?.takeIf { it.exists() && it.canRead() }
    } else {
      null
    }

    if (tree != null) {
      Log.d(TAG, "Syncing from user MPV directory: ${tree.uri}")
      syncConfigFiles(tree)
      syncFonts(tree)
      Log.d(TAG, "Full MPV directory sync completed")
    } else {
      Log.d(TAG, "No MPV directory configured, using preferences fallback")
      copyConfigFromPreferences()
    }
  }

  /** 从用户目录同步 mpv.conf / input.conf，并缓存到 Preferences 供配置编辑器使用。 */
  private fun syncConfigFiles(tree: DocumentFile) {
    for (configName in CONFIG_NAMES) {
      runCatching {
        val configFile = findFileCaseInsensitive(tree, configName)
        if (configFile != null && configFile.exists() && configFile.canRead()) {
          context.contentResolver.openInputStream(configFile.uri)?.use { input ->
            val content = input.bufferedReader().readText()
            File(context.filesDir, configName).writeText(content)
            // 双写的第二份：配置编辑器读的是这里
            when (configName) {
              "mpv.conf" -> advancedPreferences.mpvConf.set(content)
              "input.conf" -> advancedPreferences.inputConf.set(content)
            }
            Log.d(TAG, "Synced config: $configName (${content.length} chars)")
          }
        } else {
          // 目录里没有这个配置文件，退回用 Preferences 里存的那份
          val prefContent = when (configName) {
            "mpv.conf" -> advancedPreferences.mpvConf.get()
            "input.conf" -> advancedPreferences.inputConf.get()
            else -> ""
          }
          File(context.filesDir, configName).apply {
            if (!exists()) createNewFile()
            if (prefContent.isNotBlank()) writeText(prefContent)
          }
          Log.d(TAG, "Config not found in directory, used preferences: $configName")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing config: $configName", e)
      }
    }
  }

  /**
   * 同步字体文件到 `filesDir/fonts/`，供 mpv 的字幕渲染使用。
   *
   * 先找 `fonts/` 子目录（大小写不敏感），没找到就在根目录里扫。
   * 之后还会从字幕设置里指定的字体目录再补一批。
   */
  private fun syncFonts(tree: DocumentFile) {
    val internalFontsDir = File(context.filesDir, "fonts")
    internalFontsDir.mkdirs()

    val sourceDir = findSubdirCaseInsensitive(tree, "fonts") ?: tree
    var count = 0

    sourceDir.listFiles().forEach { file ->
      if (!file.isFile) return@forEach
      val name = file.name ?: return@forEach
      if (name.substringAfterLast('.', "").lowercase() !in FONT_EXTENSIONS) return@forEach

      val target = File(internalFontsDir, name)
      // 字体很大，已存在就跳过（首次导入语义，不是保持一致语义）
      if (target.exists()) return@forEach

      runCatching {
        context.contentResolver.openInputStream(file.uri)?.use { input ->
          target.outputStream().use { output -> input.copyTo(output) }
          count++
          Log.d(TAG, "Synced font: $name")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing font: $name", e)
      }
    }

    // 字幕设置里单独指定的字体目录，也一并搬过来
    runCatching {
      val fontsFolderUri = subtitlesPreferences.fontsFolder.get()
      if (fontsFolderUri.isNotBlank()) {
        val destDir = fileManager.fromPath("${context.filesDir.path}/fonts")
        if (!fileManager.exists(destDir)) {
          fileManager.createDir(fileManager.fromPath(context.filesDir.path), "fonts")
        }
        val fontsDir = fileManager.fromUri(fontsFolderUri.toUri())
        if (fontsDir != null && fileManager.exists(fontsDir)) {
          fileManager.copyDirectoryWithContent(fontsDir, destDir, false)
        }
      }
    }.onFailure { e ->
      Log.e(TAG, "Error syncing subtitle fonts: ${e.message}")
    }

    Log.d(TAG, "Fonts sync: $count file(s) from MPV directory")
  }

  /** 回落路径：用户没配 mpv 目录时，用 Preferences 里的内容生成配置文件。 */
  private fun copyConfigFromPreferences() {
    runCatching {
      File(context.filesDir, "mpv.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.mpvConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      File(context.filesDir, "input.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.inputConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      // 即便没有用户目录，字体目录也要先建出来 —— mpv 找不到会报错
      File(context.filesDir, "fonts").mkdirs()
    }.onFailure { e ->
      Log.e(TAG, "Error creating fallback config files", e)
    }
  }

  /** 按名字找子目录（大小写不敏感）。 */
  private fun findSubdirCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? =
    parent.listFiles().firstOrNull {
      it.isDirectory && it.name?.equals(name, ignoreCase = true) == true
    }

  /** 按名字找文件（大小写不敏感）。 */
  private fun findFileCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? =
    parent.listFiles().firstOrNull {
      it.isFile && it.name?.equals(name, ignoreCase = true) == true
    }

  private companion object {
    const val TAG = "MpvAssetSync"
    val CONFIG_NAMES = listOf("mpv.conf", "input.conf")
    val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "woff", "woff2")
  }
}
