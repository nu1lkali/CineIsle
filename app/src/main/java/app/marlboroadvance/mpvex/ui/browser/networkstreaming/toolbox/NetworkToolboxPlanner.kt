package app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox

import app.marlboroadvance.mpvex.domain.network.NetworkFile

/**
 * 扫描目录、把「用户选的那个工具要做哪些事」算成一份 [ToolPlan]。
 *
 * ## 为什么扫描与执行分开
 *
 * AList 没有回收站，一次「提取并整理」可能搬动几百个文件。所以一律先扫出清单、
 * 把「动几个文件、落到哪些文件夹、删几个空目录」摆给用户看，确认之后才执行。
 * 扫描本身是只读的（`fs/list`），失败了最多是白扫一次。
 *
 * ## 目录遍历的三条护栏
 *
 * - **深度** [MAX_DEPTH]：防止软链 / 挂载点绕圈（AList 的挂载可以指向自己）。
 * - **目录数** [MAX_DIRS] + **文件数** [MAX_FILES]：一次整理几十万个文件既慢又危险，
 *   到上限就收手并把 [ToolPlan.truncated] 打开，UI 会明确告诉用户「只处理已扫到的部分」。
 * - **单个目录列失败不放弃整轮**：网盘偶发 403 / 超时很常见，跳过这一支比整轮报错好 ——
 *   大不了这个子树这一轮不整理，重新点一次就好。
 */
object NetworkToolboxPlanner {
  /** 最多下钻几层 */
  private const val MAX_DEPTH = 12

  /** 一次最多扫多少个目录 */
  private const val MAX_DIRS = 800

  /** 一次最多处理多少个文件 */
  private const val MAX_FILES = 30_000

  /**
   * @param root 当前目录（内部相对路径，无前导斜杠；根目录传空串）
   * @param list 列一个目录的条目。由 ViewModel 接到 `AlistClient.listFiles` 上
   * @param onPhase 扫描进度回调（正在看哪个目录），UI 把它显示成「扫描：xxx」
   */
  suspend fun plan(
    tool: NetworkTool,
    root: String,
    list: suspend (String) -> List<NetworkFile>,
    onPhase: (String) -> Unit = {},
  ): ToolPlan {
    val rootEntries = list(root)
    return when (tool) {
      NetworkTool.ORGANIZE_BY_TYPE -> planOrganizeByType(root, rootEntries)
      NetworkTool.ARCHIVE_BY_MONTH -> planArchiveByMonth(root, rootEntries)
      NetworkTool.EXTRACT_AND_ORGANIZE,
      NetworkTool.CLEAN_EMPTY_DIRS,
      NetworkTool.CLEAN_EMPTY_FILES -> planWithWalk(tool, root, rootEntries, list, onPhase)
    }
  }

  // ────────────────────────── 按类型归类（只看当前层） ──────────────────────────

  private fun planOrganizeByType(root: String, entries: List<NetworkFile>): ToolPlan {
    val groups = LinkedHashMap<String, MutableList<String>>()
    entries.filter { !it.isDirectory }.forEach { file ->
      val category = toolboxCategoryOf(file.name) ?: return@forEach
      groups.getOrPut(category) { mutableListOf() }.add(file.name)
    }
    val actions = mutableListOf<ToolAction>()
    groups.forEach { (category, names) ->
      val target = toolboxJoinPath(root, category)
      actions += ToolAction.Mkdir(target)
      names.forEach { actions += ToolAction.Move(root, it, target) }
    }
    val summary = buildList {
      if (groups.isEmpty()) {
        add("当前目录没有可归类的文件（图片 / 视频 / 音频 / 文档）")
      } else {
        add("将把 ${groups.values.sumOf { it.size }} 个文件移进 ${groups.size} 个子文件夹：")
        groups.forEach { (category, names) -> add("· $category ${names.size} 个") }
        val unknown = entries.count { !it.isDirectory && toolboxCategoryOf(it.name) == null }
        if (unknown > 0) add("认不出类型的 $unknown 个文件留在原处")
      }
      add("同名文件会自动改名为「名字(1).扩展名」，不会覆盖")
    }
    return ToolPlan(tool = NetworkTool.ORGANIZE_BY_TYPE, root = root, actions = actions, summary = summary)
  }

  // ────────────────────────── 按月份归档（只看当前层） ──────────────────────────

