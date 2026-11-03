package app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.AlistClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 按 [ToolPlan] 真去改服务器上的文件。
 *
 * ## 三条执行纪律
 *
 * 1. **一步失败不拖垮整轮**：网盘偶发 403 / 409 / 超时很常见，一个文件失败就停下，
 *    用户面对的是「一半搬了一半没搬」且不知道停在哪。这里全程计数、继续往下跑，
 *    最后把「成功 N / 失败 M」摆给用户。
 * 2. **移动前先解决重名**：`fs/move` 撞上同名文件的行为取决于底层驱动（有的覆盖、
 *    有的直接报错），所以先把源文件重命名成 `名字(1).ext` 再移动 —— 与 AlistClientN
 *    的 `FileOrganizeExecutor` 同一规则。目标目录的文件名按目录缓存一次，不重复列。
 * 3. **「提取并整理」的最后一步才扫空目录**：文件搬走之前子文件夹是满的，
 *    扫描那一刻判不出来，只能等搬完再扫一遍（[NetworkToolboxPlanner] 复用同一个计划器）。
 *
 * 协程取消（用户点「取消」）会立刻停下：已经做完的动作留在服务器上，不会回滚 ——
 * 这也是确认框要写「不可撤销」的原因。
 */
class NetworkToolboxExecutor(private val client: AlistClient) {

  /** dstDir → 该目录已有的文件名（含本轮改名 / 移入的），一个目录只列一次 */
  private val dirNames = HashMap<String, MutableSet<String>>()

  /**
   * @param onProgress (阶段, 已完成, 总数, 当前动作, 失败数)
   */
  suspend fun execute(
    plan: ToolPlan,
    onProgress: (
      phase: String,
      done: Int,
      total: Int,
      current: String,
      failed: Int,
      lastError: String?,
    ) -> Unit,
  ): ToolResult {
    var done = 0
    var failed = 0
    var firstError: String? = null
    var lastError: String? = null
    val failures = mutableListOf<ToolFailure>()
    val total = plan.actions.size

    fun step(action: ToolAction, ok: Boolean, error: String? = null) {
      done++
      if (!ok) {
        failed++
        if (firstError == null) firstError = error
        failures += ToolFailure(action, error)
        lastError =
          buildString {
            append(action.label)
            if (!error.isNullOrBlank()) append("：").append(error)
          }
      }
      // ⚠️ 回调是函数类型，实参必须按位置传（命名实参对函数类型不合法）
      onProgress(phaseOf(action), done, total, action.label, failed, lastError)
    }

    for (action in plan.actions) {
      coroutineContext.ensureActive()
      when (action) {
        is ToolAction.Mkdir -> {
          val result = client.mkdir(action.path)
          // 已存在也算成功：服务端可能回 409 / 400「文件已存在」，目标达成即可
          step(action, ok = result.isSuccess || isAlreadyExists(result.exceptionOrNull()), result.exceptionOrNull()?.message)
        }

        is ToolAction.Move -> {
          val outcome = moveWithUniqueName(action)
          step(action, ok = outcome.first, error = outcome.second)
        }

        is ToolAction.Rename -> {
          // 规划阶段已经按「全目录原名 + 已生成的新名」避过一次撞名，这里再兜一层：
          // 执行期间目录下可能新出现了同名文件（别的会话同时在传），`fs/rename`
          // 没有冲突策略，撞上可能直接覆盖，不能只信规划时的快照。
          val existing = namesIn(action.dir)
          val target = toolboxUniqueName(action.newName, existing - action.name)
          val result = client.rename(toolboxJoinPath(action.dir, action.name), target)
          if (result.isSuccess) {
            existing -= action.name
            existing += target
          }
          step(action, ok = result.isSuccess, error = result.exceptionOrNull()?.message)
        }

        is ToolAction.DeleteFile -> {
          val result = client.remove(action.dir, listOf(action.name))
          step(action, ok = result.isSuccess, error = result.exceptionOrNull()?.message)
        }

        is ToolAction.DeleteDir -> {
          val result = client.remove(action.dir, listOf(action.name))
          step(action, ok = result.isSuccess, error = result.exceptionOrNull()?.message)
        }

        is ToolAction.DeleteTree -> {
          // fs/remove 对目录是递归的，与删空目录同一个接口；差别只在文案与进度阶段
          val result = client.remove(action.dir, listOf(action.name))
          step(action, ok = result.isSuccess, error = result.exceptionOrNull()?.message)
        }
      }
    }

    // ── 收尾：搬空的子文件夹 ──
    var swept = 0
    if (plan.sweepEmptyDirs) {
      onProgress("清理空目录…", done, total, "正在检查空文件夹", failed, lastError)
      val sweepPlan =
        runCatching {
          NetworkToolboxPlanner.plan(
            tool = NetworkTool.CLEAN_EMPTY_DIRS,
            root = plan.root,
            list = { path -> client.listFiles(path).getOrThrow() },
          )
        }.getOrNull()
      sweepPlan?.actions?.forEach { action ->
        coroutineContext.ensureActive()
        if (action is ToolAction.DeleteDir) {
          if (client.remove(action.dir, listOf(action.name)).isSuccess) swept++
        }
      }
    }

    return ToolResult(
      succeeded = (done - failed).coerceAtLeast(0),
      failed = failed,
      firstError = firstError,
      sweptDirs = swept,
      failures = failures,
    )
  }

  /**
   * 把 `srcDir/name` 移到 `dstDir`，撞名时先改名。
   *
   * @return (成功?, 失败原因)
   */
  private suspend fun moveWithUniqueName(action: ToolAction.Move): Pair<Boolean, String?> {
    val existing = namesIn(action.dstDir)
    val target = toolboxUniqueName(action.name, existing)
    var currentName = action.name
    if (target != action.name) {
      try {
        // 先改源文件的名字：move 撞名时底层驱动的行为不统一（覆盖 / 报错），改名最稳
        client.rename(toolboxJoinPath(action.srcDir, action.name), target).getOrThrow()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return false to (e.message ?: "重名处理失败")
      }
      currentName = target
      existing += target
    }

    val result = client.move(action.srcDir, action.dstDir, listOf(currentName))
    return if (result.isSuccess) {
      existing += currentName
      true to null
    } else {
      false to (result.exceptionOrNull()?.message ?: "移动失败")
    }
  }

  /** 目标目录已有文件名（懒加载一次） */
  private suspend fun namesIn(dir: String): MutableSet<String> =
    dirNames.getOrPut(dir) {
      val names =
        runCatching { client.listFiles(dir).getOrThrow() }
          .map { list -> list.map { it.name }.toMutableSet() }
          .getOrElse { mutableSetOf() }
      names
    }

  private fun isAlreadyExists(error: Throwable?): Boolean {
    val message = error?.message?.lowercase() ?: return false
    return "exist" in message || "已存在" in message || "409" in message
  }

  private fun phaseOf(action: ToolAction): String =
    when (action) {
      is ToolAction.Mkdir -> "准备目标文件夹…"
      is ToolAction.Move -> "正在移动文件…"
      is ToolAction.Rename -> "正在重命名文件…"
      is ToolAction.DeleteFile -> "正在删除空文件…"
      is ToolAction.DeleteDir -> "正在删除空目录…"
      is ToolAction.DeleteTree -> "正在删除垃圾目录…"
    }
}
