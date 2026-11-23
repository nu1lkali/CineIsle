package app.marlboroadvance.mpvex.domain.subtitle.online

import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 在线字幕下载器。
 *
 * ## 为什么先收进内存再落盘
 *
 * 字幕文件只有几十 KB，但「下载下来的是不是一个字幕」必须在**写文件之前**判定：
 * 站点临时返回 404 页面 / 登录页 / Cloudflare 挑战页时，HTTP 状态码往往还是 200。
 * 先攒字节、验内容、再写盘，就不会在字幕库里留下一堆 `xxx.srt` 的 HTML。
 * （需求 12.10 / 12.12：不允许空文件或 HTML 错误页被当成字幕。）
 *
 * ## 取消与重试
 *
 * 进度回调与 `ensureActive()` 都在流式读取的循环里 —— 用户点「取消」时协程被取消，
 * 下一块数据到手就会退出，临时文件在 `finally` 里删掉。失败后重新调用即可重试，
 * 不会留下半成品。
 */
class OnlineSubtitleDownloader {
  /** 单条字幕上限。超过基本可以断定点错了（或站点把视频压成 .srt 返回）。 */
  private val maxBytes = 16L * 1024 * 1024

  data class Result(
    val file: File,
    val format: String,
    val sizeBytes: Long,
  )

  /**
   * 下载并落盘。
   *
   * @param targetDir 目标目录（通常是本地字幕库的媒体子目录），不存在会自动创建
   * @param baseName 文件名主干（不含扩展名，已由调用方清洗）
   * @param onProgress 0..1；服务器不给 `Content-Length` 时回调 null（界面显示不确定进度）
   * @throws IOException 网络失败 / 内容不是字幕 / 超过大小上限
   */
  suspend fun download(
    item: OnlineSubtitle,
    targetDir: File,
    baseName: String,
    onProgress: (Float?) -> Unit = {},
  ): Result =
    withContext(Dispatchers.IO) {
      if (!SubtitleHttp.isSafeHttpUrl(item.downloadUrl)) {
        throw IOException("下载地址不合法")
      }
      val request = SubtitleHttp.get(item.downloadUrl)
      val bytes =
        SubtitleHttp.downloadClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
          val contentType = response.body.contentType()?.toString().orEmpty()
          // 明确是网页 ⇒ 直接判失败，不用等正文
          if (contentType.contains("text/html", ignoreCase = true)) {
            throw IOException("返回的是网页而不是字幕文件")
          }
          val declared = response.body.contentLength()
          if (declared > maxBytes) throw IOException("文件过大")

          val stream = response.body.byteStream()
          val buffer = ByteArray(8 * 1024)
          val out = java.io.ByteArrayOutputStream(
            if (declared in 1..maxBytes) declared.toInt() else 64 * 1024,
          )
          var total = 0L
          while (true) {
            coroutineContext.ensureActive()
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) throw IOException("文件过大")
            out.write(buffer, 0, read)
            onProgress(
              if (declared > 0) (total.toFloat() / declared).coerceIn(0f, 1f) else null,
            )
          }
          out.toByteArray()
        }

      val sniffed = sniffFormat(bytes)
      if (sniffed == null) {
        throw IOException("下载内容不是可识别的字幕")
      }
      val format = item.format?.takeIf { it in KNOWN_FORMATS } ?: sniffed
      // 下载完再校一次格式声明：站点把 ASS 标成 srt 的情况很常见，
      // 用内容判定的结果覆盖声明，播放器才不会按 SRT 解析失败。
      val finalFormat = if (sniffed == "ass" || sniffed == "vtt") sniffed else format

      if (!targetDir.exists() && !targetDir.mkdirs()) throw IOException("无法创建字幕目录")
      val target = File(targetDir, "$baseName.${finalFormat.takeIf { it in KNOWN_FORMATS } ?: "srt"}")
      val tmp = File(targetDir, ".${target.name}.part")
      try {
        tmp.writeBytes(bytes)
        if (target.exists() && !target.delete()) throw IOException("无法覆盖旧字幕")
        if (!tmp.renameTo(target)) {
          tmp.copyTo(target, overwrite = true)
          tmp.delete()
        }
      } finally {
        if (tmp.exists()) tmp.delete()
      }
      Log.i(TAG, "downloaded ${item.source.label} subtitle: ${bytes.size} bytes ($finalFormat)")
      Result(file = target, format = target.extension.lowercase(), sizeBytes = bytes.size.toLong())
    }

  companion object {
    private const val TAG = "OnlineSubtitleDownload"

    val KNOWN_FORMATS = setOf("srt", "ass", "ssa", "vtt", "txt")

    /**
     * 按内容判断格式。判断不出来就返回 null（调用方据此判定「不是字幕」）。
     *
     * 只看开头 ~1KB：三种文本字幕的签名都在最前面。
     */
    fun sniffFormat(bytes: ByteArray): String? {
      if (bytes.isEmpty()) return null
      // 去掉 UTF-8 BOM，否则每一条 startsWith 都会错
      val head =
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
          bytes.copyOfRange(3, bytes.size)
        } else {
          bytes
        }
      val text = String(head, 0, minOf(head.size, 1024), Charsets.UTF_8)
      val trimmed = text.trimStart()
      if (trimmed.startsWith("WEBVTT")) return "vtt"
      if (trimmed.startsWith("[Script Info]", ignoreCase = true) ||
        trimmed.contains("[V4+ Styles]") || trimmed.contains("[V4 Styles]")
      ) {
        return "ass"
      }
      // SRT：一行纯数字 + 紧随其后的 `00:00:01,000 --> 00:00:04,000`
      if (SRT_TIMECODE.containsMatchIn(text)) return "srt"
      // HTML 错误页：明确不算字幕
      if (trimmed.startsWith("<") && (trimmed.contains("<html", true) || trimmed.contains("<!doctype", true))) {
        return null
      }
      return null
    }

    private val SRT_TIMECODE =
      Regex("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-+>\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}")
  }
}