  private fun planArchiveByMonth(root: String, entries: List<NetworkFile>): ToolPlan {
    val groups = LinkedHashMap<String, MutableList<String>>()
    entries.filter { !it.isDirectory }.forEach { file ->
      groups.getOrPut(toolboxMonthFolder(file.lastModified)) { mutableListOf() }.add(file.name)
    }
    val actions = mutableListOf<ToolAction>()
    groups.forEach { (month, names) ->
      val target = toolboxJoinPath(root, month)
      actions += ToolAction.Mkdir(target)
      names.forEach { actions += ToolAction.Move(root, it, target) }
    }
    val summary = buildList {
      if (groups.isEmpty()) {
        add("当前目录没有文件可归档")
      } else {
        add("将把 ${groups.values.sumOf { it.size }} 个文件移进 ${groups.size} 个月份文件夹：")
        // 月份可能很多，只列前 6 个，余下用一句带过
        groups.entries.take(6).forEach { (month, names) -> add("· $month ${names.size} 个") }
        if (groups.size > 6) add("· 其余 ${groups.size - 6} 个月份略")
        if (groups.containsKey("未知日期")) add("修改时间缺失的文件归到「未知日期」")
      }
      add("同名文件会自动改名，不会覆盖")
    }
    return ToolPlan(tool = NetworkTool.ARCHIVE_BY_MONTH, root = root, actions = actions, summary = summary)
  }

  // ────────────────────── 需要递归的三个：提取整理 / 清空目录 / 清空文件 ──────────────────────

  private suspend fun planWithWalk(
    tool: NetworkTool,
    root: String,
    rootEntries: List<NetworkFile>,
    list: suspend (String) -> List<NetworkFile>,
    onPhase: (String) -> Unit,
  ): ToolPlan {
    val budget = Budget()
    // 后序：子目录先入表、父目录后入 —— 判「父目录是否只剩空子目录」靠的就是这个顺序
    val dirEntries = LinkedHashMap<String, List<NetworkFile>>()
    val filesInSubdirs = mutableListOf<Pair<String, NetworkFile>>()

    rootEntries.filter { it.isDirectory }.forEach { sub ->
      walk(
        dir = toolboxJoinPath(root, sub.name),
        depth = 1,
        budget = budget,
        list = list,
        onPhase = onPhase,
        dirEntries = dirEntries,
        filesOut = filesInSubdirs,
      )
    }

    return when (tool) {
      NetworkTool.CLEAN_EMPTY_DIRS -> {
        val empties = emptyDirs(dirEntries)
        val actions = empties.map { ToolAction.DeleteDir(toolboxParentOf(it), toolboxNameOf(it)) }
        val summary = buildList {
          if (empties.isEmpty()) {
            add("没有空文件夹")
          } else {
            add("将删除 ${empties.size} 个空文件夹（已含子目录）：")
            empties.take(6).forEach { add("· $it") }
            if (empties.size > 6) add("· 其余 ${empties.size - 6} 个略")
          }
          add("扫描了 ${dirEntries.size} 个文件夹")
        }
        ToolPlan(
          tool = tool,
          root = root,
          actions = actions,
          summary = summary,
          truncated = budget.truncated,
        )
      }

      NetworkTool.CLEAN_EMPTY_FILES -> {
        val rootFiles = rootEntries.filter { !it.isDirectory }
        val empties =
          buildList {
            rootFiles.filter { it.size == 0L && !it.name.startsWith(".") }
              .forEach { add(root to it.name) }
            filesInSubdirs.filter { (_, file) -> file.size == 0L && !file.name.startsWith(".") }
              .forEach { (dir, file) -> add(dir to file.name) }
          }
        val actions = empties.map { (dir, name) -> ToolAction.DeleteFile(dir, name) }
        val summary = buildList {
          if (empties.isEmpty()) {
            add("没有 0 字节的文件")
          } else {
            add("将删除 ${empties.size} 个空文件：")
            empties.take(8).forEach { (dir, name) -> add("· ${toolboxJoinPath(dir, name)}") }
            if (empties.size > 8) add("· 其余 ${empties.size - 8} 个略")
          }
          add("隐藏文件（. 开头）不会被删")
          add("扫描了 ${budget.files + rootFiles.size} 个文件")
        }
        ToolPlan(
          tool = tool,
          root = root,
          actions = actions,
          summary = summary,
          truncated = budget.truncated,
        )
      }

      // EXTRACT_AND_ORGANIZE
      else -> {
        val all =
          buildList {
            rootEntries.filter { !it.isDirectory }.forEach { add(root to it) }
            addAll(filesInSubdirs)
          }
        val actions = mutableListOf<ToolAction>()
        val targets = LinkedHashSet<String>()
        val perCategory = LinkedHashMap<String, Int>()
        var skipped = 0
        all.forEach { (dir, file) ->
          val category = toolboxCategoryOf(file.name) ?: TOOLBOX_CAT_OTHER
          val target = toolboxJoinPath(root, category)
          // 已经躺在目标目录里（上一次整理过）就别再动它
          if (dir == target) {
            skipped++
            return@forEach
          }
          targets += target
          perCategory[category] = (perCategory[category] ?: 0) + 1
          actions += ToolAction.Move(dir, file.name, target)
        }
        val summary = buildList {
          val moved = perCategory.values.sumOf { it }
          if (moved == 0) {
            add("没有需要整理的文件")
          } else {
            val fromRoot = rootEntries.count { !it.isDirectory }
            add(
              "共 $moved 个文件（当前层 $fromRoot 个 + 子文件夹 ${filesInSubdirs.size} 个）：",
            )
            perCategory.forEach { (category, count) -> add("· $category $count 个") }
            if (skipped > 0) add("已在目标文件夹里的 $skipped 个不动")
            add("整理完成后会删除腾空的子文件夹")
          }
          if (budget.truncated) add("已达扫描上限，只处理已扫到的部分")
        }
        ToolPlan(
          tool = tool,
          root = root,
          actions = buildList {
            targets.forEach { add(ToolAction.Mkdir(it)) }
            addAll(actions)
          },
          summary = summary,
          truncated = budget.truncated,
          // 文件搬走之后子文件夹才变空，扫描这一刻判不出来 ⇒ 执行完再扫一遍
          sweepEmptyDirs = actions.isNotEmpty(),
        )
      }
    }
  }

