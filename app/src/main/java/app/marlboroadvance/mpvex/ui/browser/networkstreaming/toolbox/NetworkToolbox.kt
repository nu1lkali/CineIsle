package app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox

import app.marlboroadvance.mpvex.domain.network.NETWORK_IMAGE_EXTENSIONS
import app.marlboroadvance.mpvex.domain.network.NETWORK_VIDEO_EXTENSIONS
import app.marlboroadvance.mpvex.domain.network.fileExtension
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网络位置「工具箱」的工具清单。
 *
 * 前三项照搬 AlistClientN（`lib/screen/file_list/file_list_screen.dart` 的
 * `_organizeByType` / `_extractAndOrganize` / `_deleteEmptyFolders`），
 * 后两项是这个 App 自己补的：空文件在网盘上几乎全是「下载中断 / 上传失败」留下的壳，
 * 而按月份归档是整理相册 / 追番目录时最常用的一刀。
 *
 * ## 为什么全部只作用在「AList」
 *
 * 这几件事都要**移动**或**删除**：移动要 `fs/move`，删除要 `fs/remove`，建目录要
 * `fs/mkdir` —— 只有 AList 有。SMB / FTP / WebDAV 三个客户端本来就没有写接口
 * （见 `NetworkClient.supportsWrite`），所以入口只在 AList 的连接上出现。
 *
 * ## 全部都是「先出清单、再动手」
 *
 * 每一项都会先扫一遍目录、把「要动哪些文件、落到哪个文件夹」摆给用户看，
 * 确认之后才真正写服务器。AList 没有回收站，误操作不可撤销 —— 清单这一步不是装饰。
 */
enum class NetworkTool(
  /** 卡片标题 */
  val title: String,
  /** 卡片副标题（一句话说清副作用） */
  val description: String,
  /** 会删东西的工具：确认框用错误色，文案更重 */
  val destructive: Boolean,
) {
  ORGANIZE_BY_TYPE(
    title = "按类型归类",
    description = "当前目录里的文件移进 图片 / 视频 / 音频 / 文档 四个子文件夹，认不出类型的留在原处",
    destructive = false,
  ),
  EXTRACT_AND_ORGANIZE(
    title = "提取并整理",
    description = "把子文件夹里的文件全部提到当前目录、按类型归档（含「其他」），随后删除空文件夹",
    destructive = true,
  ),
  CLEAN_EMPTY_DIRS(
    title = "清理空目录",
    description = "递归删除当前目录下所有空文件夹（套着空文件夹的也会一起清）",
    destructive = true,
  ),
  CLEAN_EMPTY_FILES(
    title = "清理空文件",
    description = "递归删除 0 字节的文件，多是下载中断 / 上传失败留下的空壳（跳过隐藏文件）",
    destructive = true,
  ),
  CLEAN_JUNK_FILES(
    title = "清理垃圾文件",
    description = "递归删除系统 / 同步残留：Thumbs.db、.DS_Store、._* 资源叉、@eaDir 缩略图目录等",
    destructive = true,
  ),
  ARCHIVE_BY_MONTH(
    title = "按月份归档",
    description = "当前目录里的文件按修改时间移进「2026-10」这样的月份文件夹",
    destructive = false,
  ),
}

/** 归类目标文件夹名。前四类与 AlistClientN 完全一致，`其他` 只给「提取并整理」用 */
const val TOOLBOX_CAT_PHOTO = "图片"
const val TOOLBOX_CAT_VIDEO = "视频"
const val TOOLBOX_CAT_AUDIO = "音频"
const val TOOLBOX_CAT_DOCUMENT = "文档"
const val TOOLBOX_CAT_OTHER = "其他"

/**
 * 音频扩展名。
 *
 * ⚠️ 刻意**不**并进 `NetworkFileType`：那个枚举决定卡片角标与网格分流，
 * 多一个 AUDIO 会牵动播放页、筛选、封面等一串既有逻辑。工具箱只关心「移到哪个文件夹」，
 * 自己维护一张表就够，坏了也只影响这一个工具。
 */
private val TOOLBOX_AUDIO_EXTENSIONS: Set<String> =
  setOf(
    "mp3", "m4a", "aac", "flac", "wav", "wma", "ogg", "oga", "opus", "ape",
    "alac", "aiff", "aif", "dsf", "dff", "mka", "m4b",
  )

/** 文档扩展名。除常见办公格式，把字幕与 nfo 也算进来（整理影片目录时它们跟着片子走） */
private val TOOLBOX_DOCUMENT_EXTENSIONS: Set<String> =
  setOf(
    "pdf", "epub", "mobi", "azw", "azw3", "txt", "md", "rtf", "csv",
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
    "srt", "ass", "ssa", "vtt", "sub", "nfo",
  )

/**
 * 按文件名判归类目录；认不出返回 null（「按类型归类」会把它留在原处）。
 *
 * 只认扩展名，与 [app.marlboroadvance.mpvex.domain.network.type] 同一套口径 ——
 * mimeType 由各协议客户端猜，服务端不给 Content-Type 时就空着，不可靠。
 */
