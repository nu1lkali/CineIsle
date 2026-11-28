package app.marlboroadvance.mpvex.domain.subtitle

import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitleInfo
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 字幕删除后的持久「忽略」记录。
 *
 * 删除一条字幕不等于删除它对应的文件：用户媒体目录里的文件必须原样保留，应用私有下载、
 * Emby 缓存与 TSV 清单也只是附属物。真正需要跨进程保留的是“这条字幕已经被用户删过”，
 * 这样下一次扫描、绑定恢复、在线库挂载或 Emby 自动选择才不会把它重新捡回来。
 *
 * 本文件只做键归一、匹配、过滤和序列化，不访问 Android / 文件系统 / SharedPreferences。
 */
object SubtitleTombstones {
  private const val HEADER = "#subtitle-tombstones-v1"
  private const val VERSION = "1"
  private const val FIELD = '\t'

  sealed interface Tombstone {
    val createdAt: Long

    data class External(
      /** 规范化绝对路径。只有 content:// 等无法拿到真实路径时才为 null。 */
      val path: String? = null,
      /** content:// 等稳定来源 URI；绝不保存会复用的 fd://N。 */
      val sourceUri: String? = null,
      override val createdAt: Long = 0L,
    ) : Tombstone

    data class Online(
      val serverKey: String,
      val itemId: String,
      val originId: String? = null,
      val originUri: String? = null,
      /** 私有库绝对路径或旧记录里的相对文件名，仅作稳定 origin 缺失时的回退。 */
      val storagePath: String? = null,
      override val createdAt: Long = 0L,
    ) : Tombstone

    data class Emby(
      val serverId: String,
      val itemId: String,
      val mediaSourceId: String? = null,
      val streamIndex: Int,
      override val createdAt: Long = 0L,
    ) : Tombstone
  }

  data class ExternalKey(val path: String? = null, val sourceUri: String? = null)

  data class OnlineKey(
    val serverKey: String,
    val itemId: String,
    val originId: String? = null,
    val originUri: String? = null,
    val storagePath: String? = null,
  )

  data class EmbyKey(
    val serverId: String,
    val itemId: String,
    val mediaSourceId: String? = null,
    val streamIndex: Int,
  )

  // ──────────────────────────── 键 ────────────────────────────

  fun externalKey(path: String?, sourceUri: String?): ExternalKey? {
    val normalizedPath = normalizeAbsolutePath(path)
    val normalizedUri = normalizeStableUri(sourceUri)
    if (normalizedPath == null && normalizedUri == null) return null
    return ExternalKey(normalizedPath, normalizedUri)
  }

  fun external(path: String?, sourceUri: String?, createdAt: Long = 0L): Tombstone.External? =
    externalKey(path, sourceUri)?.let { Tombstone.External(it.path, it.sourceUri, createdAt) }

  fun onlineKey(
    serverKey: String,
    itemId: String,
    originId: String?,
    originUri: String?,
    storagePath: String?,
  ): OnlineKey? {
    val server = clean(serverKey) ?: return null
    val item = clean(itemId) ?: return null
    val id = clean(originId)
    val uri = clean(originUri)
    val storage = normalizeStoragePath(storagePath)
    // 老记录可能没有 origin；此时仍允许用存储位置精确忽略这一份。
    if (id == null && uri == null && storage == null) return null
    return OnlineKey(server, item, id, uri, storage)
  }

  fun online(
    serverKey: String,
    itemId: String,
    originId: String?,
    originUri: String?,
    storagePath: String?,
    createdAt: Long = 0L,
  ): Tombstone.Online? = onlineKey(serverKey, itemId, originId, originUri, storagePath)?.let {
    Tombstone.Online(it.serverKey, it.itemId, it.originId, it.originUri, it.storagePath, createdAt)
  }

  fun embyKey(
    serverId: String,
    itemId: String,
    mediaSourceId: String?,
    streamIndex: Int,
  ): EmbyKey? {
    val server = clean(serverId) ?: return null
    val item = clean(itemId) ?: return null
    if (streamIndex < 0) return null
    return EmbyKey(server, item, clean(mediaSourceId), streamIndex)
  }

  fun emby(
    serverId: String,
    itemId: String,
    mediaSourceId: String?,
    streamIndex: Int,
    createdAt: Long = 0L,
  ): Tombstone.Emby? = embyKey(serverId, itemId, mediaSourceId, streamIndex)?.let {
    Tombstone.Emby(it.serverId, it.itemId, it.mediaSourceId, it.streamIndex, createdAt)
  }

  fun embyKey(serverId: String, itemId: String, subtitle: SubtitleInfo): EmbyKey? =
    embyKey(serverId, itemId, subtitle.mediaSourceId, subtitle.streamIndex)