  /** 递归收集：目录条目按**后序**入表，文件带所属目录 */
  private suspend fun walk(
    dir: String,
    depth: Int,
    budget: Budget,
    list: suspend (String) -> List<NetworkFile>,
    onPhase: (String) -> Unit,
    dirEntries: MutableMap<String, List<NetworkFile>>,
    filesOut: MutableList<Pair<String, NetworkFile>>,
  ) {
    if (depth > MAX_DEPTH) return
    if (budget.dirs >= MAX_DIRS) {
      budget.truncated = true
      return
    }
    budget.dirs++
    onPhase(dir)
    // 列不出来就跳过这一支：这一轮不整理它，重试即可（不因此让整轮失败）
    val entries = runCatching { list(dir) }.getOrNull() ?: return
    entries.forEach { entry ->
      if (entry.isDirectory) {
        walk(
          dir = toolboxJoinPath(dir, entry.name),
          depth = depth + 1,
          budget = budget,
          list = list,
          onPhase = onPhase,
          dirEntries = dirEntries,
          filesOut = filesOut,
        )
      } else {
        if (budget.files >= MAX_FILES) {
          budget.truncated = true
          return
        }
        budget.files++
        filesOut += dir to entry
      }
    }
    dirEntries[dir] = entries
  }

  /**
   * 从后序的目录表里挑出「删了也不会丢东西」的目录。
   *
   * 判据：目录为空，或者**所有条目都是已被判空的子目录**。
   * 因为表是后序的（子在前、父在后），一趟就能推完 —— 不需要反复迭代到不动点。
   */
  private fun emptyDirs(dirEntries: Map<String, List<NetworkFile>>): List<String> {
    val deletable = HashSet<String>()
    dirEntries.forEach { (dir, entries) ->
      if (entries.isEmpty()) {
        deletable += dir
      } else if (entries.all { it.isDirectory && toolboxJoinPath(dir, it.name) in deletable }) {
        deletable += dir
      }
    }
    // ⚠️ 必须**深的先删**：父目录只有在子目录删掉之后才真的空。
    //    反过来（浅的先删）父目录这一刻还不空，服务端会拒绝，等于白跑一趟。
    return deletable.sortedByDescending { it.count { ch -> ch == '/' } }
  }

  private class Budget {
    var dirs = 0
    var files = 0
    var truncated = false
  }
}
