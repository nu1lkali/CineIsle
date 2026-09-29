package app.marlboroadvance.mpvex.dlna

import android.net.Uri

/**
 * 待投屏的媒体描述。
 *
 * @param uri     媒体地址：http/https 直接交给渲染器拉流；file/content 由 UPnPCast 内置 HTTP 服务对外暴露。
 * @param title   展示名（投屏时作为 DIDL-Lite 标题传给设备）。
 * @param mimeType 可选 MIME 提示，本地文件通常无需指定，库会按扩展名推断。
 */
data class CastPayload(
  val uri: Uri,
  val title: String,
  val mimeType: String? = null,
)
