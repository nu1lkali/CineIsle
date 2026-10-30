package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.album

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册里的一个媒体条目（图片或视频）。
 *
 * @param uri MediaStore 条目地址（`content://media/external/images/media/123`）。
 *   即使 [file] 为 null 也能用它流式读取 —— 见 [UploadPick][app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload.UploadPick]。
 * @param file `_data` 列指向的真实文件。**可能为 null**：Android 10+ 的 scoped storage 下
 *   个别 ROM 会对自身以外写入的条目返回空，或者文件已被删除但索引还没清。
 * @param bucketId 相册（目录）id。同 id 即同一个相册。
 */
data class AlbumItem(
  val id: Long,
  val uri: Uri,
  val name: String,
  val sizeBytes: Long,
  val dateAdded: Long,
  val isVideo: Boolean,
  val durationMs: Long,
  val bucketId: Long,
  val bucketName: String,
  val file: File?,
) {
  /**
   * 跨图片/视频唯一的键。
   *
   * ⚠️ **不能用裸 [id] 当 LazyGrid 的 key**：`images` 和 `video` 是两张表，
   * 各自的 `_id` 都从 1 开始自增，同一个相册里「第 3 张照片」和「第 3 个视频」
   * 都会拿到 id=3 —— 直接当 key 会让 LazyGrid 抛 "Key was already used"。
   */
  val key: String
    get() = if (isVideo) "v$id" else "i$id"
}

/** 一个相册（= 一个目录）。[cover] 取该相册里最新的一张 */
data class AlbumBucket(val id: Long, val name: String, val cover: AlbumItem, val count: Int)

/**
 * 一次加载出来的整个相册库。
 *
 * @param loadedAt 加载完成的时间戳。**只用来当 Compose 的 remember key** ——
 *   直接拿 [items] 当 key 会让每次重组都深比较六千个条目。
 * @param hasMore 库里是否还有更早的条目没读（v181：超出首批上限时给「加载更多」用）
 */
data class AlbumLibrary(
  val items: List<AlbumItem>,
  val buckets: List<AlbumBucket>,
  val loadedAt: Long = 0L,
  val hasMore: Boolean = false,
)

/** 按名字搜索的结果。[truncated] = 撞上条数上限，只给了前一部分 */
data class AlbumSearchResult(
  val items: List<AlbumItem>,
  val truncated: Boolean = false,
)

/**
 * 本机媒体库（MediaStore）读取。
 *
 * ## 为什么不用官方的嵌入式 PhotoPicker
 *
 * 1. `androidx.photopicker:photopicker-compose` 到 2026-10 还是 `1.0.0-alpha03`，
 *    API 未定版，且它自带一整套 UI（含自己的确认条）—— 而这里要和「文件 / 文件夹」
 *    共用同一条底部上传栏、同一份选择状态。
 * 2. 它依赖系统 MediaProvider 的 photopicker 能力：Android 13+ 原生有，
 *    更老的靠 GMS backport。国产 ROM / 无 GMS 设备上不一定可用，
 *    而这个项目是自发布 APK，不能赌设备。
 * 3. 视觉不可定制，和这个 App 的 Material3 主题 + Cine 玻璃风格对不上。
 *
 * 直接查 MediaStore 是三条路径里唯一在 Android 8 ~ 16 上表现完全一致的。
 *
 * ## 权限
 *
 * 应用已持有 `MANAGE_EXTERNAL_STORAGE`（同文件选择器），MediaStore 全量可见，
 * 不需要 `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`。没有该权限时这里只会返回空 ——
 * 调用方（选择器面板）自己负责先过权限闸门。
 */
object AlbumMediaSource {
  private const val TAG = "AlbumMediaSource"

  /**
   * 首批最多读取多少条。
   *
   * 6000 是「够用」与「查询/内存成本」的折中：一个手机相册通常 1000~5000 张，
   * 上万张的设备上少显示最旧的那部分 —— 传文件时几乎不会去挑三年前的照片，
   * 而全量查询会让面板打开前多等好几秒。
   *
   * ⚠️ **但首批上限不等于「看不到」**（v181 修）：
   * - 往下翻有「加载更多」（[loadMore]，按 `date_added` 键集分页，能一直往前翻）；
   * - 直接找有按名字搜索（[searchByName]，**直查 MediaStore，不受这个上限约束**）。
   */
  const val MAX_ITEMS = 6000

  /** 「加载更多」一次追加多少条。2000 ≈ 屏幕上滚七八屏，够用又不会一次卡住 */
  const val PAGE_SIZE = 2000

  /** 按名字搜索最多返回多少条（同样会翻页式追加，见 [searchByName] 的 limit） */
  const val SEARCH_LIMIT = 2000

  // 这些列名在 API 26~36 之间有过归属变化（bucket_* 从 ImageColumns/VideoColumns
  // 挪到了 MediaColumns，_data 在 29 被 deprecated 但仍是真实列），
  // 统一用字面量，避免跟着 compileSdk 走。
  private const val COL_ID = "_id"
  private const val COL_NAME = "_display_name"
  private const val COL_SIZE = "_size"
  private const val COL_DATE = "date_added"
  private const val COL_DATA = "_data"
  private const val COL_DURATION = "duration"
  private const val COL_BUCKET_ID = "bucket_id"
  private const val COL_BUCKET_NAME = "bucket_display_name"