fun toolboxCategoryOf(fileName: String): String? =
  when (fileName.fileExtension()) {
    in NETWORK_IMAGE_EXTENSIONS -> TOOLBOX_CAT_PHOTO
    in NETWORK_VIDEO_EXTENSIONS -> TOOLBOX_CAT_VIDEO
    in TOOLBOX_AUDIO_EXTENSIONS -> TOOLBOX_CAT_AUDIO
    in TOOLBOX_DOCUMENT_EXTENSIONS -> TOOLBOX_CAT_DOCUMENT
    else -> null
  }

/** `dir` + `name`，`dir` 为空（连接根目录）时只留名字 —— 与 [NetworkFile.path] 同一口径 */
fun toolboxJoinPath(dir: String, name: String): String {
  val left = dir.trim('/')
  return if (left.isEmpty()) name else "$left/$name"
}

/** 父目录；`a/b/c` → `a/b`，`c` → 空串（根目录） */
fun toolboxParentOf(path: String): String = path.trim('/').substringBeforeLast('/', "")

/** 最后一段名字 */
fun toolboxNameOf(path: String): String = path.trim('/').substringAfterLast('/')

/** 展开「文件名冲突时加 (1)」的候选名，与 AlistClientN 的 `_generateUniqueFileName` 同一规则 */
fun toolboxUniqueName(originalName: String, existing: Set<String>): String {
  if (originalName !in existing) return originalName
  val dot = originalName.lastIndexOf('.')
  val (base, ext) =
    if (dot > 0 && dot < originalName.length - 1) {
      originalName.substring(0, dot) to originalName.substring(dot)
    } else {
      originalName to ""
    }
  var counter = 1
  while (true) {
    val candidate = "$base($counter)$ext"
    if (candidate !in existing) return candidate
    counter++
  }
}

/** 修改时间 → 「2026-10」；时间戳缺失（0）归到「未知日期」，不静默丢掉文件 */
fun toolboxMonthFolder(lastModified: Long): String =
  if (lastModified <= 0L) {
    "未知日期"
  } else {
    SimpleDateFormat("yyyy-MM", Locale.US).format(Date(lastModified))
  }

/**
 * 垃圾**文件**名单（小写比较），按来源分三组 —— 分组不是为了执行，是为了让确认清单
 * 能说清「删的是什么东西」，而不是甩一个总数让人猜。
 *
 * - **Windows 缓存**：`Thumbs.db` / `ehthumbs*.db` 是资源管理器缩略图库，
 *   `desktop.ini` 是文件夹显示属性；进网络共享就会跟着漂过来。
 * - **macOS 标记**：`.DS_Store` 存图标位置，`._*` 是给每个文件配的资源叉
 *   （`._影片.mkv`，内容只有 Finder 元信息），`.localized` / `.apdisk` /
 *   `.VolumeIcon.icns` 同理。
 * - **临时残留**：`.fuse_hidden*` 是 FUSE 卸载不干净留下的副本，往往还占着真实空间。
 *
 * ⚠️ **刻意不收** `.part` / `.crdownload` / `.aria2` / `.!ut`：这些是断点续传的现场，
 * 删掉等于把「还能接着下」的那一半废掉。清理工具最忌讳把可恢复的东西当垃圾。
 */
private val TOOLBOX_JUNK_WINDOWS: Set<String> =
  setOf("thumbs.db", "ehthumbs.db", "ehthumbs_vista.db", "desktop.ini")

private val TOOLBOX_JUNK_MACOS: Set<String> =
  setOf(".ds_store", ".localized", ".apdisk", ".volumeicon.icns")

private const val TOOLBOX_JUNK_LABEL_WINDOWS = "Windows 缓存"
private const val TOOLBOX_JUNK_LABEL_MACOS = "macOS 标记"
private const val TOOLBOX_JUNK_LABEL_TEMP = "临时残留"

/**
 * 垃圾**目录**名单（小写）。这些目录里动辄几百上千个小文件，逐个删既慢又没意义，
 * 直接整目录删。
 *
 * ⚠️ **刻意不收** `#recycle` / `.recycle` / `.Trashes`：那是「回收站」本身，
 * 里面是用户以为还能恢复的东西 —— 从清理工具里删掉，比不清理恶劣得多。
 */
private val TOOLBOX_JUNK_DIRS: Set<String> =
  setOf(
    "@eadir", // Synology 缩略图 / 索引
    ".@__thumb", // QNAP 缩略图
    ".appledouble", // SMB 上的资源叉目录
    ".spotlight-v100", // macOS 索引
    ".fseventsd", // macOS 文件事件
    ".temporaryitems", // macOS 临时目录
  )

/**
 * 垃圾文件的归类标签；不是垃圾返回 null。
 *
 * 「是不是垃圾」和「是哪一类垃圾」用同一个函数判，避免两处名单走偏
 * （一处加了、另一处忘了 ⇒ 清单里出现「共 12 个」但只列出 10 个的怪账）。
 */