  // ──────────────────────────── 匹配 ────────────────────────────

  /** 去重时忽略写入时间；同一条字幕反复删除不会把账本撑大。 */
  fun sameIdentity(a: Tombstone, b: Tombstone): Boolean =
    when {
      a is Tombstone.External && b is Tombstone.External ->
        a.path == b.path && a.sourceUri == b.sourceUri
      a is Tombstone.Online && b is Tombstone.Online ->
        a.serverKey == b.serverKey && a.itemId == b.itemId &&
          a.originId == b.originId && a.originUri == b.originUri && a.storagePath == b.storagePath
      a is Tombstone.Emby && b is Tombstone.Emby ->
        a.serverId == b.serverId && a.itemId == b.itemId &&
          a.mediaSourceId == b.mediaSourceId && a.streamIndex == b.streamIndex
      else -> false
    }

  // ──────────────────────────── 过滤 ────────────────────────────

  fun <T> filterExternal(
    items: Iterable<T>,
    tombstones: List<Tombstone>,
    path: (T) -> String?,
    sourceUri: (T) -> String? = { null },
  ): List<T> {
    val external = tombstones.filterIsInstance<Tombstone.External>()
    if (external.isEmpty()) return items.toList()
    return items.filter { item ->
      external.none { it.matches(path(item), sourceUri(item)) }
    }
  }

  fun <T> filterOnline(
    items: Iterable<T>,
    tombstones: List<Tombstone>,
    key: (T) -> OnlineKey?,
  ): List<T> {
    val online = tombstones.filterIsInstance<Tombstone.Online>()
    if (online.isEmpty()) return items.toList()
    return items.filter { item -> key(item)?.let { candidate -> online.none { it.matches(candidate) } } ?: true }
  }

  fun <T> filterEmby(
    items: Iterable<T>,
    tombstones: List<Tombstone>,
    key: (T) -> EmbyKey?,
  ): List<T> {
    val emby = tombstones.filterIsInstance<Tombstone.Emby>()
    if (emby.isEmpty()) return items.toList()
    return items.filter { item -> key(item)?.let { candidate -> emby.none { it.matches(candidate) } } ?: true }
  }

  // ──────────────────────────── 序列化 ────────────────────────────

  /** 一行一条；字段做 URL 编码，URI 里的制表符 / 换行不会破坏账本。 */
  fun serialize(tombstones: List<Tombstone>): String =
    buildString {
      append(HEADER).append('\n')
      tombstones.distinct().forEach { tombstone ->
        append(FIELD).append(VERSION).append(FIELD)
        when (tombstone) {
          is Tombstone.External -> {
            append('E').append(FIELD).append(tombstone.createdAt).append(FIELD)
            append(encode(tombstone.path.orEmpty())).append(FIELD)
            append(encode(tombstone.sourceUri.orEmpty()))
          }
          is Tombstone.Online -> {
            append('O').append(FIELD).append(tombstone.createdAt).append(FIELD)
            append(encode(tombstone.serverKey)).append(FIELD)
            append(encode(tombstone.itemId)).append(FIELD)
            append(encode(tombstone.originId.orEmpty())).append(FIELD)
            append(encode(tombstone.originUri.orEmpty())).append(FIELD)
            append(encode(tombstone.storagePath.orEmpty()))
          }
          is Tombstone.Emby -> {
            append('B').append(FIELD).append(tombstone.createdAt).append(FIELD)
            append(encode(tombstone.serverId)).append(FIELD)
            append(encode(tombstone.itemId)).append(FIELD)
            append(encode(tombstone.mediaSourceId.orEmpty())).append(FIELD)
            append(tombstone.streamIndex)
          }
        }
        append('\n')
      }
    }