  /** 名字都没有的相册（极少数录音、截图目录）统一归到这一档 */
  private const val FALLBACK_BUCKET_NAME = "其他"

  /** 相册结果复用窗口。面板开关很频繁，但刚拍的照片必须能很快出现 */
  private const val CACHE_TTL_MS = 30_000L

  @Volatile private var cached: AlbumLibrary? = null

  /** 一次读完全部图片 + 视频，按时间倒序 */
  suspend fun load(context: Context): AlbumLibrary {
    cached?.let { if (System.currentTimeMillis() - it.loadedAt < CACHE_TTL_MS) return it }
    val loaded =
      withContext(Dispatchers.IO) {
        val app = context.applicationContext
        // 两张表各多取一条：用来判断「还有更早的没读」（撞上限 ⇒ 一定还有）
        val raw =
          buildList {
              addAll(query(app, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false))
              addAll(query(app, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true))
            }
            .sortedByDescending { it.dateAdded }
        val items = raw.take(MAX_ITEMS)
        AlbumLibrary(
          items = items,
          buckets = bucketsOf(items),
          loadedAt = System.currentTimeMillis(),
          hasMore = raw.size > items.size,
        )
      }
    cached = loaded
    return loaded
  }

  /**
   * 「加载更多」：读严格早于 [beforeDateAdded] 的下一段（**键集分页**，不是 offset）。
   *
   * ## 为什么用 `date_added < 游标` 而不是 `LIMIT/OFFSET`
   *
   * 图片与视频是**两张表**，各自的 offset 对不上统一的「第 6000 条」：
   * offset 分页还得记住两张表各自消费了多少，中途有新增条目就会漏读或重读。
   * 键集只需一个游标（当前最旧一条的时间），两表各查一段再归并即可 ——
   * 重读由调用方按 [AlbumItem.key] 去重（同一时间戳的相邻条目会被重复查出）。
   *
   * @param beforeDateAdded 当前已有的最旧一条的 `date_added`（秒）
   */
  suspend fun loadMore(context: Context, beforeDateAdded: Long): List<AlbumItem> =
    withContext(Dispatchers.IO) {
      val app = context.applicationContext
      val selection = "$COL_DATE < ?"
      val args = arrayOf(beforeDateAdded.toString())
      buildList {
          addAll(
            query(
              context = app,
              base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
              isVideo = false,
              selection = selection,
              selectionArgs = args,
              limit = PAGE_SIZE,
            ),
          )
          addAll(
            query(
              context = app,
              base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
              isVideo = true,
              selection = selection,
              selectionArgs = args,
              limit = PAGE_SIZE,
            ),
          )
        }
        .sortedByDescending { it.dateAdded }
        .take(PAGE_SIZE)
    }

  /**
   * 按**文件名**搜索，**直查 MediaStore** —— 这是「找第 6000 条之外的内容」的正解。
   *
   * 为什么不只是过滤 [load] 那 6000 条：那 6000 条是按时间取的最新一段，
   * 三年前拍的那张截图根本不在里面，本地过滤永远搜不到。`_display_name LIKE` 由
   * MediaProvider 在数据库层完成，不受任何客户端上限影响。
   *
   * ⚠️ 上限 [limit] 仍然存在（一次拉几万条没有意义），但**超了会明确回报**
   * [AlbumSearchResult.truncated]，UI 提示「换个更精确的关键词」而不是假装搜完了。
   *
   * @param query 关键词；`%` / `_` 会被剥掉（它们在 LIKE 里是通配符，用户不会想用）
   */
  suspend fun searchByName(
    context: Context,
    query: String,
    limit: Int = SEARCH_LIMIT,
  ): AlbumSearchResult =
    withContext(Dispatchers.IO) {
      val needle = query.trim().replace("%", "").replace("_", "")
      if (needle.isEmpty()) return@withContext AlbumSearchResult(emptyList())
      val app = context.applicationContext
      val selection = "$COL_NAME LIKE ?"
      val args = arrayOf("%$needle%")
      val raw =
        buildList {
            addAll(
              this@AlbumMediaSource.query(
                context = app,
                base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                isVideo = false,
                selection = selection,
                selectionArgs = args,
                // 多要一条用于判「还有」
                limit = limit + 1,
              ),
            )
            addAll(
              this@AlbumMediaSource.query(
                context = app,
                base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                isVideo = true,
                selection = selection,
                selectionArgs = args,
                limit = limit + 1,
              ),
            )
          }
          .sortedByDescending { it.dateAdded }
      AlbumSearchResult(
        items = raw.take(limit),
        truncated = raw.size > limit,
      )
    }

  /** 把「加载更多」/ 搜索之后的列表并回缓存，避免面板重开又退回首批 6000 条 */
  fun rememberLoaded(items: List<AlbumItem>, hasMore: Boolean) {
    val current = cached ?: return
    cached =
      current.copy(
        items = items,
        buckets = bucketsOf(items),
        hasMore = hasMore,
        loadedAt = System.currentTimeMillis(),
      )
  }

