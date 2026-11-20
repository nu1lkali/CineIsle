package app.marlboroadvance.mpvex.domain.emby

/**
 * 媒体库「名字」与「磁盘目录」的对照表 —— 目录导航认库根的唯一依据。
 *
 * ## 为什么需要一张表
 *
 * Emby 里一个媒体库的**名字**是用户在服务端手动设置的（例：「Movies_HD」），
 * 而它索引的那个磁盘目录叫 `HD`、`张三`，挂到乱码目录就叫那串乱码。
 * `/Items/{Id}/Ancestors` 返回的是**磁盘目录条目**，`Name` 给的是目录名 ——
 * 于是目录导航里库根那一行会显示成 `HD`，而不是用户起的库名。
 *
 * 只要能把「目录末段 → 库名」对上，那一行就能改回库名，而且对所有库通用：
 * 换库名、换挂载点、挂到乱码目录，都由服务端 `/Users/{uid}/Views` 说了算，
 * 客户端不写死任何路径。
 *
 * ## 两条索引的分工
 *
 * - [byId]：`库 Id → 库名`。**最硬**的判据 —— 界面拿到的条目 Id 正好是某个库时用它。
 * - [bySegment]：`磁盘目录末段（小写）→ 库名`。Id 对不上时的退路：
 *   服务端把库根当成普通 `Folder` 条目返回时，只有目录名能认。
 *
 * ⚠️ 两个索引都可能为空：`/Views` 不保证返回 `Path`（拿不到就只剩 [byId]），
 * 也可能一次都没请求过（冷启动直进深层）。调用方一律按「查不到就保持原样」处理。
 */
data class LibraryNameIndex(
  val byId: Map<String, String> = emptyMap(),
  val bySegment: Map<String, String> = emptyMap(),
) {
  val isEmpty: Boolean get() = byId.isEmpty() && bySegment.isEmpty()

  companion object {
    val EMPTY = LibraryNameIndex()
  }
}

/**
 * 取路径的**末段**：`/mnt/data/HD` → `HD`、`D:\media\HD\` → `HD`、`smb://nas/HD` → `HD`。
 *
 * 兼容 Windows 反斜杠与 POSIX 正斜杠，并去掉尾部斜杠（`/mnt/data/HD/` 不该取到空串）。
 * 空串 / null / 只有分隔符时返回 null —— 这种「末段」拿去比对只会误伤。
 */
fun pathLastSegment(path: String?): String? =
  path
    ?.trim()
    ?.trimEnd('\\', '/')
    ?.substringAfterLast('\\')
    ?.substringAfterLast('/')
    ?.takeIf { it.isNotBlank() }

/**
 * 把名字按常见分隔符切段：`Movies_HD` → `[Movies, HD]`。
 *
 * 只用来做「这一行是不是这个库的目录」的最后一道启发式（见
 * `resolveLibraryRowNames`）。分隔符刻意只收下划线 / 连字符 / 点 / 空格这类
 * **人起名时会用的**符号，不收字母数字 —— 否则 `MoviesHD` 会被切成无意义的碎片。
 */
fun nameTokens(name: String?): List<String> {
  if (name.isNullOrBlank()) return emptyList()
  return name
    .split('_', '-', '.', '·', ' ', '\t')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
}