  /** 损坏的旧行单独丢弃，不能让一条坏记录使整份忽略账本失效。 */
  fun parse(raw: String): List<Tombstone> {
    if (raw.isBlank()) return emptyList()
    val out = ArrayList<Tombstone>()
    raw.lineSequence().forEach { line ->
      if (line.isBlank() || line.startsWith("#")) return@forEach
      val parts = line.split(FIELD)
      if (parts.size < 4 || parts[0].isNotEmpty() || parts[1] != VERSION) return@forEach
      val createdAt = parts[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
      when (parts[2]) {
        "E" -> {
          if (parts.size != 6) return@forEach
          external(decode(parts[4]), decode(parts[5]), createdAt)?.let(out::add)
        }
        "O" -> {
          if (parts.size != 9) return@forEach
          online(
            serverKey = decode(parts[4]),
            itemId = decode(parts[5]),
            originId = decode(parts[6]),
            originUri = decode(parts[7]),
            storagePath = decode(parts[8]),
            createdAt = createdAt,
          )?.let(out::add)
        }
        "B" -> {
          if (parts.size != 8) return@forEach
          emby(
            serverId = decode(parts[4]),
            itemId = decode(parts[5]),
            mediaSourceId = decode(parts[6]),
            streamIndex = parts[7].toIntOrNull() ?: return@forEach,
            createdAt = createdAt,
          )?.let(out::add)
        }
      }
    }
    return out.distinct()
  }

  // ──────────────────────────── 路径 / URI ────────────────────────────

  /**
   * 规范化绝对路径。只做词法处理，不判断文件是否存在，也不碰磁盘。
   *
   * `fd://N` 是 SAF 打开文件后临时分配的描述符，重启后会指向别的东西，必须拒绝；
   * content:// 的稳定 URI 由 [normalizeStableUri] 单独保存。
   */
  fun normalizeAbsolutePath(raw: String?): String? = normalizePath(raw, allowRelative = false)

  private fun normalizeStoragePath(raw: String?): String? = normalizePath(raw, allowRelative = true)

  private fun normalizePath(raw: String?, allowRelative: Boolean): String? {
    val value = clean(raw) ?: return null
    val lower = value.lowercase()
    if (lower.startsWith("fd:") || lower.startsWith("content:")) return null
    val path =
      when {
        lower.startsWith("file://") -> value.substring("file://".length)
        lower.startsWith("file:/") -> value.substring("file:".length)
        value.contains("://") -> return null
        else -> value
      }.substringBefore('?').substringBefore('#')
    val decoded = decodePercent(path)
    val slashed = decoded.replace('\\', '/')
    val drive = Regex("^[A-Za-z]:/").find(slashed)?.value
    val absolute = slashed.startsWith('/') || drive != null
    if (!allowRelative && !absolute) return null
    val body = if (drive != null) slashed.removePrefix(drive) else slashed.removePrefix("/")
    val segments = ArrayList<String>()
    body.split('/').forEach { segment ->
      when (segment) {
        "", "." -> Unit
        ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
        else -> segments += segment
      }
    }
    if (drive != null) return drive + segments.joinToString("/")
    if (!absolute) return segments.joinToString("/").takeIf { it.isNotBlank() }
    return "/" + segments.joinToString("/")
  }

  /** 只保留可跨进程定位来源的 URI；file:// 已归一到路径，fd:// 直接丢弃。 */
  fun normalizeStableUri(raw: String?): String? {
    val value = clean(raw) ?: return null
    val scheme = value.substringBefore(':', "").lowercase()
    if (scheme == "fd" || scheme == "file" || scheme.isBlank()) return null
    if (scheme != "content" && scheme != "http" && scheme != "https") return null
    return value
  }

  private fun clean(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

  private fun decodePercent(value: String): String {
    if ('%' !in value) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
      if (value[i] == '%' && i + 2 < value.length) {
        val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
        if (hex != null) {
          out.append(hex.toChar())
          i += 3
          continue
        }
      }
      out.append(value[i])
      i++
    }
    return out.toString()
  }

  private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

  private fun decode(value: String): String = runCatching {
    URLDecoder.decode(value, Charsets.UTF_8.name())
  }.getOrDefault(value)
}

fun SubtitleTombstones.Tombstone.External.matches(path: String?, sourceUri: String?): Boolean {
  val candidatePath = SubtitleTombstones.normalizeAbsolutePath(path)
  val candidateUri = SubtitleTombstones.normalizeStableUri(sourceUri)
  return (this.path != null && this.path == candidatePath) ||
    (this.sourceUri != null && this.sourceUri == candidateUri)
}

fun SubtitleTombstones.Tombstone.Online.matches(key: SubtitleTombstones.OnlineKey): Boolean {
  if (serverKey != key.serverKey || itemId != key.itemId) return false
  val hasComparableOrigin =
    (originId != null && key.originId != null) || (originUri != null && key.originUri != null)
  if (hasComparableOrigin) {
    // originId 优先；URI 用于只有 URI 的旧记录。两条稳定身份任一相同即同一字幕。
    // 两者都可比但不同时不看存储路径，避免“两条字幕共用一个文件”误伤另一条。
    return (originId != null && originId == key.originId) ||
      (originUri != null && originUri == key.originUri)
  }
  val tombstoneHasOrigin = originId != null || originUri != null
  val candidateHasOrigin = key.originId != null || key.originUri != null
  if (tombstoneHasOrigin && candidateHasOrigin) return false
  return storagePath != null && storagePath == key.storagePath
}

fun SubtitleTombstones.Tombstone.Emby.matches(key: SubtitleTombstones.EmbyKey): Boolean =
  serverId == key.serverId &&
    itemId == key.itemId &&
    mediaSourceId == key.mediaSourceId &&
    streamIndex == key.streamIndex
