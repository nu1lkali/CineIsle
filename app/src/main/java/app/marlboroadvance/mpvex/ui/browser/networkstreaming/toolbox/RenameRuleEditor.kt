package app.marlboroadvance.mpvex.ui.browser.networkstreaming.toolbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 批量重命名的两步流程：先定规则，再确认清单（前 8 项样例 → 扫全目录出正式清单）。 */
private enum class RenameStep(
  val number: Int,
  val label: String,
) {
  RULES(1, "设置规则"),
  PREVIEW(2, "确认清单"),
}

/**
 * 批量重命名的「规则链」页 —— **不是另弹一个 Dialog**，而是整理工具箱抽屉里的
 * 一个状态页（与 ToolListPane / ProgressPane / ResultPane 并列）。
 *
 * 交互参考 flut-renamer 的 `rules_page.dart`，但视觉完全沿用本项目工具箱：
 * - 卡片 = 同一套 `surfaceContainerHigh` + 16dp 圆角；
 * - 按钮只用 `TextButton` / `FilledTonalButton` / `Button`，参数编辑只用现成的 `AlertDialog`；
 * - 颜色一律取自 `MaterialTheme.colorScheme`，无硬编码色；
 * - 抽屉不关、不跳新窗口，返回键沿「清单 → 规则 → 工具列表」逐级回。
 *
 * ## 分步多页面（v205）
 *
 * 旧版把「添加规则 / 范围开关 / 随机种子 / 整条规则链 / 8 项样例 / 主按钮」全塞进
 * 一个 `verticalScroll` 里：东西越堆越多，主按钮被埋在最末尾要滚到底才看得见，
 * 而且它是个 `FilledTonalButton`（secondaryContainer 和卡片底色接近），第一眼就像点不了。
 *
 * 现在拆成两页，每页都一屏放得下，整页**不滚**：
 *
 * ```
 * 第 1 步 设置规则：添加 / 范围 / 随机串设置 / 规则链（限高自滚，两行一张卡）
 * 第 2 步 确认清单：改动统计 / 前 8 项「修改前 / 修改后」上下两行（限高自滚）
 * ```
 *
 * 底部是一条**常驻**的主行动条：第 1 步「下一步：确认清单」、第 2 步「扫描全目录生成清单」，
 * 都是 primary 填充的大按钮，永远点得到。两页各自只有一小块列表需要局部滚动，
 * 不做整页嵌套滚动（两层 `verticalScroll` 会互抢手势，反而更涩）。
 *
 * ⚠️ 「样例」与「清单」是同一件事的两半，别再命名成两个都叫预览的东西：
 * 第 2 步页内的 8 项只是本地样例（不联网），点底部按钮**才**扫全目录，
 * 扫完进入工具箱原有的结果页 —— 那页才叫清单、才有「执行重命名」。
 *
 * 规则按顺序依次作用（对应它的 ReorderableListView），每次增删改立刻
 * [onBookChange] 落盘（对应它的 `saveRules`），[RenameRuleBook.lastAddedType]
 * 记住上次添加的类型（对应它的 `Shared.ruleName`）—— 这就是「规则能够记忆」。
 *
 * 预览与规划器共用 [computeRenameBatch]（规则链 + ordinal + 撞名避让都在里面），
 * 且两边喂同一份目录名单、同一个顺序，所以「这里看到的 → 确认清单里的」完全一致。
 * 真正执行时若目录里新出现了同名文件，执行器会再让一次名加 (1) 后缀以免覆盖，
 * 这是防并发上传的兜底，不是另一套算法。
 *
 * ⚠️ 这里仍然不直接改服务器：点「扫描全目录生成清单」只是交给 ViewModel 扫描、
 * 出正式清单，用户还要在确认框里再点一次「确认执行」。AList 没有回收站。
 */
