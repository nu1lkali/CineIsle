package app.marlboroadvance.mpvex.domain.network

import app.marlboroadvance.mpvex.utils.sort.SortUtils

/**
 * 文件夹封面的**来源**（决定用哪条渲染链路，也用于日志定位）。
 *
 * 两种来源的取值形态不同，这是「封面引用」被设计成一个字符串的原因：
 * - [VIDEO_THUMB]：AList `fs/list` 返回的 `thumb`，[NetworkFile.thumb] 已归一化成
 *   **绝对 URL**（`http(s)://host/d/...?sign=...`）⇒ 直接当图片地址加载；
 * - [IMAGE]：图片文件在挂载点内的**相对 path**（如 `电影/海报.jpg`）⇒ 交给客户端
 *   取字节再解码。
 *
 * 两者的字符串**天然不相交**（相对 path 不可能以 `http://` 开头），所以缓存里
 * 用一个 TEXT 列装得下，不必为此加列、加迁移 —— 判据见 [isRemoteCoverRef]。
 */
enum class FolderCoverKind {
  /** 视频的服务端海报帧（AList `thumb`） */
  VIDEO_THUMB,

  /** 图片本身（取原图字节，客户端采样解码） */
  IMAGE,
  ;
}

/**
 * 一个文件夹的封面选择结果。
 *
 * @param ref 封面引用：绝对 URL（[FolderCoverKind.VIDEO_THUMB]）或相对 path（[FolderCoverKind.IMAGE]）
 * @param kind 来源，只用于日志与「为什么这张封面长这样」的追溯，渲染侧按 [ref] 的
 *   scheme 分流，不依赖它（缓存里没地方存 kind，见 [FolderCoverKind] 的说明）
 */
data class FolderCoverPick(
  val ref: String,
  val kind: FolderCoverKind,
)

/** 封面引用是不是「可直接当图片 URL 加载」的服务端缩略图 */
fun isRemoteCoverRef(ref: String): Boolean =
  ref.startsWith("http://", ignoreCase = true) || ref.startsWith("https://", ignoreCase = true)

/** 名称自然序（与列表排序同口径，见 `networkFileComparator` 的说明） */
private val naturalByName: Comparator<NetworkFile> =
  compareBy(SortUtils.NaturalOrderComparator.DEFAULT) { it.name }

/**
 * 封面选择**算法口径**的版本号；口径变了就 +1。
 *
 * App 启动时比对持久化的同名列（`BrowserPreferences.folderCoverCacheVersion`），
 * 不一致就清空 `folder_cover_cache` 整表（[app.marlboroadvance.mpvex.repository.NetworkRepository.clearAllFolderCovers]）。
 *
 * 为什么需要它：封面结果是落盘缓存的（30 天 TTL）。改了选择规则却留着旧结果，
 * 用户升级后看到的还是老封面，要等 TTL 到期或手动下拉刷新才变 —— 表现就是
 * 「改了个寂寞」。凡是**同一批目录会算出不同封面**的改动，都必须涨这个号。
 *
 * 版本历史：
 * - 1（隐含，v232 初版）：目录内视频海报优先 → 第一张图片；纯结构目录穿透、按 BFS
 *   「先命中者胜」。
 * - 2：同层穿透改为**视频海报优先**（`A/{图片, 视频}` 里「图片」自然序在前，
 *   旧规则会让 A 永远拿图片子目录当封面）。
 */
const val FOLDER_COVER_CACHE_VERSION: Int = 2

/**
 * 在**单个目录的一层条目**里挑封面（纯函数，不含任何 IO，可单测）。
 *
 * ## 优先级（本次需求的核心）
 *
 * 1. **视频优先，但只在「视频已经有封面」时成立**：候选是「`thumb` 非空」的视频
 *    （服务端已给出海报帧）。名称自然序取第一条 —— 与列表排序同口径，
 *    同一目录每次算出来都是同一张，不会因为服务端返回顺序变化而跳动。
 * 2. **否则回退图片**：取名称自然序第一张图片的 path。
 * 3. 两者都没有 ⇒ null（本级没有可用封面，交给上层决定要不要穿透子文件夹）。
 *
 * ## 为什么「有视频但没海报」要回退到图片、而不是直接返回 null
 *
 * 同一目录里既有带海报的剧集、又有截图是很常见的（截图通常是花絮 / 封面残件）。
 * 视频那边一张海报都没有时，图片至少能表达「这里是什么内容」；返回 null 只会
 * 退化成一个灰文件夹图标，信息量为零。
 *
 * ## 为什么「仅含视频」的目录也走同一支
 *
 * 仅含视频时图片候选为空，第 1 步命中就直接用视频海报 —— 也就是说
 * 「视频优先」与「只有视频」在代码上是同一条路径，不需要额外分支：
 * 有海报的视频 ⇒ 有封面；一个海报都没有 ⇒ 无封面（本工程不自行截帧，
 * 见 [FolderCoverKind.VIDEO_THUMB]）。
 *
 * @param items 该目录**直接子级**的条目（可以含子文件夹，本函数会忽略它们）
 */
fun pickFolderCover(items: List<NetworkFile>): FolderCoverPick? {
  // ① 视频优先：只认「服务端已经给过海报」的视频，绝不为了封面去下载视频文件
  val video =
    items
      .asSequence()
      .filter { !it.isDirectory && it.isVideoFile() }
      .filter { !it.thumb.isNullOrBlank() }
      .minWithOrNull(naturalByName)
  if (video != null) {
    // 非空已由上面的 filter 保证
    return FolderCoverPick(ref = video.thumb!!, kind = FolderCoverKind.VIDEO_THUMB)
  }

  // ② 回退：名称自然序第一张图片（自然序理由见 networkFileComparator）
  val photo = items.asSequence().filter { it.isPhoto() }.minWithOrNull(naturalByName)
  return photo?.let { FolderCoverPick(ref = it.path, kind = FolderCoverKind.IMAGE) }
}

/**
 * 这个目录的条目里、可以继续向下穿透的子文件夹（名称自然序）。
 *
 * 只排**直接子级里的目录**；顺序固定，保证「先看哪个子文件夹」是可预期的
 * （上层是广度优先 + 命中即停，顺序决定最终取到谁的封面）。
 */
fun folderCoverChildDirs(items: List<NetworkFile>): List<NetworkFile> =
  items.filter { it.isDirectory }.sortedWith(naturalByName)
