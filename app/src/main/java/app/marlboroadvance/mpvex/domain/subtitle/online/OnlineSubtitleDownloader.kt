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
  private val maxBytes = MAX_SUBTITLE_BYTES

  data class Result(
    val file: File,
    val format: String,
    val sizeBytes: Long,
  )

  /** 只取内容、不落盘的结果（[format] 是按内容判定的真实格式）。 */
  data class Fetched(
    val bytes: ByteArray,
    val format: String,
  )

  /**
   * 只把内容读进内存，**不写任何文件**。
   *
   * 用于搜索结果的时间线预览：用户还没决定要不要这条字幕，为了看一眼就落盘
   * 会在本地库里留下一堆没用的条目。校验口径与 [download] 完全相同
   * （非 2xx / HTML 页 / 超大 / 认不出格式一律抛异常）。
   */
  suspend fun fetch(
    item: OnlineSubtitle,
    onProgress: (Float?) -> Unit = {},
  ): Fetched = withContext(Dispatchers.IO) { fetchBytes(item, onProgress) }

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
      val fetched = fetchBytes(item, onProgress)
      val bytes = fetched.bytes
      val finalFormat = fetched.format

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

  /**
   * 取字节 + 按内容判格式（不落盘）。[download] 与 [fetch] 共用的唯一网络路径，
   * 校验规则只维护这一份。
   */
  private suspend fun fetchBytes(
    item: OnlineSubtitle,
    onProgress: (Float?) -> Unit,
  ): Fetched {
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

    // ── 站点的两种常见「套壳」：压缩包 / 网页 ──
    // 迅雷与 SubtitleCat 都有把单条字幕打包成 zip 的链接（有时扩展名仍写着 .ass），
    // 这里直接解出里面的字幕文件，用户不必知道「刚才下的是个压缩包」。
    var payload = bytes
    if (isZip(payload)) {
      payload =
        extractFirstSubtitleFromZip(payload)
          ?: throw IOException("站点返回的是压缩包，但里面没有可用的字幕文件")
    }

    val sniffed = sniffFormat(payload)
    if (sniffed == null) {
      throw IOException(describeNonSubtitle(payload))
    }
    val format = item.format?.takeIf { it in KNOWN_FORMATS } ?: sniffed
    // 下载完再校一次格式声明：站点把 ASS 标成 srt 的情况很常见，
    // 用内容判定的结果覆盖声明，播放器才不会按 SRT 解析失败。
    val finalFormat = if (sniffed == "ass" || sniffed == "vtt") sniffed else format
    return Fetched(bytes = payload, format = finalFormat)
  }

  companion object {
    private const val TAG = "OnlineSubtitleDownload"

    /** 单条字幕上限（16MB）：与实例里的 maxBytes 同一口径，解包时也要用。 */
    private const val MAX_SUBTITLE_BYTES = 16L * 1024 * 1024

    /** 嗅探时读入的头部长度。ASS 的 `[Events]` 段可能排在样式之后，1KB 有时不够。 */
    private const val HEAD_BYTES = 8 * 1024

    val KNOWN_FORMATS = setOf("srt", "ass", "ssa", "vtt", "txt")

    /**
     * 按内容判断格式。判断不出来就返回 null（调用方据此判定「不是字幕」）。
     *
     * ## 为什么不再要求「文件必须以标记开头」
     *
     * 旧实现用 `startsWith("[Script Info]")` 判 ASS，结果三类正常文件被误杀：
     * · 开头有空行 / 注释行；
     * · 站点在真正的 ASS 之前塞了自定义头；
     * · 老 SSA 只有 `[Events]` + `Dialogue:`，没有 `[Script Info]`。
     * 现在改成「在头部范围内包含标记」，并补上 `[Events] + Dialogue:` 这条兜底。
     *
     * ## 为什么先解码再判
     *
     * ASS 有相当一部分是 **UTF-16LE** 保存的（带 `FF FE` BOM）。直接按 UTF-8 读会得到
     * 一串乱码，任何标记都匹配不上 —— 这正是「ASS 下载后提示不是可识别的字幕」的根因之一。
     */
    fun sniffFormat(bytes: ByteArray): String? {
      if (bytes.isEmpty()) return null
      val text = decodeHead(bytes)
      if (text.isBlank()) return null
      val trimmed = text.trimStart()
      if (trimmed.startsWith("WEBVTT", ignoreCase = true)) return "vtt"
      if (trimmed.contains("[Script Info]", ignoreCase = true) ||
        trimmed.contains("[V4+ Styles]", ignoreCase = true) ||
        trimmed.contains("[V4 Styles]", ignoreCase = true) ||
        (trimmed.contains("[Events]", ignoreCase = true) &&
          trimmed.contains("Dialogue:", ignoreCase = true))
      ) {
        return "ass"
      }
      // SRT：一行纯数字 + 紧随其后的 `00:00:01,000 --> 00:00:04,000`
      if (SRT_TIMECODE.containsMatchIn(text)) return "srt"
      return null
    }

    /** 取头部按 BOM 决定的编码解成文本（UTF-8 / UTF-16LE / UTF-16BE）。 */
    private fun decodeHead(bytes: ByteArray): String {
      if (bytes.size >= 2) {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xFE) return slice(bytes, 2, Charsets.UTF_16LE)
        if (b0 == 0xFE && b1 == 0xFF) return slice(bytes, 2, Charsets.UTF_16BE)
      }
      val start =
        if (bytes.size >= 3 &&
          bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
          3
        } else {
          0
        }
      return slice(bytes, start, Charsets.UTF_8)
    }

    private fun slice(
      bytes: ByteArray,
      start: Int,
      charset: java.nio.charset.Charset,
    ): String {
      if (start >= bytes.size) return ""
      var length = minOf(bytes.size - start, HEAD_BYTES)
      // UTF-16 按两字节对齐：截到半个字符会解码出替换符
      if (charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE) length -= length % 2
      return String(bytes, start, length, charset)
    }

    private fun isZip(bytes: ByteArray): Boolean =
      bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
        (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte())

    private fun isRar(bytes: ByteArray): Boolean =
      bytes.size >= 4 &&
        bytes[0] == 0x52.toByte() && bytes[1] == 0x61.toByte() &&
        bytes[2] == 0x72.toByte() && bytes[3] == 0x21.toByte()

    /**
     * 从 zip 里取出第一条**内容确实是字幕**的条目。
     *
     * 不按扩展名认：站点经常把 `.ass` 存成 `.txt` 或反过来。扩展名只用来筛掉
     * 图片 / 视频条目，最终判据仍是 [sniffFormat]。
     */
    private fun extractFirstSubtitleFromZip(bytes: ByteArray): ByteArray? =
      runCatching {
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
          var entry = zip.nextEntry
          while (entry != null) {
            if (!entry.isDirectory) {
              val name = entry.name.substringAfterLast('/')
              val ext = name.substringAfterLast('.', "").lowercase()
              val worthReading = ext in KNOWN_FORMATS || ext.isEmpty()
              if (worthReading) {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                  val read = zip.read(buffer)
                  if (read <= 0) break
                  total += read
                  if (total > MAX_SUBTITLE_BYTES) break
                  out.write(buffer, 0, read)
                }
                val data = out.toByteArray()
                if (data.isNotEmpty() && sniffFormat(data) != null) {
                  return@use data
                }
              }
            }
            entry = zip.nextEntry
          }
          null
        }
      }.getOrNull()

    /** 失败时给一句能指导下一步的说明，而不是统一的「不是可识别的字幕」。 */
    private fun describeNonSubtitle(bytes: ByteArray): String {
      val head = decodeHead(bytes).trimStart()
      return when {
        head.startsWith("<") &&
          (head.contains("<html", ignoreCase = true) ||
            head.contains("<!doctype", ignoreCase = true)) ->
          "站点返回的是网页而不是字幕文件（链接可能已失效或需要登录）"
        isZip(bytes) -> "压缩包里没有可用的字幕文件"
        isRar(bytes) -> "站点返回的是 RAR 压缩包，暂不支持自动解包"
        bytes.size >= 4 && bytes[0] == 0x37.toByte() && bytes[1] == 0x7A.toByte() ->
          "站点返回的是 7z 压缩包，暂不支持自动解包"
        // 二进制内容（多数是图片字幕 PGS / VobSub 的裸流）
        head.count { it.code < 0x09 || (it.code in 0x0E..0x1F) } > 8 ->
          "下载内容不是文本字幕（可能是图片字幕或二进制文件）"
        else -> "下载内容不是可识别的字幕"
      }
    }

    private val SRT_TIMECODE =
      Regex("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-+>\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}")
  }
}