@Composable
fun RenameRulePane(
  book: RenameRuleBook,
  takenNames: List<String>,
  targetNames: List<String>,
  onBookChange: (RenameRuleBook) -> Unit,
  onPreview: () -> Unit,
  onBack: () -> Unit,
) {
  var editingIndex by remember { mutableStateOf<Int?>(null) }
  // 待新增的规则类型。**先不落盘**：点「添加规则」只记住类型并打开参数框，
  // 点「取消」就整个丢掉。以前的写法是「先 append 空规则、再编辑最后一条」，
  // 于是取消只关窗、那条空规则早就进了链 —— 用户看到的就是「点取消还新建一条」。
  var pendingNewType by remember { mutableStateOf<RenameRuleType?>(null) }
  var addMenuOpen by remember { mutableStateOf(false) }
  // 随机串是进阶能力，默认收起：常驻会白占两行高度
  var advancedOpen by remember { mutableStateOf(false) }
  var step by remember { mutableStateOf(RenameStep.RULES) }

  /** 加一条规则：只记住类型并打开参数框，**点确定时才真正入链** */
  fun add(type: RenameRuleType) {
    pendingNewType = type
    editingIndex = null
  }

  // ⭐ 必须 remember：computeRenameBatch 会把 takenNames 整份灌进 HashSet 再逐条跑
  // 规则链（含正则）。以前它直接写在可滚动 Column 的 content 里，抽屉里任何一次重组
  // （滚动、拨开关、编辑规则）都会把大目录整个重算一遍，这就是滚动掉帧的主因。
  // 只有名单 / 规则链 / 种子真的变了才重算。
  val preview =
    remember(takenNames, targetNames, book.rules, book.randomSeed) {
      computeRenameBatch(takenNames, targetNames.take(8), book.rules, book.randomSeed)
    }
  val previewFraction =
    remember(preview, takenNames) {
      if (takenNames.isEmpty() || preview.isEmpty()) 0f
      else preview.count { it.second != null }.toFloat() / preview.size
    }
  val changedCount = remember(preview) { preview.count { it.second != null } }
  val ruleCount = book.rules.size

  /** 返回键：清单页先回规则页，规则页才回工具列表 */
  fun back() {
    if (step == RenameStep.PREVIEW) step = RenameStep.RULES else onBack()
  }

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp),
  ) {
    // ── 固定步骤条：一眼知道在第几步、总共几步 ──
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "第 ${step.number} / 2 步 · ${step.label}",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "$ruleCount 条规则",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
      progress = { step.number / 2f },
      modifier = Modifier.fillMaxWidth().height(3.dp),
    )
    Spacer(Modifier.height(10.dp))

    when (step) {
      // ── 第 1 步：设置规则 ──────────────────────────────────────────────
      RenameStep.RULES -> {
        // 「添加规则」直接复用上次的类型（lastAddedType），旁边箭头展开七选一菜单；
        // 菜单项不额外加图标，只在文字上标出「上次使用」，与项目菜单口径一致。
        Row(verticalAlignment = Alignment.CenterVertically) {
          val lastType =
            runCatching { RenameRuleType.valueOf(book.lastAddedType) }
              .getOrDefault(RenameRuleType.REPLACE)
          FilledTonalButton(onClick = { add(lastType) }) {
            Text("添加规则 · ${lastType.label}")
          }
          IconButton(onClick = { addMenuOpen = true }) {
            Icon(Icons.Default.ArrowDropDown, contentDescription = "选择规则类型")
          }
          DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
            RenameRuleType.entries
              .sortedBy { if (it.name == book.lastAddedType) 0 else 1 }
              .forEach { type ->
                DropdownMenuItem(
                  text = {
                    Text(type.label + if (type.name == book.lastAddedType) "（上次使用）" else "")
                  },
                  onClick = { addMenuOpen = false; add(type) },
                )
              }
          }
          Spacer(Modifier.weight(1f))
          Text("包含文件夹", style = MaterialTheme.typography.bodySmall)
          Spacer(Modifier.width(4.dp))
          Switch(
            checked = book.includeDirs,
            onCheckedChange = { onBookChange(book.copy(includeDirs = it)) },
          )
        }

        Spacer(Modifier.height(6.dp))

        // 随机串标记是所有「输出文本」共用的能力，种子放规则本级：改一次整批换一批，
        // 预览与正式清单都走同一个 computeRenameBatch，改完两边同步变。
        if (advancedOpen) {
          Text(
            text =
              "随机串 {RandomString:N}（N=1~32，小写十六进制）可写在「插入 / 替换为 / 编号前后缀」里；" +
                "同一文件每次算出的串相同，改种子可换一批。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          IntField("随机种子（换一批随机串）", book.randomSeed) {
            onBookChange(book.copy(randomSeed = it))
          }
          TextButton(onClick = { advancedOpen = false }) { Text("收起随机串设置") }
        } else {
          TextButton(onClick = { advancedOpen = true }) { Text("随机串设置 · {RandomString:N}") }
        }

        Spacer(Modifier.height(10.dp))

        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "规则链 · $ruleCount 条",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
          )
          Text(
            text = "按顺序生效",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(4.dp))
        // 只有规则链这一小块需要滚；整页不滚，所以主按钮永远在底部。
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(max = 250.dp)
              .verticalScroll(rememberScrollState()),
        ) {
          if (book.rules.isEmpty()) {
            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              shape = RoundedCornerShape(16.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = "还没有规则。点上方「添加规则」：替换、删除、插入、递增编号、重排、大小写、截断。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
              )
            }
          } else {
            book.rules.forEachIndexed { index, rule ->
              RuleCard(
                index = index,
                rule = rule,
                isFirst = index == 0,
                isLast = index == book.rules.lastIndex,
                onMoveUp = {
                  val next = book.rules.toMutableList()
                  next.add(index - 1, next.removeAt(index))
                  onBookChange(book.copy(rules = next))
                },
                onMoveDown = {
                  val next = book.rules.toMutableList()
                  next.add(index + 1, next.removeAt(index))
                  onBookChange(book.copy(rules = next))
                },
                onDelete = {
                  val next = book.rules.toMutableList()
                  next.removeAt(index)
                  onBookChange(book.copy(rules = next))
                  editingIndex = null
                },
                onEdit = { editingIndex = index },
              )
            }
          }
        }
      }

      // ── 第 2 步：确认清单（页内先给前 8 项样例）────────────────────────
      RenameStep.PREVIEW -> {
        // 与规划器共用同一个纯函数：ordinal 基准、撞名避让的演化完全一致，预览才不会骗人。
        // 只算前 8 项也不影响一致性 —— 前面条目的 taken 演化与规划器全量遍历时逐帧相同。
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "样例 · 前 8 项",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
          )
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = "改动 $changedCount / ${preview.size}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
          progress = { previewFraction },
          modifier = Modifier.fillMaxWidth().height(4.dp),
        )
        Spacer(Modifier.height(6.dp))
        // 一条一块「修改前 / 修改后」上下两行，不再把 old → new 挤成一坨文字。
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(max = 300.dp)
              .verticalScroll(rememberScrollState()),
        ) {
          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column {
              if (preview.isEmpty()) {
                Text(
                  "当前目录没有文件",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                )
              } else {
                preview.forEachIndexed { index, (old, new) ->
                  if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                  }
                  RenamePreviewRow(old = old, new = new)
                }
              }
            }
          }
        }
        Spacer(Modifier.height(6.dp))
        Text(
          text = "以上只是当前目录前 8 项的样例，不联网。点下方按钮才会扫全目录、生成正式清单。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Spacer(Modifier.height(10.dp))

    // ── 固定主行动条：整页唯一一块 primary 填充，常驻底部，不用滚到最下面 ──
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(10.dp))
    Text(
      text =
        when {
          ruleCount == 0 -> "先添加一条规则，才能进入确认清单"
          step == RenameStep.PREVIEW -> "确认无误后生成正式清单；AList 没有回收站，确认执行后不可撤销"
          else -> "已设 $ruleCount 条规则 · 按顺序依次作用在当前目录这一层"
        },
      style = MaterialTheme.typography.labelSmall,
      color =
        if (ruleCount == 0) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      TextButton(onClick = { back() }) {
        Text(if (step == RenameStep.PREVIEW) "上一步" else "返回")
      }
      Spacer(Modifier.width(8.dp))
      Button(
        onClick = {
          if (step == RenameStep.RULES) {
            step = RenameStep.PREVIEW
          } else {
            onBookChange(book)
            editingIndex = null
            onPreview()
          }
        },
        enabled = ruleCount > 0,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.weight(1f).height(48.dp),
      ) {
        Text(
          text = if (step == RenameStep.RULES) "下一步：确认清单" else "扫描全目录生成清单",
          style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
          imageVector = Icons.Default.ArrowForward,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
      }
    }
    Spacer(Modifier.height(4.dp))
  }

  // ── 单条规则的参数编辑：沿用工具箱确认清单那个 AlertDialog，不另起窗口 ──
  // 两种来源：pendingNewType（新增，确定才入链）与 editingIndex（改已有，原地覆盖）。
  val newType = pendingNewType
  val index = editingIndex
  val draftRule = remember(newType) { newType?.blank() }
  when {
    newType != null && draftRule != null ->
      RuleEditDialog(
        rule = draftRule,
        onDismiss = { pendingNewType = null },
        onSave = { updated ->
          onBookChange(book.copy(rules = book.rules + updated, lastAddedType = newType.name))
          pendingNewType = null
        },
      )

    index != null && index in book.rules.indices ->
      RuleEditDialog(
        rule = book.rules[index],
        onDismiss = { editingIndex = null },
        onSave = { updated ->
          val next = book.rules.toMutableList()
          next[index] = updated
          onBookChange(book.copy(rules = next))
          editingIndex = null
        },
      )
  }
}

