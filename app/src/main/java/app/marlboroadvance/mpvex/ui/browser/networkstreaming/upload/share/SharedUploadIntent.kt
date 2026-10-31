package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import java.util.Locale

/**
 * 从系统「分享」送进来的一个媒体项。
 *
 * 只有 [uri] 是权威来源：分享进来的是别的 App 授予的 `content://`
 * （典型是 `com.android.providers.media` 的相册条目），scoped storage 下
 * **不可能**拿到可靠的 `_data` 路径，所以整条链路只认 URI。
 */
data class SharedMedia(
  val uri: Uri,
  /** 落盘时用的文件名（已去重、已清洗） */
  val name: String,
  /** 系统报的字节数；拿不到是 -1（真正上传前还会再 stat 一次） */
  val sizeBytes: Long,
  val mimeType: String?,
) {
  val isVideo: Boolean
    get() = mimeType?.startsWith("video/") == true ||
      name.substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXTENSIONS

  val isImage: Boolean
    get() = mimeType?.startsWith("image/") == true ||
      name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS
}

private val IMAGE_EXTENSIONS =
  setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng", "tif", "tiff")

private val VIDEO_EXTENSIONS =
  setOf("mp4", "m4v", "mkv", "mov", "3gp", "webm", "avi", "ts", "m2ts", "mts", "flv", "wmv")

private const val TAG = "SharedUploadIntent"

/**
 * 把 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 的 Intent 解析成待上传清单。
 *
 * ## 三条数据来源都要收
 *
 * 1. `EXTRA_STREAM` —— 单条分享；
 * 2. `EXTRA_STREAM`（`ArrayList<Uri>`）—— 多选分享；
 * 3. `ClipData` —— 部分相册/文件管理器只填 ClipData（尤其是把「分享」实现成
 *    拖拽语义的那些），漏了它就会出现「点了分享却什么都没进来」。
 *
 * 顺序保持不变：相册多选时用户是按自己点选顺序期待的，排序会显得莫名其妙。
 *
 * ## 为什么要自己算文件名
 *
 * 目标文件的名字由这里决定（AList 只认路径）。`DISPLAY_NAME` 是首选；
 * 拿不到就退回 `uri.lastPathSegment`，再拿不到才按 MIME 猜个 `IMG_<ts>.<ext>`。
 * 同名必须在本批次内去重（相册里同一张图选了两次、或不同目录同名文件），
 * 否则两个任务写同一个远端路径，后一个会覆盖前一个，而用户以为传了两份。
 */
object SharedUploadIntent {

  fun parse(context: Context, intent: Intent?): List<SharedMedia> {
    if (intent == null) return emptyList()
    val uris = collectUris(intent)
    if (uris.isEmpty()) return emptyList()

    val used = HashSet<String>(uris.size * 2)
    return uris.distinctBy { it.toString() }.mapNotNull { uri ->
      runCatching { describe(context, uri) }
        .onFailure { Log.w(TAG, "解析分享项失败: $uri | ${it.message}") }
        .getOrNull()
        ?.let { media -> media.copy(name = uniqueName(sanitize(media.name), used)) }
    }
  }

  private fun collectUris(intent: Intent): List<Uri> {
    val out = ArrayList<Uri>(4)
    val stream = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
    if (stream != null) out += stream
    intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let { out += it }
    val clip = intent.clipData
    if (clip != null) {
      for (i in 0 until clip.itemCount) {
        clip.getItemAt(i)?.uri?.let { out += it }
      }
    }
    return out.filter { it.scheme != null }
  }

  private fun describe(context: Context, uri: Uri): SharedMedia? {
    val resolver = context.contentResolver
    var name: String? = null
    var size = -1L
    // getType 也可能返回 null（个别 App 不声明），所以名字/MIME 都留了退路
    val mime = runCatching { resolver.getType(uri) }.getOrNull()

    runCatching {
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
          }
        }
    }

    val fallbackName = "share_${System.currentTimeMillis()}"
    val raw = name?.takeIf { it.isNotBlank() }
      ?: uri.lastPathSegment?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
      ?: fallbackName

    // 没有扩展名时按 MIME 补一个：AList 的 type 判定、播放器识别、缩略图全靠它
    val withExt = if (raw.substringAfterLast('.', "").isEmpty()) raw + extensionFor(mime) else raw
    return SharedMedia(uri = uri, name = withExt, sizeBytes = size, mimeType = mime)
  }

  /**
   * 清掉文件名里会破坏远端路径的字符。
   *
   * `/` 与 `\` 会把一个文件变成两层目录；控制字符与首尾空白在各家对象存储上表现不一致。
   * 这里**不**做全量白名单（会误伤中文、emoji、空格），只掐掉真正致命的几个。
   */
  internal fun sanitize(name: String): String {
    val cleaned = name
      .replace('/', '_')
      .replace('\\', '_')
      .filterNot { it.isISOControl() }
      .trim()
      .trim('.')
    return cleaned.ifBlank { "share_${System.currentTimeMillis()}" }
  }

  /** 批次内同名去重：`a.jpg` → `a (1).jpg` */
  internal fun uniqueName(name: String, used: MutableSet<String>): String {
    if (used.add(name)) return name
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var i = 1
    while (true) {
      val candidate = "$base ($i)$ext"
      if (used.add(candidate)) return candidate
      i++
    }
  }

  private fun extensionFor(mime: String?): String =
    when {
      mime == null -> ""
      mime.startsWith("image/") ->
        when (mime.removePrefix("image/").lowercase(Locale.ROOT)) {
          "jpeg" -> ".jpg"
          "svg+xml" -> ".svg"
          "x-adobe-dng" -> ".dng"
          else -> "." + mime.removePrefix("image/").lowercase(Locale.ROOT)
        }
      mime.startsWith("video/") ->
        when (mime.removePrefix("video/").lowercase(Locale.ROOT)) {
          "mp2t" -> ".ts"
          "quicktime" -> ".mov"
          "x-matroska" -> ".mkv"
          else -> "." + mime.removePrefix("video/").lowercase(Locale.ROOT)
        }
      mime.startsWith("audio/") -> ".${mime.removePrefix("audio/").lowercase(Locale.ROOT)}"
      else -> ""
    }
}

/** `getParcelableExtra` 在 API 33 之后必须带类型，老写法已废弃 */
@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(key, T::class.java)
  } else {
    getParcelableExtra(key) as? T
  }

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable>
  Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<T>? =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableArrayListExtra(key, T::class.java)
  } else {
    // 老 API 只有泛型擦除的版本：类型参数必须**显式**给，否则从赋值上下文推不出来
    getParcelableArrayListExtra<T>(key)
  }