fun toolboxJunkLabel(fileName: String): String? {
  val lower = fileName.lowercase()
  return when {
    lower in TOOLBOX_JUNK_WINDOWS -> TOOLBOX_JUNK_LABEL_WINDOWS
    lower in TOOLBOX_JUNK_MACOS || lower.startsWith("._") -> TOOLBOX_JUNK_LABEL_MACOS
    lower.startsWith(".fuse_hidden") -> TOOLBOX_JUNK_LABEL_TEMP
    else -> null
  }
}

/** 是不是垃圾文件（按文件名，忽略大小写） */
fun toolboxIsJunkFile(fileName: String): Boolean = toolboxJunkLabel(fileName) != null

/** 是不是垃圾目录（按目录名，忽略大小写） */
fun toolboxIsJunkDir(dirName: String): Boolean = dirName.lowercase() in TOOLBOX_JUNK_DIRS

/** 一步动作。执行器只认这四种 —— 顺序即执行顺序 */
sealed interface ToolAction {
  /** 列表里那一行的描述（进度页显示「正在…」） */
  val label: String

  /** 建目录。已存在算成功（服务端可能回 409，见 [NetworkToolboxExecutor]） */
  data class Mkdir(val path: String) : ToolAction {
    override val label: String get() = "新建文件夹 $path"
  }

  /** 同目录内移动一个文件 */
  data class Move(val srcDir: String, val name: String, val dstDir: String) : ToolAction {
    override val label: String get() = "$name → $dstDir"
  }

  /** 删除一个文件（`fs/remove`） */
  data class DeleteFile(val dir: String, val name: String) : ToolAction {
    override val label: String get() = "删除文件 $name"
  }

  /** 删除一个空文件夹（`fs/remove`，与删文件同一个接口） */
  data class DeleteDir(val dir: String, val name: String) : ToolAction {
    override val label: String get() = "删除空目录 $name"
  }

  /**
   * 删除一棵**非空**目录树（`fs/remove` 对目录是递归的，与删空目录同一个接口）。
   *
   * 单独立一个类型不是为了执行路径不同，而是为了在确认清单和进度里说清楚
   * 「这里面是有东西的」—— 「清理垃圾文件」撞上 `@eaDir` 时不能让人误以为
   * 只删了一个空壳。
   */
  data class DeleteTree(val dir: String, val name: String) : ToolAction {
    override val label: String get() = "删除目录 $name（含内容）"
  }
}

/**
 * 一次整理的完整计划 —— **扫描阶段的产物**，执行前给用户过目。
 *
 * @param root 当前目录（内部相对路径，无前导斜杠；根目录是空串）
 * @param actions 按顺序执行的动作（建目录 → 移动 → 删除）
 * @param summary 给确认框显示的几行摘要
 * @param truncated 扫到上限提前收手了：只处理已扫到的部分
 * @param sweepEmptyDirs 执行完再扫一遍空目录并删掉（「提取并整理」用 ——
 *   文件搬走之后子文件夹才变空，扫描那一刻是判不出来的）
 */
data class ToolPlan(
  val tool: NetworkTool,
  val root: String,
  val actions: List<ToolAction>,
  val summary: List<String>,
  val truncated: Boolean = false,
  val sweepEmptyDirs: Boolean = false,
) {
  val mkdirCount: Int get() = actions.count { it is ToolAction.Mkdir }
  val moveCount: Int get() = actions.count { it is ToolAction.Move }
  val deleteCount: Int
    get() = actions.count { it is ToolAction.DeleteFile || it is ToolAction.DeleteDir || it is ToolAction.DeleteTree }

  val isEmpty: Boolean get() = mkdirCount == 0 && moveCount == 0 && deleteCount == 0
}

/** 执行结果。UI 拿它拼最后那句「完成：移动 12 个，失败 1 个」 */
data class ToolResult(
  val succeeded: Int,
  val failed: Int,
  val firstError: String?,
  /** 「提取并整理」收尾时顺带删掉的空目录数 */
  val sweptDirs: Int = 0,
)

/**
 * 工具箱的实时状态。
 *
 * 整个对象只有一个来源（[app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkBrowserViewModel]），
 * 面板订阅它画进度条，**面板关掉也照样在跑** —— 状态挂在 ViewModel 上，
 * 不是挂在那一屏的 `remember` 上。
 */
data class ToolProgress(
  val tool: NetworkTool,
  /** 阶段文案：「准备目标文件夹…」「正在移动文件…」「清理空目录…」 */
  val phase: String,
  val done: Int,
  val total: Int,
  val current: String = "",
  val failed: Int = 0,
  /** 非 null 表示已经结束（成功或失败），UI 切成结果态 */
  val result: ToolResult? = null,
  val errorMessage: String? = null,
  val cancelled: Boolean = false,
) {
  val fraction: Float
    get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}