  // ── 最近使用（进程内，最多 [RECENT_ALBUMS] 个）────────────────────────────
  //
  // 「最近用过的」和「最近有新照片的」是两件事：前者是你主动挑过的，
  // 后者只是时间顺序。相册几十个时，前者才是真正省事的那个快捷入口
  // （Google 相册 / 三星相册的相册页顶部都有这一档）。
  //
  // 只记进程内存：一次上传会话里够用；落盘反而会出现「半年前挑过一次的相册
  // 永远挂在最上面」这种尴尬。

  private const val RECENT_ALBUMS = 3

  private val recentIds = ArrayList<Long>(RECENT_ALBUMS)

  /** 记一次「用户挑了这个相册」。重复挑会把它提到最前 */
  @Synchronized
  fun rememberAlbumUsed(bucketId: Long) {
    if (bucketId <= 0L) return
    recentIds.remove(bucketId)
    recentIds.add(0, bucketId)
    while (recentIds.size > RECENT_ALBUMS) recentIds.removeAt(recentIds.lastIndex)
  }

  /** 最近用过的相册 id，最近的在前 */
  @Synchronized
  fun recentAlbumIds(): List<Long> = recentIds.toList()

  @Suppress("LongParameterList")
  private fun query(
    context: Context,
    base: Uri,
    isVideo: Boolean,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    limit: Int = MAX_ITEMS,
  ): List<AlbumItem> {
    val projection =
      buildList {
          add(COL_ID)
          add(COL_NAME)
          add(COL_SIZE)
          add(COL_DATE)
          add(COL_BUCKET_ID)
          add(COL_BUCKET_NAME)
          add(COL_DATA)
          // ⚠️ images 表没有 duration 列，一起查会整条 query 失败
          if (isVideo) add(COL_DURATION)
        }
        .toTypedArray()

    // QUERY_ARG_LIMIT 从 API 26 起可用（minSdk 正好 26）。它在个别 ROM 上会被忽略，
    // 所以调用方还会再 take 一次 —— 这里只是尽量少让 Cursor 走完整张表。
    val args =
      Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "$COL_DATE DESC")
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        // 条件也走 Bundle（API 26+），免得依赖已废弃的三参重载
        if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        if (selectionArgs != null) {
          putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        }
      }

    val out = ArrayList<AlbumItem>(512)
    runCatching {
        context.contentResolver.query(base, projection, args, null)?.use { cursor ->
          val idIdx = cursor.getColumnIndex(COL_ID)
          val nameIdx = cursor.getColumnIndex(COL_NAME)
          val sizeIdx = cursor.getColumnIndex(COL_SIZE)
          val dateIdx = cursor.getColumnIndex(COL_DATE)
          val bucketIdIdx = cursor.getColumnIndex(COL_BUCKET_ID)
          val bucketNameIdx = cursor.getColumnIndex(COL_BUCKET_NAME)
          val dataIdx = cursor.getColumnIndex(COL_DATA)
          val durationIdx = if (isVideo) cursor.getColumnIndex(COL_DURATION) else -1
          if (idIdx < 0) return@use

          while (cursor.moveToNext()) {
            val id = cursor.getLong(idIdx)
            val name =
              nameIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }?.takeIf { it.isNotBlank() }
                ?: continue
            val bucketId = bucketIdIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L
            val bucketName =
              bucketNameIdx
                .takeIf { it >= 0 }
                ?.let { cursor.getString(it) }
                ?.takeIf { it.isNotBlank() }
                ?: FALLBACK_BUCKET_NAME
            // _data 可能为空 / 指向已删除的文件：只当作「没有快速通道」，
            // 条目照样保留，上传时走 content URI 流式读。
            val file =
              dataIdx
                .takeIf { it >= 0 }
                ?.let { cursor.getString(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.isFile && it.canRead() }

            out.add(
              AlbumItem(
                id = id,
                uri = ContentUris.withAppendedId(base, id),
                name = name,
                sizeBytes = sizeIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                dateAdded = dateIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                isVideo = isVideo,
                durationMs = durationIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                bucketId = bucketId,
                bucketName = bucketName,
                file = file,
              ),
            )
          }
        }
      }
      .onFailure { Log.w(TAG, "读取媒体库失败: $base", it) }
    return out
  }

  /**
   * 按 bucket 分组。
   *
   * 入参已按时间倒序，所以 LinkedHashMap 的插入顺序天然就是
   * 「相册按各自最新一张的时间倒序」—— 和系统相册 App 的顺序一致。
   */
  fun bucketsOf(items: List<AlbumItem>): List<AlbumBucket> {
    val grouped = LinkedHashMap<Long, MutableList<AlbumItem>>()
    items.forEach { grouped.getOrPut(it.bucketId) { mutableListOf() }.add(it) }
    return grouped.map { (id, list) ->
      val head = list.first()
      // 同一个 bucketId 下名字理应一致；不一致时以最新那条为准（改名后的表现）。
      AlbumBucket(id = id, name = head.bucketName, cover = head, count = list.size)
    }
  }
}
