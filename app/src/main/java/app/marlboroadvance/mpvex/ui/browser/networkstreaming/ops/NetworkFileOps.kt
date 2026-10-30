package app.marlboroadvance.mpvex.ui.browser.networkstreaming.ops

import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.AlistClient
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.toolboxJoinPath
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.toolboxNameOf
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.toolboxParentOf
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox.toolboxUniqueName

/**
 * 复制 / 移动的方向。
 *
 * 两个动作在 AList 上是**一对同构接口**（`fs/copy` 与 `fs/move`，请求体一模一样），
 * 差别只在「源还在不在」。所以执行器只写一份，用这个枚举区分那一点点不同 ——
 * 写成两个函数的话，重名处理、自身嵌套检查这些边界迟早只在一边修。
 */
enum class NetworkTransferMode(
  /** 动名词，用于文案：「复制到…」「正在移动 2/5」 */
  val verb: String,
  /** 完成态，用于 toast：「已复制 3 个」 */
  val doneVerb: String,
) {
  COPY(verb = "复制", doneVerb = "复制"),
  MOVE(verb = "移动", doneVerb = "移动"),
}

/**
 * 一次批量写操作的结果。
 *
 * 三个数各有各的用途，缺一个用户就会疑惑：
 * - [succeeded] 干成了几个；
 * - [skipped] 因为「本来就在目标目录」「不能放进自己里面」这类**合理原因**没动；
 * - [failed] 服务端拒绝 / 网络断了。
 *
 * [firstError] 只留**第一条**错误原文：toast 塞不下第二条，而多条时通常同因
 * （同一个目录没权限），留一条就够定位。
 */
data class NetworkOpResult(
  val succeeded: Int = 0,
  val failed: Int = 0,
  val skipped: Int = 0,
  val firstError: String? = null,
) {
  /** 给 toast 用的一句话总结（成功 / 跳过 / 失败按需拼接，全 0 时给空串） */
  fun summaryText(action: String): String =
    buildString {
      if (succeeded > 0) append("已$action $succeeded 个")
      if (skipped > 0) {
        if (isNotEmpty()) append(" · ")
        append("跳过 $skipped 个")
      }
      if (failed > 0) {
        if (isNotEmpty()) append(" · ")
        append("失败 $failed 个")
      }
      firstError?.let {
        if (isNotEmpty()) append("：")
        append(it)
      }
    }.ifEmpty { "没有需要$action 的条目" }
}

/**
 * AList 的**批量写操作执行器**（复制 / 移动 / 删除）。
 *
 * ## 为什么不复用工具箱的 `NetworkToolboxExecutor`
 *
 * 那个是「按计划搬一整个目录树」的执行器：输入是 [ToolPlan]（扫描出来的动作清单），
 * 语义是整理；这里输入是用户**当面挑中的几个条目**，语义是文件管理。
 * 硬套的话要给工具计划造一个假 plan，反而更绕。共用的是**重名策略**这些纯函数
 * （[toolboxUniqueName] / [toolboxParentOf]），那才是真正该共享的东西。
 *
 * ## 重名策略（这是整个类最要紧的一条）
 *
 * AList 的 `fs/copy` / `fs/move` **没有冲突策略参数**，底层驱动行为各异：
 * 有的直接覆盖、有的回错、有的自己生成 `name(1).ext`。而「覆盖」是不可逆的
 * 数据丢失，所以这里一律自己处理，绝不把决定权交给服务端：
 *
 * - **移动**：目标已有同名 ⇒ 先在本目录把源改名成唯一名，再移（2 个请求）。
 *   与工具箱同一手法，零覆盖风险。
 * - **复制**：源不能改名（那会改到用户的原件），所以改用
 *   「目标下建临时目录 → 复制进去 → 在临时目录里改名 → 移出来 → 删临时目录」。
 *   5 个请求换「绝不覆盖 + 保留两者」，只有真撞名时才走这条路；
 *   同目录复制（= 复制一份副本）必然撞名，走的也是这条 —— 这正是最常用的场景。
 *
 * ## 自身嵌套
 *
 * 把 `a` 移到 `a/b` 里是无限递归，AList 会直接报错。这里先判掉并计入
 * [NetworkOpResult.skipped]，省一次注定失败的请求，也给出人话原因。
 *
 * ## 串行
 *
 * 全部串行执行。AList 的写操作在服务端是带全局锁的（尤其本机存储驱动），
 * 并发只会互相排队 + 制造半成品；而批量操作的条目数本来就是人挑出来的量级。
 */
