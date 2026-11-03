package app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 网络目录页的**整理工具箱**抽屉。
 *
 * ## 交互：三段式，绝不「点了就动手」
 *
 * ```
 * 工具列表 → 点一项 → 扫描（只读）→ 确认清单 → 执行（进度）→ 结果
 * ```
 *
 * AList 没有回收站，这里的每个动作都可能搬动几百个文件，所以中间那道「清单 + 确认」
 * 不是可选项：用户必须看见「动几个、落到哪些文件夹、删什么」再点头。
 *
 * ## 状态放在哪
 *
 * 进度不在这里存，而是订阅 ViewModel 的 [ToolProgress]（见 `NetworkBrowserViewModel`）——
 * 面板关掉、整理继续跑，重新打开还能接着看。这里的 `remember` 只放「扫描中 / 待确认的
 * 计划」这类**面板独有**的临时状态。
 *
 * @param atRoot 当前是否在连接的**根目录**。是的话除只读工具外全部置灰 ——
 *   根目录往往是所有网盘挂载的汇合处，「按类型归类」在那一层建个「视频」目录
 *   就等于把整块存储的顶层文件全搬走，误点一次代价太大。ViewModel 里还有第二道闸。
 */
@Composable
fun NetworkToolboxSheet(
  dirTitle: String,
  progress: ToolProgress?,
  atRoot: Boolean = false,
  onScan: (NetworkTool, (ToolPlan?, String?) -> Unit) -> Unit,
  onRun: (ToolPlan) -> Unit,
  onCancel: () -> Unit,
  onDismiss: () -> Unit,
  /** 批量重命名的规则链（ViewModel 持有一份持久化的，重启后仍在） */
  renameBook: RenameRuleBook = RenameRuleBook(),
  /** 当前目录的全部原名，给规则编辑器做撞名避让基准（零请求即时预览） */
  renameTakenNames: List<String> = emptyList(),
  /** 按范围开关过滤后的执行名单，顺序必须与规划器一致 */
  renameTargetNames: List<String> = emptyList(),
  onRenameBookChange: (RenameRuleBook) -> Unit = {},
  onRetryFailed: () -> Unit = {},
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  // 面板自己的临时状态：正在扫哪个工具 / 扫出来的计划（等确认）
  var scanning by remember { mutableStateOf<NetworkTool?>(null) }
  var pendingPlan by remember { mutableStateOf<ToolPlan?>(null) }
  var scanError by remember { mutableStateOf<String?>(null) }
  // 「批量重命名」不直接扫描：先开规则链编辑器（对齐 flut-renamer 的 rules_page）
  var renameEditorOpen by remember { mutableStateOf(false) }

  /** 结束态：有结果 / 有错误 / 被取消，三者任一都算跑完了 */
  val finished = progress != null && (progress.result != null || progress.errorMessage != null || progress.cancelled)
  val running = progress != null && !finished && progress.total > 0

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    // 自带拖拽条会和自定义标题挤在一起，关掉（与上传面板同口径）
    dragHandle = null,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
    ) {
      // ── 头部：标题 + 当前目录 ──
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text =
              when {
                running -> "正在整理"
                finished -> "整理结果"
                renameEditorOpen -> "批量重命名"
                else -> "整理工具箱"
              },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text = "当前 · $dirTitle",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = "关闭")
        }
      }

      Spacer(Modifier.height(8.dp))

      // 取一份局部引用：直接对参数 progress 判空会被智能转换吃掉（编译器报
      // 「Condition is always true」），转成局部 val 后判空才有意义，也顺便把
      // 三个分支收在一处读
      val active = progress
      when {
        // 批量重命名：规则链编辑就是抽屉里的一页，不另弹窗口，返回键直接回工具列表
        renameEditorOpen ->
          RenameRulePane(
            book = renameBook,
            takenNames = renameTakenNames,
            targetNames = renameTargetNames,
            onBookChange = onRenameBookChange,
            onPreview = {
              renameEditorOpen = false
              scanError = null
              scanning = NetworkTool.BATCH_RENAME
              onScan(NetworkTool.BATCH_RENAME) { plan, error ->
                scanning = null
                if (plan != null) pendingPlan = plan else scanError = error ?: "扫描失败"
              }
            },
            onBack = { renameEditorOpen = false },
          )

        active != null && running -> ProgressPane(progress = active, onCancel = onCancel)

        active != null && finished ->
          ResultPane(progress = active, onClose = onDismiss, onRetryFailed = onRetryFailed)

        else ->
          ToolListPane(
            scanning = scanning,
            atRoot = atRoot,
            scanError = scanError,
            onScanErrorShown = { scanError = null },
            onPick = { tool ->
              scanError = null
              if (tool == NetworkTool.BATCH_RENAME) {
                // 批量重命名要先定规则链，点一下就去扫描等于拿着空规则白跑一趟
                renameEditorOpen = true
              } else {
                scanning = tool
                onScan(tool) { plan, error ->
                  scanning = null
                  if (plan != null) {
                    pendingPlan = plan
                  } else {
                    scanError = error ?: "扫描失败"
                  }
                }
              }
            },
          )
      }

      Spacer(Modifier.height(12.dp))
    }
  }

  // ── 确认清单 / 只读报告 ──
  // 只读工具（大文件排行）没有「动手」这一步，扫完直接把报告摊开：
  // 对一份什么都不会改的清单点「确认执行」是纯噪声，还会让人以为它要动文件。
  val plan = pendingPlan
  if (plan != null) {
    val summaryColumn: @Composable () -> Unit = {
      Column(
        modifier =
          Modifier
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
      ) {
        plan.summary.forEach { line ->
          Text(
            text = line,
            style =
              if (plan.tool.readOnly) {
                MaterialTheme.typography.bodySmall
              } else {
                MaterialTheme.typography.bodyMedium
              },
            modifier = Modifier.padding(vertical = 2.dp),
          )
        }
      }
    }
    val truncatedNote: @Composable () -> Unit = {
      if (plan.truncated) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = "⚠️ 已达扫描上限，只处理已扫到的部分（重新点一次可继续处理剩下的）",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    if (plan.tool.readOnly) {
      AlertDialog(
        onDismissRequest = { pendingPlan = null },
        title = { Text(plan.tool.title) },
        text = {
          Column {
            summaryColumn()
            truncatedNote()
          }
        },
        confirmButton = {
          TextButton(onClick = { pendingPlan = null }) { Text("知道了") }
        },
      )
    } else {
      val empty = plan.isEmpty
      AlertDialog(
        onDismissRequest = { pendingPlan = null },
        title = { Text(plan.tool.title) },
        text = {
          Column {
            summaryColumn()
            truncatedNote()
            Spacer(Modifier.height(12.dp))
            Text(
              text = "操作直接作用在服务器上，AList 没有回收站，完成后不可撤销。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        },
        confirmButton = {
          TextButton(
            enabled = !empty,
            onClick = {
              pendingPlan = null
              onRun(plan)
              // 关掉面板不影响执行（状态在 ViewModel 上），但这里留着能看到进度
              scope.launch { sheetState.expand() }
            },
          ) {
            Text(if (empty) "无可处理" else "确认执行")
          }
        },
        dismissButton = {
          TextButton(onClick = { pendingPlan = null }) { Text("取消") }
        },
      )
    }
  }
}

/**
 * 工具清单：网格卡片。
 *
 * ⭐ 用 `LazyVerticalGrid` 而不是 `Column + verticalScroll`，两件事一起解决：
 * 1. 工具攒到八个之后，一列一行要滚很久；两列一屏基本看全。
 * 2. 旧写法一次组合全部卡片，且内层 `verticalScroll` 与 `ModalBottomSheet` 的
 *    拖拽手势靠 nestedScroll 抢事件，滚动发涩。Lazy 容器只组合可见项，也是
 *    BottomSheet 官方推荐的可滚动内容形态。
 *
 * 列数按抽屉实际可用宽度反推：窄屏两列、平板三列。这里固定列数而不用
 * `GridCells.Adaptive`，是因为 Adaptive 在窄屏会退化成一列、大屏又会挤成四列窄条，
 * 卡片里的两行描述就没法读了。
 */
@Composable
private fun ToolListPane(
  scanning: NetworkTool?,
  atRoot: Boolean,
  scanError: String?,
  onScanErrorShown: () -> Unit,
  onPick: (NetworkTool) -> Unit,
) {
  // 说明和错误条放在网格外面：它们是固定的头部，不该跟着卡片一起滚。
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
    Text(
      text =
        if (atRoot) {
          "根目录只开放「大文件排行」（只读）。整理 / 清理类会把整块存储顶层的文件搬走或删掉，" +
            "先进到具体目录再用。"
        } else {
          "先扫描目录、把要做的改动列成清单，确认之后才会向服务器发送请求。「大文件排行」只读，只出报告。"
        },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(bottom = 10.dp),
    )

    if (scanError != null) {
      Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = scanError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = onScanErrorShown) { Text("知道了") }
        }
      }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
      val columns = if (maxWidth >= 600.dp) 3 else 2
      LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        contentPadding = PaddingValues(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // 稳定 key：扫描中 / 置灰状态变化时，Lazy 能按 key 复用卡片而不是整表重组。
        items(NetworkTool.entries, key = { it.name }) { tool ->
          val blocked = atRoot && !tool.readOnly
          ToolCard(
            tool = tool,
            scanning = scanning == tool,
            enabled = scanning == null && !blocked,
            note = if (blocked) "根目录下不开放" else null,
            onClick = { onPick(tool) },
          )
        }
      }
    }
  }
}

