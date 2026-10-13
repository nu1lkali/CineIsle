package app.marlboroadvance.mpvex.ui.browser.emby.photo

import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlin.math.roundToInt

/**
 * 照片链路的共享纯函数（URL 构造 / 卡片副标题 / 信息面板行）。
 *
 * 网格页、浏览器、保存器都要用这几样，单独放一个文件避免三处各写一遍
 * （同一份逻辑散开必不一致 —— 红线「同一份实现只允许一处」）。
 */

/** 照片显示图地址（原格式，HEIC 交给客户端解码）。 */
fun photoDisplayUrl(server: EmbyServer, item: EmbyItem, maxWidth: Int = 720): String? {
  val id = item.Id ?: return null
  return EmbyClient.photoImageUrl(
    server = server,
    itemId = id,
    tag = item.ImageTags["Primary"],
    maxWidth = maxWidth,
  )
}

/**
 * 照片显示图的 JPEG 兜底地址（服务端转码）。
 *
 * 原图解不了时用它：API < 28（没有 ImageDecoder）、AVIF 在 API 31 以下、
 * 以及个别 ROM 的 HEIF 解码有 bug。两个地址都试过还不行才显示占位。
 */
fun photoJpegFallbackUrl(server: EmbyServer, item: EmbyItem, maxWidth: Int = 720): String? {
  val id = item.Id ?: return null
  return EmbyClient.photoImageUrl(
    server = server,
    itemId = id,
    tag = item.ImageTags["Primary"],
    maxWidth = maxWidth,
    format = "jpg",
  )
}

/**
 * 照片卡片副标题：相机型号 + 像素尺寸。
 *
 * 照片没有「年份 / 评分」这类影视元数据，副标题给拍摄信息才有意义；
 * 两项都没有时返回 null（卡片不渲染副标题行）。
 */
fun photoSubtitle(item: EmbyItem): String? {
  val camera = listOfNotNull(item.CameraMake?.trim(), item.CameraModel?.trim())
    .filter { it.isNotBlank() }
    .joinToString(" ")
    .takeIf { it.isNotBlank() }
  val dims = if (item.Width != null && item.Height != null && item.Width > 0 && item.Height > 0) {
    "${item.Width}×${item.Height}"
  } else {
    null
  }
  return listOfNotNull(camera, dims)
    .filter { it.isNotBlank() }
    .joinToString(" · ")
    .takeIf { it.isNotBlank() }
}

/**
 * 「图片信息」面板的内容行（label → value）。
 *
 * EXIF 字段由 Emby 服务端在库扫描时写进条目实体、对 Photo 自动返回
 * （见 EmbyModels 照片字段段的注释）；缺的字段不进列表，不占位。
 */
fun photoInfoLines(item: EmbyItem): List<Pair<String, String>> {
  val lines = mutableListOf<Pair<String, String>>()
  item.Name?.takeIf { it.isNotBlank() }?.let { lines += "名称" to it }
  if (item.Width != null && item.Height != null && item.Width > 0 && item.Height > 0) {
    lines += "尺寸" to "${item.Width} × ${item.Height}"
  }
  val camera = listOfNotNull(item.CameraMake?.trim(), item.CameraModel?.trim())
    .filter { it.isNotBlank() }
    .joinToString(" ")
    .takeIf { it.isNotBlank() }
  camera?.let { lines += "相机" to it }
  val params = buildList {
    item.ExposureTime?.takeIf { it > 0 }?.let { add(formatShutter(it)) }
    item.Aperture?.takeIf { it > 0 }?.let { add("f/${trimZero(it)}") }
    item.FocalLength?.takeIf { it > 0 }?.let { add("${it.roundToInt()} mm") }
    item.IsoSpeedRating?.takeIf { it > 0 }?.let { add("ISO $it") }
  }
  if (params.isNotEmpty()) lines += "拍摄参数" to params.joinToString(" · ")
  item.Software?.takeIf { it.isNotBlank() }?.let { lines += "软件" to it }
  item.DateCreated?.takeIf { it.isNotBlank() }?.let { lines += "拍摄时间" to it.take(10) }
  if (item.Latitude != null && item.Longitude != null) {
    lines += "位置" to "${formatCoord(item.Latitude, true)}, ${formatCoord(item.Longitude, false)}"
  }
  item.Altitude?.let { lines += "海拔" to "${it.roundToInt()} m" }
  return lines
}

/** 曝光时间（秒）→ 「1/125s」「2.5s」 */
private fun formatShutter(seconds: Double): String =
  if (seconds >= 1.0) {
    "${trimZero(seconds)}s"
  } else {
    val denom = (1.0 / seconds).roundToInt()
    if (denom > 0) "1/${denom}s" else "${trimZero(seconds)}s"
  }

/** 去掉小数尾零：2.0 → "2"，2.8 → "2.8" */
private fun trimZero(value: Double): String {
  val rounded = value.roundToInt()
  return if (kotlin.math.abs(value - rounded) < 0.05) rounded.toString() else "%.1f".format(value)
}

/** 十进制度 → 「31.2304°N」 */
private fun formatCoord(value: Double, isLatitude: Boolean): String {
  val hemi = if (isLatitude) {
    if (value >= 0) "N" else "S"
  } else {
    if (value >= 0) "E" else "W"
  }
  return "%.4f°%s".format(kotlin.math.abs(value), hemi)
}