/**
 * 规则链里的一张卡：**两行**。
 * 第一行只放序号 + 规则名称（名称独占整行，不再被四个操作按钮挤成一坨）；
 * 第二行放步数说明与上移 / 下移 / 删除 / 编辑。
 */
@Composable
private fun RuleCard(
  index: Int,
  rule: RenameRule,
  isFirst: Boolean,
  isLast: Boolean,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  onDelete: () -> Unit,
  onEdit: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
  ) {
    Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "${index + 1}.",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.width(24.dp),
        )
        Text(
          text = rule.describe(),
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      }
      Spacer(Modifier.height(6.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "第 ${index + 1} 步生效",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        IconButton(enabled = !isFirst, onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
          Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = "上移",
            modifier = Modifier.size(18.dp),
          )
        }
        IconButton(enabled = !isLast, onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
          Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = "下移",
            modifier = Modifier.size(18.dp),
          )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
          Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = "删除",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
          )
        }
        TextButton(onClick = onEdit) { Text("编辑") }
      }
    }
  }
}

/**
 * 清单里的一条：**上下两行**。
 *
 * 第一行「修改前」、第二行「修改后」。标签定宽 42dp，两行的文件名左缘对齐成一条竖线；
 * 文件名拿剩余整行宽度，且允许换到第 2 行。
 * 以前是左右双栏挤一行（中间还塞箭头），长文件名两边各占半行宽 ⇒ 必然被截成省略号。
 */