@Composable
private fun ToolCard(
  tool: NetworkTool,
  scanning: Boolean,
  enabled: Boolean,
  note: String? = null,
  onClick: () -> Unit,
) {
  val icon = tool.icon()
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(16.dp),
    modifier =
      Modifier
        .fillMaxWidth()
        // 只给**最小**高度，不写死：LazyGrid 的 item 是 wrap content，没有下限时
        // 同一行卡片会长短不齐；但一旦写死高度，文案一长就被裁成省略号。
        // 描述本身已压到 15 字内（2 列最多 2 行），这里留 3 行护栏负责「将来改长也不裁」。
        .heightIn(min = 150.dp)
        // 置灰用整体透明度而不是改色：图标底色、标题、描述、箭头一次性都淡下去，
        // 不会出现「卡片是灰的但字是全黑」的半灰状态
        .alpha(if (enabled || scanning) 1f else 0.45f)
        .clickable(enabled = enabled && !scanning, onClick = onClick),
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier =
            Modifier
              .size(38.dp)
              .background(
                color =
                  if (tool.destructive) {
                    MaterialTheme.colorScheme.errorContainer
                  } else {
                    MaterialTheme.colorScheme.secondaryContainer
                  },
                shape = RoundedCornerShape(12.dp),
              ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint =
              if (tool.destructive) {
                MaterialTheme.colorScheme.onErrorContainer
              } else {
                MaterialTheme.colorScheme.onSecondaryContainer
              },
            modifier = Modifier.size(20.dp),
          )
        }
        Spacer(Modifier.weight(1f))
        if (scanning) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
          Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
      }
      Spacer(Modifier.height(10.dp))
      Text(
        text = tool.title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(3.dp))
      Text(
        text = tool.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // minLines 让描述较短的卡片也占两行，整排文案基线对齐；
        // maxLines 只是护栏（文案本就在 2 行内），写 3 行免得哪天改长就被裁掉。
        minLines = 2,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
      if (note != null) {
        Spacer(Modifier.height(3.dp))
        Text(
          text = note,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** 执行中：阶段 + 进度条 + 当前动作 + 取消 */
@Composable
private fun ProgressPane(progress: ToolProgress, onCancel: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = progress.phase,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "${progress.done} / ${progress.total}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.height(8.dp))
    // ⚠️ Material3 1.4 起 `progress: Float` 重载已废弃，必须传 lambda
    LinearProgressIndicator(
      progress = { progress.fraction },
      modifier = Modifier.fillMaxWidth().height(6.dp),
    )
    Spacer(Modifier.height(10.dp))
    Text(
      text = if (progress.current.isBlank()) "…" else "当前：${progress.current}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    if (progress.failed > 0) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = "已失败 ${progress.failed} 项（不影响其余文件继续处理）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
      progress.lastError?.let {
        Spacer(Modifier.height(4.dp))
        Text(
          text = "最近失败：$it",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
      TextButton(onClick = onCancel) { Text("取消") }
    }
  }
}

/** 结束态：成功数 / 失败数 / 取消 / 出错 */
@Composable
private fun ResultPane(
  progress: ToolProgress,
  onClose: () -> Unit,
  onRetryFailed: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
    // 取局部 val 再判空：对参数属性判空会被智能转换吃掉（`?: ""` 被判「永远取左边」）
    val errorMessage = progress.errorMessage
    val (title, detail) =
      when {
        errorMessage != null -> "执行中断" to errorMessage
        progress.cancelled -> "已取消" to "已完成的改动保留在服务器上，未处理的部分没有动。"
        progress.result != null -> {
          val r = progress.result
          val head = "处理完成"
          val body =
            buildString {
              append("成功 ${r.succeeded} 项")
              if (r.failed > 0) append("，失败 ${r.failed} 项")
              if (r.sweptDirs > 0) append("，顺带清掉 ${r.sweptDirs} 个空目录")
              if (r.firstError != null) append("\n首个错误：${r.firstError}")
            }
          head to body
        }
        else -> "已结束" to ""
      }
    Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    Text(
      text = detail,
      style = MaterialTheme.typography.bodySmall,
      color =
        if (errorMessage != null) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    val failures = progress.result?.failures.orEmpty()
    if (failures.isNotEmpty()) {
      Spacer(Modifier.height(10.dp))
      Text(
        text = "失败明细（可只重试这些）：",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
      )
      Spacer(Modifier.height(4.dp))
      failures.take(5).forEach { failure ->
        Text(
          text = "· ${failure.action.label}${if (failure.error.isNullOrBlank()) "" else "：${failure.error}"}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (failures.size > 5) {
        Text(
          text = "· 其余 ${failures.size - 5} 项略",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
      if (failures.isNotEmpty()) {
        TextButton(onClick = onRetryFailed) { Text("重试失败 ${failures.size} 项") }
        Spacer(Modifier.width(8.dp))
      }
      FilledTonalButton(onClick = onClose) { Text("完成") }
    }
  }
}

/** 每个工具一个图标。名字都对照 material-icons-extended 1.7.8 的 AAR 验过 */
private fun NetworkTool.icon(): ImageVector =
  when (this) {
    NetworkTool.ORGANIZE_BY_TYPE -> Icons.Default.FolderSpecial
    NetworkTool.EXTRACT_AND_ORGANIZE -> Icons.Default.AutoAwesome
    NetworkTool.CLEAN_EMPTY_DIRS -> Icons.Default.CleaningServices
    NetworkTool.CLEAN_EMPTY_FILES -> Icons.Default.DeleteSweep
    NetworkTool.CLEAN_JUNK_FILES -> Icons.Default.AutoDelete
    NetworkTool.ARCHIVE_BY_MONTH -> Icons.Default.CalendarMonth
    NetworkTool.BIG_FILE_RANKING -> Icons.Default.Leaderboard
    NetworkTool.BATCH_RENAME -> Icons.Default.Edit
  }