object NetworkFileOps {
  /**
   * 复制 / 移动一组条目到 [dstDir]。
   *
   * @param dstDir 目标目录（相对 base path，根目录传空串或 `/`）
   * @param onProgress (已完成, 总数)。⚠️ 在协程里同步回调，UI 侧只该更新状态
   */
  suspend fun transfer(
    client: AlistClient,
    files: List<NetworkFile>,
    dstDir: String,
    mode: NetworkTransferMode,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
  ): NetworkOpResult {
    if (files.isEmpty()) return NetworkOpResult()
    val target = dstDir.trim('/')

    // 目标目录现有名字：**列一次、全程复用**。每个文件各列一次的话，
    // 20 个条目就是 20 次目录请求，而且中途新落地的名字还看不出来。
    val dstNames =
      runCatching { client.listFiles(target).getOrThrow().mapTo(mutableSetOf()) { it.name } }
        .getOrElse {
          return NetworkOpResult(failed = files.size, firstError = "无法读取目标目录：${it.message}")
        }

    var ok = 0
    var failed = 0
    var skipped = 0
    var firstError: String? = null

    files.forEachIndexed { index, file ->
      val srcPath = file.path.trim('/')
      val srcDir = toolboxParentOf(srcPath)
      val name = file.name.ifBlank { toolboxNameOf(srcPath) }

      val outcome =
        runCatching {
          // 目标在源内部（含相等）：移动/复制到自己里面必然失败，先拦掉给人话
          if (srcPath.isNotEmpty() && (target == srcPath || target.startsWith("$srcPath/"))) {
            return@runCatching StepResult.Skipped("不能放进它自己里面")
          }
          when (mode) {
            NetworkTransferMode.MOVE -> moveOne(client, srcDir, name, target, dstNames)
            NetworkTransferMode.COPY -> copyOne(client, srcDir, name, target, dstNames)
          }
        }

      outcome.fold(
        onSuccess = { step ->
          when (step) {
            StepResult.Done -> ok++
            is StepResult.Skipped -> {
              skipped++
              if (firstError == null) firstError = step.reason
            }
          }
        },
        onFailure = { e ->
          failed++
          if (firstError == null) firstError = e.message ?: "${mode.verb}失败"
        },
      )
      onProgress(index + 1, files.size)
    }

    return NetworkOpResult(
      succeeded = ok,
      failed = failed,
      skipped = skipped,
      firstError = firstError,
    )
  }

  /**
   * 批量删除（`fs/remove` 一次能带走同一目录下的多个名字，所以按目录分组发）。
   *
   * ⚠️ **不可逆**（AList 没有回收站），调用方必须二次确认后再调。
   */
  suspend fun delete(
    client: AlistClient,
    files: List<NetworkFile>,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
  ): NetworkOpResult {
    if (files.isEmpty()) return NetworkOpResult()
    var ok = 0
    var failed = 0
    var firstError: String? = null
    var done = 0

    // 同一目录下的条目合成一次请求：fs/remove 的 names 本来就是数组
    files.groupBy { toolboxParentOf(it.path.trim('/')) }.forEach { (dir, items) ->
      runCatching { client.remove(dir, items.map { it.name }).getOrThrow() }
        .onSuccess { ok += items.size }
        .onFailure {
          failed += items.size
          if (firstError == null) firstError = it.message
        }
      done += items.size
      onProgress(done, files.size)
    }

    return NetworkOpResult(succeeded = ok, failed = failed, firstError = firstError)
  }

  // ────────────────────────── 单条目实现 ──────────────────────────

  private suspend fun moveOne(
    client: AlistClient,
    srcDir: String,
    name: String,
    dstDir: String,
    dstNames: MutableSet<String>,
  ): StepResult {
    // 本来就是同一个文件夹：什么都不用做（不是错误，别计入失败）
    if (srcDir == dstDir) return StepResult.Skipped("已在目标文件夹")
    var moving = name
    if (moving in dstNames) {
      // 先在源目录改名：改完再移，服务端永远不会看到「目标已有同名」
      moving = toolboxUniqueName(name, dstNames)
      client.rename(toolboxJoinPath(srcDir, name), moving).getOrThrow()
    }
    client.move(srcDir, dstDir, listOf(moving)).getOrThrow()
    dstNames += moving
    return StepResult.Done
  }

  private suspend fun copyOne(
    client: AlistClient,
    srcDir: String,
    name: String,
    dstDir: String,
    dstNames: MutableSet<String>,
  ): StepResult {
    if (name !in dstNames) {
      client.copy(srcDir, dstDir, listOf(name)).getOrThrow()
      dstNames += name
      return StepResult.Done
    }

    // ── 撞名：临时目录中转，拿到 name(1).ext 且不覆盖任何东西 ──
    val newName = toolboxUniqueName(name, dstNames)
    val tmpName = ".cineisle_tmp_${System.currentTimeMillis()}_${(100..999).random()}"
    val tmpDir = toolboxJoinPath(dstDir, tmpName)
    client.mkdir(tmpDir).getOrThrow()
    try {
      // 临时目录是新建的、必然是空的 ⇒ 这一步绝不可能撞名
      client.copy(srcDir, tmpDir, listOf(name)).getOrThrow()
      client.rename(toolboxJoinPath(tmpDir, name), newName).getOrThrow()
      client.move(tmpDir, dstDir, listOf(newName)).getOrThrow()
    } finally {
      // 成功时清掉腾空的中转目录；失败时尽力清掉里面可能残留的半成品。
      // ⚠️ 这里吞掉清理的异常：真正要报给用户的是上面那步失败的原因，
      //    不能被「清理也失败了」盖掉（残留物以 `.` 开头，不碍事）。
      runCatching { client.remove(dstDir, listOf(tmpName)) }
    }
    dstNames += newName
    return StepResult.Done
  }

  /** 单个条目的结局：干成了 / 合理跳过（带原因） */
  private sealed interface StepResult {
    object Done : StepResult

    data class Skipped(val reason: String) : StepResult
  }
}