@Composable
private fun RenamePreviewRow(
  old: String,
  new: String?,
) {
  val changed = new != null
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
    PreviewLine(
      label = "修改前",
      text = old,
      labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
      textColor = MaterialTheme.colorScheme.onSurfaceVariant,
      emphasis = false,
    )
    Spacer(Modifier.height(3.dp))
    PreviewLine(
      label = if (changed) "修改后" else "未改动",
      text = new ?: "保持原名",
      labelColor =
        if (changed) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
      textColor =
        if (changed) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
      emphasis = changed,
    )
  }
}

/** 清单里的一行：小标签 + 文件名。标签定宽，文件名最多两行，超长才省略。 */
@Composable
private fun PreviewLine(
  label: String,
  text: String,
  labelColor: Color,
  textColor: Color,
  emphasis: Boolean,
) {
  Row(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = labelColor,
      modifier = Modifier.width(42.dp).padding(top = 2.dp),
    )
    Text(
      text = text,
      style =
        if (emphasis) MaterialTheme.typography.bodyMedium
        else MaterialTheme.typography.bodySmall,
      fontWeight = if (emphasis) FontWeight.Medium else FontWeight.Normal,
      color = textColor,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

/** 按规则类型展示对应参数；字段与 flut-renamer 的各 *_dialog.dart 一一对应 */
@Composable
private fun RuleEditDialog(
  rule: RenameRule,
  onSave: (RenameRule) -> Unit,
  onDismiss: () -> Unit,
) {
  var draft by remember(rule) { mutableStateOf(rule) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("编辑规则参数") },
    text = {
      Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        when (val r = draft) {
          is RuleReplace -> {
            Field("查找", r.target) { draft = r.copy(target = it) }
            Field("替换为", r.replacement) { draft = r.copy(replacement = it) }
            // 「替换为」也是输出文本：配合下面的替换次数（正数前 N 处 / 负数后 N 处），
            // 能把前 n 处 / 后 n 处各换成一个不同的随机串。包成圆角按钮，别再是裸文字。
            FilledTonalButton(
              onClick = { draft = r.copy(replacement = r.replacement + "{RandomString:8}") },
              shape = RoundedCornerShape(12.dp),
            ) {
              Text("插入 {RandomString:8}")
            }
            Text(
              text =
                "「替换为」里也能写 {RandomString:N}；配合下面的替换次数（正数前 N 处、负数后 N 处），" +
                  "多处会各得一个不同的随机串。",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 2.dp),
            )
            IntField("替换次数（0 为全部，负数从末尾数）", r.limit) { draft = r.copy(limit = it) }
            SwitchRow("区分大小写", r.caseSensitive) { draft = r.copy(caseSensitive = it) }
            SwitchRow("使用正则（支持 \\1 引用捕获组）", r.isRegex) { draft = r.copy(isRegex = it) }
            SwitchRow("不动扩展名", r.ignoreExtension) { draft = r.copy(ignoreExtension = it) }
          }

          is RuleRemove -> {
            Field("删除内容", r.target) { draft = r.copy(target = it) }
            IntField("删除次数（0 为全部，负数从末尾数）", r.limit) { draft = r.copy(limit = it) }
            SwitchRow("区分大小写", r.caseSensitive) { draft = r.copy(caseSensitive = it) }
            SwitchRow("使用正则", r.isRegex) { draft = r.copy(isRegex = it) }
            SwitchRow("不动扩展名", r.ignoreExtension) { draft = r.copy(ignoreExtension = it) }
          }

          is RuleInsert -> {
            Field("插入文本", r.text) { draft = r.copy(text = it) }
            // 对齐 flut-renamer 的「插入随机字符串」：它是 Insert 文本里的标记，不是独立规则。
            // 用带圆角的 FilledTonalButton 内联插入，不另弹一层长度 Dialog。
            FilledTonalButton(
              onClick = { draft = r.copy(text = r.text + "{RandomString:8}") },
              shape = RoundedCornerShape(12.dp),
            ) {
              Text("插入 {RandomString:8}")
            }
            Text(
              text =
                "可写 {RandomString}（8 位）或 {RandomString:N}（N=1~32，小写十六进制）。" +
                  "同一文件每次算出的串相同，换一批请改规则页的「随机种子」。",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 2.dp),
            )
            IntField("插入位置（第几个字符前）", r.index) { draft = r.copy(index = it) }
            SwitchRow("位置从末尾数", r.fromEnd) { draft = r.copy(fromEnd = it) }
            SwitchRow("不动扩展名", r.ignoreExtension) { draft = r.copy(ignoreExtension = it) }
          }

          is RuleIncrement -> {
            Field("前缀", r.prefix) { draft = r.copy(prefix = it) }
            Field("后缀", r.suffix) { draft = r.copy(suffix = it) }
            IntField("起始编号", r.start) { draft = r.copy(start = it) }
            IntField("步长", r.step) { draft = r.copy(step = it.coerceAtLeast(1)) }
            IntField("补零位数", r.minDigits) { draft = r.copy(minDigits = it.coerceIn(0, 10)) }
            SwitchRow("省略前缀与编号之间的连字符", r.omitDash) { draft = r.copy(omitDash = it) }
            EnumPicker(
              label = "方式",
              selected = r.mode.name,
              options =
                IncrementMode.entries.map {
                  it.name to if (it == IncrementMode.REPLACE) "替换整个主名" else "插入原名指定位置"
                },
            ) { picked -> draft = r.copy(mode = IncrementMode.valueOf(picked)) }
            if (r.mode == IncrementMode.INSERT) {
              IntField("插入位置", r.insertIndex) { draft = r.copy(insertIndex = it) }
              SwitchRow("位置从末尾数", r.fromEnd) { draft = r.copy(fromEnd = it) }
            }
            SwitchRow("不动扩展名", r.ignoreExtension) { draft = r.copy(ignoreExtension = it) }
          }

          is RuleRearrange -> {
            Field("分隔符（留空按字符切）", r.delimiter) { draft = r.copy(delimiter = it) }
            Field("段顺序（从 1 起，逗号分隔，如 2,1,3）", r.order.joinToString(",")) { raw ->
              draft =
                r.copy(
                  order = raw.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 },
                )
            }
            SwitchRow("不动扩展名", r.ignoreExtension) { draft = r.copy(ignoreExtension = it) }
          }

          is RuleTransliterate -> {
            EnumPicker(
              label = "转换",
              selected = r.mode.name,
              options = CaseMode.entries.map { it.name to if (it == CaseMode.UPPER) "转大写" else "转小写" },
            ) { picked -> draft = r.copy(mode = CaseMode.valueOf(picked)) }
            SwitchRow("不动扩展名", r.ignoreExtension) { draft = r.copy(ignoreExtension = it) }
          }

          is RuleTruncate -> {
            IntField("位置 1", r.index1) { draft = r.copy(index1 = it) }
            IntField("位置 2", r.index2) { draft = r.copy(index2 = it) }
            SwitchRow("位置 1 从末尾数", r.i1FromEnd) { draft = r.copy(i1FromEnd = it) }
            SwitchRow("位置 2 从末尾数", r.i2FromEnd) { draft = r.copy(i2FromEnd = it) }
            SwitchRow("只保留两位置之间（关闭则挖掉这段）", r.keepBetween) { draft = r.copy(keepBetween = it) }
            SwitchRow("不动扩展名", r.ignoreExtension) { draft = r.copy(ignoreExtension = it) }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("保存") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = onValue,
    label = { Text(label) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
  )
}

@Composable
private fun IntField(label: String, value: Int, onValue: (Int) -> Unit) {
  OutlinedTextField(
    value = value.toString(),
    onValueChange = { onValue(it.toIntOrNull() ?: 0) },
    label = { Text(label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
  )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onChecked)
  }
}

@Composable
private fun EnumPicker(
  label: String,
  selected: String,
  options: List<Pair<String, String>>,
  onPick: (String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
    Text("$label：", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { open = true }) {
      Text(options.firstOrNull { it.first == selected }?.second ?: selected)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      options.forEach { (key, text) ->
        DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) })
      }
    }
  }
}
