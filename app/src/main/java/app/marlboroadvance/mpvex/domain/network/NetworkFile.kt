package app.marlboroadvance.mpvex.domain.network

import kotlinx.serialization.Serializable

/**
 * Represents a file or directory on a network share
 */
@Serializable
data class NetworkFile(
  val name: String,
  val path: String,
  val size: Long,
  val isDirectory: Boolean,
  val lastModified: Long = 0,
  val mimeType: String? = null,
)

/**
 * 网络条目的展示类型（卡片右上角角标 / 视图分流都按它判）。
 *
 * ⚠️ 与 mimeType 的关系：[NetworkFile.mimeType] 由客户端按扩展名猜，**不可靠**
 * （SMB/WebDAV/FTP 服务端不一定给 Content-Type，客户端那张表也只会越维护越歪）。
 * 真正做类型判定的一律用 [networkFileType]，它只认扩展名，纯函数、可单测。
 */
enum class NetworkFileType {
  FOLDER,
  VIDEO,
  PHOTO,
  OTHER,
  ;

  val displayName: String
    get() =
      when (this) {
        FOLDER -> "文件夹"
        VIDEO -> "视频"
        PHOTO -> "图片"
        OTHER -> "文件"
      }
}

/** 图片扩展名（小写，不含点）。覆盖相机 / 截图 / 设计稿的常见格式 */
val NETWORK_IMAGE_EXTENSIONS: Set<String> =
  setOf(
    "jpg", "jpeg", "jpe", "jfif",
    "png", "apng",
    "gif",
    "webp",
    "bmp", "dib",
    "heic", "heif", "hif",
    "avif",
    "tiff", "tif",
    "svg",
  )

/** 视频扩展名（小写，不含点）。与三个客户端 getMimeType 的表保持一致 */
val NETWORK_VIDEO_EXTENSIONS: Set<String> =
  setOf(
    "mp4", "m4v",
    "mkv",
    "avi",
    "mov",
    "wmv",
    "flv",
    "webm",
    "mpeg", "mpg", "mpe",
    "3gp", "3g2",
    "ts", "m2ts", "mts",
    "ogv", "ogm",
    "rm", "rmvb",
    "asf",
    "vob",
    "divx",
    "f4v",
  )

/** 文件扩展名（小写，不含点）；没有扩展名时返回空串 */
fun String.fileExtension(): String = substringAfterLast('.', "").lowercase()

/**
 * 按扩展名猜 MIME。三个客户端原来各写一份同构的 when（还都漏了图片格式），
 * 收敛到这里只维护一份。猜不出来返回 null —— 调用方按 [NetworkFileType.OTHER] 处理。
 */
fun guessNetworkMimeType(fileName: String): String? {
  val extension = fileName.fileExtension()
  return when {
    extension.isEmpty() -> null
    extension in NETWORK_IMAGE_EXTENSIONS -> when (extension) {
      "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"
      "png", "apng" -> "image/png"
      "gif" -> "image/gif"
      "webp" -> "image/webp"
      "bmp", "dib" -> "image/bmp"
      "heic", "heif", "hif" -> "image/heic"
      "avif" -> "image/avif"
      "tiff", "tif" -> "image/tiff"
      "svg" -> "image/svg+xml"
      else -> null
    }
    extension in NETWORK_VIDEO_EXTENSIONS -> when (extension) {
      "mp4", "m4v" -> "video/mp4"
      "mkv" -> "video/x-matroska"
      "avi" -> "video/x-msvideo"
      "mov" -> "video/quicktime"
      "wmv" -> "video/x-ms-wmv"
      "flv" -> "video/x-flv"
      "webm" -> "video/webm"
      "mpeg", "mpg", "mpe" -> "video/mpeg"
      "3gp" -> "video/3gpp"
      "3g2" -> "video/3gpp2"
      "ts" -> "video/mp2t"
      "m2ts", "mts" -> "video/mp2t"
      "ogv" -> "video/ogg"
      "ogm" -> "video/ogg"
      "rm", "rmvb" -> "video/vnd.rn-realvideo"
      "asf" -> "video/x-ms-asf"
      "vob" -> "video/mpeg"
      "divx" -> "video/divx"
      "f4v" -> "video/x-f4v"
      else -> null
    }
    else -> null
  }
}

/**
 * 这个条目该按什么类型展示。
 *
 * 目录优先（服务端把某些特殊「文件」也标成目录），其余按扩展名判；
 * 判不出的一律 OTHER（列表里给个通用文件卡，网格里直接不显示）。
 */
fun NetworkFile.type(): NetworkFileType =
  when {
    isDirectory -> NetworkFileType.FOLDER
    name.fileExtension() in NETWORK_IMAGE_EXTENSIONS -> NetworkFileType.PHOTO
    name.fileExtension() in NETWORK_VIDEO_EXTENSIONS -> NetworkFileType.VIDEO
    mimeType?.startsWith("image/") == true -> NetworkFileType.PHOTO
    mimeType?.startsWith("video/") == true -> NetworkFileType.VIDEO
    else -> NetworkFileType.OTHER
  }

/** 是否可进照片查看器 */
fun NetworkFile.isPhoto(): Boolean = type() == NetworkFileType.PHOTO

/** 是否可播放 */
fun NetworkFile.isVideoFile(): Boolean = type() == NetworkFileType.VIDEO
