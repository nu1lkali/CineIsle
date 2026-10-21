package app.marlboroadvance.mpvex.domain.emby

/**
 * 条目 **Primary 图**的实际载体：`(itemId, tag)`。
 *
 * Emby 的图片端点 `GET /Items/{itemId}/Images/Primary?tag={tag}` 中，`itemId`
 * **不总是**条目自己的 [EmbyItem.Id]：容器类条目（相册 `PhotoAlbum`、部分
 * `Folder`）自身没有主图，服务端把它「借用」的那张图的归属写在
 * [EmbyItem.PrimaryImageItemId] 上。
 *
 * 线上实证（库 1133915，Emby 4.9.3.0）：
 * ```
 * 相册条目 1133999：ImageTags={}  PrimaryImageTag=6f1059…  PrimaryImageItemId=1234948
 * GET /Items/1133999/Images/Primary  -> 404 "does not have an image of type Primary"
 * GET /Items/1234948/Images/Primary  -> 200 image/jpeg
 * ```
 * 也就是说：**只按条目自身 Id 取图，相册/文件夹必然是 404 → 占位图标**。
 * Web 端能显示预览图，用的就是 `PrimaryImageItemId`。
 */
data class EmbyPrimaryImageRef(val itemId: String, val tag: String?)

/**
 * 解析条目 Primary 图的真实归属；条目连 [EmbyItem.Id] 都没有时返回 null。
 *
 * 顺序刻意固定：
 *  1. 自己就有主图（[EmbyItem.ImageTags] 的 `Primary`）—— 绝大多数条目走这条；
 *  2. 没有但服务端给了「借用归属」（[EmbyItem.PrimaryImageItemId]）—— 相册 / 文件夹；
 *  3. 两者都没有 —— 退回「自己 + 无 tag」，行为与改动前完全一致
 *     （URL 里不带 tag，服务端能取到就用，取不到照旧显示占位图）。
 *
 * ⚠️ 第 3 条必须保留：不能因为「没有 tag」就返回 null，否则原本能显示的条目
 * （个别服务端不带 tag 也返回图）会被这次改动弄成永远空图。
 */
fun EmbyItem.primaryImageRef(): EmbyPrimaryImageRef? {
  val selfId = Id ?: return null
  ImageTags["Primary"]?.let { return EmbyPrimaryImageRef(selfId, it) }
  val owner = PrimaryImageItemId?.takeIf { it.isNotBlank() }
  if (owner != null) return EmbyPrimaryImageRef(owner, PrimaryImageTag)
  return EmbyPrimaryImageRef(selfId, null)
}

/**
 * 该条目的主图是否「借」在别的条目上（相册 / 部分文件夹 —— 服务端会把内部第一条媒体的图
 * 挂成容器的预览图，归属写在 [EmbyItem.PrimaryImageItemId] 上）。
 *
 * ⚠️ **不要拿它去决定「这个文件夹要不要拼宫格封面」。** 曾经在
 * `needsFolderMosaic` 里用过一条 `!hasBorrowedPrimaryImage()` 的豁免，真机现象是
 * 「同是视频文件夹，有的四宫格、有的一整张缩略图」：被服务端借了图的那些文件夹
 * 会整条跳过拼图、直接显示借来的那**一张**（也就是它内部第一个视频的一帧）。
 * 详细根因与被误伤的范围写在 `EmbyLibraryScreen.needsFolderMosaic` 的 KDoc 里。
 *
 * 现仅作为一个语义谓词保留（判断「这张脸是不是借来的」），当前无调用点。
 */
fun EmbyItem.hasBorrowedPrimaryImage(): Boolean =
  ImageTags["Primary"] == null && !PrimaryImageItemId.isNullOrBlank()
