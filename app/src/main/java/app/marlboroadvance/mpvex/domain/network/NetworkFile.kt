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
  /** 修改时间（mtime，epoch 毫秒）。服务端不给就 0 */
  val lastModified: Long = 0,
  /**
   * 创建时间（ctime，epoch 毫秒）。**目前只有 AList 会填** ——
   * `fs/list` 的 `created` 字段是可选的，WebDAV/SMB/FTP 的库都不暴露 ctime，
   * 因此其余协议恒为 0，按「创建时间」排序时它们会统一垫底。
   */
  val createdAt: Long = 0,
  val mimeType: String? = null,
  /**
   * AList 的签名（`fs/list`、`fs/get` 返回的 `sign`），其余协议恒为 null。
   *
   * 有了它可以拼出 `{server}/d/<路径>?sign=<sign>` 这种**不带凭据也能取**的直链
   * —— 播放器与图片加载都不必再过认证头。签名有时效，服务端不给就当没有。
   */
  val sign: String? = null,
  /**
   * 服务端返回的封面图直链（AList `fs/list` 每条带的 `thumb`），**已归一化成绝对 URL**；
   * 其余协议恒为 null。
   *
   * AList 对图片返回的就是图片本身（缩略尺寸由服务端定），对视频返回海报帧。
   * 有它就直接拉这张渲染（比客户端「流式拉原图 + 解码采样」省流量、出图快）；
   * 没有 / 拉取失败 ⇒ 退回客户端生成缩略图（见 [NetworkApiThumbnail]）。
   * 签名有时效，过期了这里就拉不到，正好触发兜底。
   */
  val thumb: String? = null,
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

/**
 * 「最近添加时间」（epoch 毫秒），没有可用的时间信号时返回 0。
 *
 * 没有任何协议会返回真正的「入库时间」（SMB/WebDAV/FTP 只有 mtime，AList 多一个 ctime），
 * 所以这里取一个**最接近「这条是最近才出现的」**的近似：
 * - **文件**：`max(修改时间, 创建时间)` —— 回填/改名的老文件 mtime 会变新，
 *   刚拷进来的新文件 ctime 就是当下，两者取大都能被最近的排序浮到前面；
 * - **目录**：只用 `lastModified` —— 多数服务端在目录内容变动时会 bump 目录的 mtime
 *   （AList 的目录 ctime 往往就是建目录那一刻，拿来当「最近添加」会永远垫底、
 *   反而把刚塞满新素材的目录压到最下面）。目录 ctime 不可靠，就不参与。
 *
 * 两个值都为 0（服务端没给）⇒ 返回 0，排序时统一垫底，不会冒充「1970 年最新」。
 */
val NetworkFile.recentlyAddedAt: Long
  get() =
    if (isDirectory) {
      lastModified
    } else {
      maxOf(lastModified, createdAt)
    }

/**
 * 「文件类型」排序键：文件夹 → 视频 → 图片 → 其他，与列表视图的 section 顺序一致。
 *
 * ⚠️ 真正比的是**扩展名**（同类型内部再按扩展名自然序细排），
 *    不只看这个大档位 —— 否则一堆 mkv/mp4 会全部同键、只能靠名称兜底。
 */
fun NetworkFile.typeSortKey(): TypeSortKey =
  TypeSortKey(
    rank =
      when (type()) {
        NetworkFileType.FOLDER -> 0
        NetworkFileType.VIDEO -> 1
        NetworkFileType.PHOTO -> 2
        NetworkFileType.OTHER -> 3
      },
    extension = if (isDirectory) "" else name.fileExtension(),
  )

/** 文件类型排序的两段键：先大档位，再扩展名（两者都比自然序更适合字符串字典序） */
data class TypeSortKey(
  /** 0=文件夹 1=视频 2=图片 3=其他 */
  val rank: Int,
  /** 小写扩展名，无扩展名为空串（排序时自然浮到该档位最前） */
  val extension: String,
)
