package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.emby.LibraryOrdering
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 操作菜单类对话框的最大宽度（M3 AlertDialog 默认上限是 560dp，对纯菜单来说太空） */
val DIALOG_MENU_WIDTH = 320.dp

/**
 * 统一执行「扫描 / 刷新元数据」这类服务器动作，并把结果 Toast 出来。
 *
 * 两个动作在 UI 侧的差别只有文案和具体调用，其它（判空、切线程、成败提示）完全一致，
 * 所以抽成这一个函数，首页与媒体库页共用。
 *
 * 注意这些动作**都是异步任务**：接口一发出去就返回，服务器在后台慢慢跑，
 * 所以成功提示只能说「已通知服务器…」，不能说「已完成」。
 */
fun runEmbyLibraryAction(
  context: Context,
  scope: CoroutineScope,
  server: EmbyServer?,
  itemId: String?,
  name: String,
  okMessage: String,
  action: suspend (EmbyServer, String) -> Result<*>,
) {
  if (server == null || itemId == null) {
    Toast.makeText(context, "无法执行：缺少媒体库信息", Toast.LENGTH_SHORT).show()
    return
  }
  scope.launch {
    val result: Result<Any?> = try {
      action(server, itemId)
    } catch (e: Exception) {
      // 取消不是失败：菜单关闭时界面作用域被取消，协程会在切回主线程时抛
      // CancellationException（LeftCompositionCancellationException），
      // 但请求早就打到服务器上并生效了 —— 这种不能弹「操作失败」，否则就是误报。
      if (e is CancellationException) throw e
      Result.failure<Any?>(e)
    }
    // 只看成败、不看返回值：toggleFavorite 成功时也可能返回 false（表示「现在是未收藏」），
    // 拿返回值当成败判断会把「取消收藏」误报成失败
    val ok = result.isSuccess
    Toast.makeText(
      context,
      if (ok) {
        okMessage
      } else {
        // 失败要把服务器/HTTP 的真实原因说出来（EmbyApiException 里带状态码和 body 片段），
        // 一句「可能需要管理员权限」掩盖了太多情况 —— 权限、404、body 解析都可能
        val reason = result.exceptionOrNull()?.message?.take(120)
        if (reason.isNullOrBlank()) {
          "对「$name」的操作失败，可能需要管理员权限"
        } else {
          "对「$name」的操作失败：$reason"
        }
      },
      Toast.LENGTH_LONG,
    ).show()
  }
}

/**
 * 长按媒体库 / 文件夹弹出的操作框。
 *
 * 之所以要弹一层而不是「长按直接执行」：这两个动作都作用在服务器上且撤不回来 ——
 * 扫描会把服务器拉去读一遍整个目录，刷新元数据会把海报简介全部重刮，
 * 误触一次代价不小，所以中间必须加一道确认。
 *
 * 两个动作的区别（对应 Emby Web 端库菜单里的两项）：
 * - **扫描媒体库**：只重新读目录，新拷进去的文件入库、删掉的清掉，不动已有元数据
 * - **刷新元数据**：重新刮削片名 / 简介 / 演职员 / 图片，可在二级框里选是否强制覆盖
 *
 * @param name 条目名称（库名 / 文件夹名）
 * @param kindLabel 条目类型文案（「媒体库」/「文件夹」），用来拼说明文字
 * @param isPinned 仅首页媒体库卡片传值：null = 这一类不支持置顶（文件夹），此时
 *   置顶与顺序号两项都不显示。已置顶时菜单项文案换成「取消置顶媒体库」——
 *   两个状态合成一项，用户不用在两条永远只有一条可用的菜单里找。
 * @param onTogglePin 点「置顶 / 取消置顶」时回调
 * @param onAdjustOrder 点「调整顺序号」时回调（打开 [EmbyLibraryOrderDialog]）
 */
@Composable
fun EmbyItemActionsDialog(
  name: String,
  kindLabel: String,
  onDismissRequest: () -> Unit,
  onScan: () -> Unit,
  onRefreshMetadata: (replaceAllMetadata: Boolean, replaceAllImages: Boolean) -> Unit,
  isPinned: Boolean? = null,
  onTogglePin: (() -> Unit)? = null,
  onAdjustOrder: (() -> Unit)? = null,
) {
  // 二级框（刷新元数据的选项）是否展开。一级框关掉后这个状态自然随组合一起销毁
  var showMetadataOptions by remember { mutableStateOf(false) }

  if (showMetadataOptions) {
    EmbyRefreshMetadataDialog(
      name = name,
      kindLabel = kindLabel,
      onDismissRequest = onDismissRequest,
      onConfirm = { replaceAllMetadata, replaceAllImages ->
        onRefreshMetadata(replaceAllMetadata, replaceAllImages)
      },
    )
    return
  }

  // 锚定在长按位置、宽度贴合内容的浮动菜单（和媒体菜单同一套观感）
  DropdownMenu(expanded = true, onDismissRequest = onDismissRequest) {
    Text(
      text = name,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
    if (isPinned != null && onTogglePin != null) {
      DropdownMenuItem(
        text = { Text(if (isPinned) "取消置顶$kindLabel" else "置顶$kindLabel") },
        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
        onClick = onTogglePin,
      )
    }
    if (onAdjustOrder != null) {
      DropdownMenuItem(
        text = { Text("调整顺序号") },
        leadingIcon = { Icon(Icons.Default.FormatListNumbered, contentDescription = null) },
        onClick = onAdjustOrder,
      )
    }
    DropdownMenuItem(
      text = { Text("扫描$kindLabel") },
      leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
      onClick = onScan,
    )
    DropdownMenuItem(
      text = { Text("刷新元数据") },
      leadingIcon = { Icon(Icons.Default.Autorenew, contentDescription = null) },
      onClick = { showMetadataOptions = true },
    )
  }
}

/**
 * 长按顶栏 Emby 图标弹出的「媒体库显示 / 隐藏」小菜单。
 *
 * **刻意和 [EmbyItemActionsDialog] 用同一套骨架**（一样是 `DropdownMenu` +
 * 顶部一行小标题 + 若干 `DropdownMenuItem`）：长按媒体库卡片、长按 Emby 图标
 * 弹出来的东西看着得是**同一个家族**，用户才不会以为是两套设计。
 * 差别只有一处 —— 这里每一行左边是**复选框**而不是功能图标。
 *
 * 语义：**勾上 = 首页显示该库，不勾 = 隐藏**。隐藏只是首页那一眼的横滑里
 * 不摆它，库还在服务器上，「调整顺序号」弹窗里也照旧能看到、能排。
 *
 * 顶部两行是批量操作（全部勾选 / 全部取消）：库多的用户先「全部勾选」再挨个
 * 去掉几个，比从「一个不勾」开始逐个点要快得多。
 *
 * 点某一行**不关菜单**：这是一个可以连着勾好几个的清单，关掉再长按一次才够
 * 是反直觉的。菜单只在点空白处（onDismissRequest）时才收起。
 *
 * ## 定位：**吸在屏幕右侧**（用户明确要求，别再"修"它）
 * 这里**刻意**给 `DropdownMenu` 传一个「必然超宽」的 x 偏移（整个屏宽），
 * 逼 M3 把菜单放到窗口最右侧。为什么必须这么绕，得从 M3 的真实算法说起
 * （以下均为 material3 1.5.0-alpha15 的 **javap 实证**，定位器在
 * `androidx.compose.material3.internal.DropdownMenuPositionProvider`，编译自 MenuPosition.kt）：
 *
 * 横向有三个候选，**按序试，第一个「放得下」的胜出**，全都放不下才用最后一个。
 * 「放得下」的判定是 `x ≥ 8dp && x + 菜单宽 ≤ 窗口宽 − 8dp`（`MenuHorizontalMargin`
 * 实证只有 **8dp**，不是想当然的 48dp；垂直边距才是 48dp）。三个候选（LTR）：
 * 1. `startToAnchorStart`：菜单**左**边缘贴锚点左边缘，再加 offset；
 * 2. `endToAnchorEnd`：菜单**右**边缘贴锚点右边缘，再加 offset；
 * 3. 按锚点中心落在窗口左/右半屏二选一：`leftToWindowLeft`（贴窗口左缘 8dp）/
 *    `rightToWindowRight`（贴窗口右缘 8dp，即 x = 窗口宽 − 8dp − 菜单宽）。
 *
 * ⚠️ 曾经有一版**不传 offset**，理由是「240dp 在锚点右边放不下 ⇒ 必然被钳到窗口最右」——
 *    **这个前提是错的**：锚点右侧虽有约 96dp，扣掉 8dp 边距还剩 88dp ≥ 0，
 *    240dp 菜单在 `x = 锚点右缘 − 240dp` 处**放得下**（普通手机屏宽 360~412dp 都成立）
 *    ⇒ 候选 2 `endToAnchorEnd` **永远胜出** ⇒ 菜单右边缘一直贴在图标（= 手指位置），
 *    从来没能吸到屏幕右侧。这就是用户反馈「还是弹在手指触摸的位置」的根因。
 *
 * 现在的做法：`offset.x = 屏宽`，候选 1、2 的 x 都会被顶到 `x + 菜单宽 > 窗口宽 − 8dp`
 * （必然放不下）⇒ 只剩候选 3。锚点是顶栏 actions 里的图标，恒在窗口右半屏
 * ⇒ 命中 `rightToWindowRight` ⇒ **菜单右边缘稳定贴在 `窗口宽 − 8dp`**。
 * 这个结果不依赖锚点精确位置、不依赖屏宽，任何机型一致。
 *
 * ⚠️ 三条红线，动之前先读：
 * 1. **不要**把这个 offset「简化」掉 —— 一去掉就退回 endToAnchorEnd（弹在手指位置）；
 * 2. **不要**改成负偏移：负偏移只会把菜单推到锚点左侧，仍然不是屏幕右侧；
 * 3. 只在 LTR 下强制（RTL 下对齐语义整体翻转，负负得正的道理不成立，故保持默认行为）。
 *
 * [VISIBILITY_MENU_WIDTH] 保持固定只是观感选择（菜单不随库名长短抖动），
 * 定位已不依赖它 —— 库名过长由 Ellipsis 截断。
 *
 * @param expanded 是否展开（由调用方持有状态，长按 Emby 图标时置 true）
 * @param libraries 全部媒体库，**已按当前显示顺序排好**（和首页那一眼的顺序一致，
 *   用户对照着勾才不会找错）
 * @param hiddenIds 当前被隐藏的库 Id 集合
 * @param onToggle 勾 / 去勾某一个库
 * @param onSelectAll 全部勾选（= 全部显示）
 * @param onDeselectAll 全部取消（= 全部隐藏）
 */
@Composable
fun EmbyLibraryVisibilityMenu(
  expanded: Boolean,
  libraries: List<EmbyItem>,
  hiddenIds: Set<String>,
  onToggle: (libraryId: String) -> Unit,
  onSelectAll: () -> Unit,
  onDeselectAll: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  // 强制「吸在屏幕右侧」：x 偏移直接给整个屏宽，M3 的前两个横向候选
  // （贴锚点左边缘 / 贴锚点右边缘）都会被「放不下」检查否掉，只剩
  // rightToWindowRight（贴窗口右缘 − 8dp）。完整推导见 KDoc「定位」一节。
  val pinToRightEdge =
    if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
      DpOffset(x = LocalConfiguration.current.screenWidthDp.dp, y = 0.dp)
    } else {
      DpOffset.Zero
    }
  DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    offset = pinToRightEdge,
  ) {
    Column(modifier = Modifier.width(VISIBILITY_MENU_WIDTH)) {
      Text(
        text = "媒体库显示",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      )
      DropdownMenuItem(
        text = { Text("全部勾选") },
        leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
        onClick = onSelectAll,
      )
      DropdownMenuItem(
        text = { Text("全部取消") },
        leadingIcon = { Icon(Icons.Default.Deselect, contentDescription = null) },
        onClick = onDeselectAll,
      )
      // 和下面的一串库行之间拉一条细线：上面是「批量动作」、下面是「逐个库」，
      // 两类东西不隔开会被读成一串同级勾选项。
      HorizontalDivider()
      libraries
        .mapNotNull { lib -> lib.Id?.let { it to (lib.Name ?: "媒体库") } }
        .forEach { (id, name) ->
          val visible = id !in hiddenIds
          DropdownMenuItem(
            text = {
              Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            // 复选框只做显示：`onCheckedChange = null` 让它不可单独点，
            // 整行的 onClick 才是唯一开关 —— 免得「点框」和「点行」两套状态打架。
            leadingIcon = { Checkbox(checked = visible, onCheckedChange = null) },
            onClick = { onToggle(id) },
          )
        }
    }
  }
}

/**
 * 「媒体库显示」菜单的固定宽度（240dp，约 2/3 个常见手机宽度）。
 *
 * 宽度固定现在**只是观感选择**（一行一个库、长名 Ellipsis 截断，菜单不随库名长短抖动），
 * **不再是定位前提** —— 新机制（offset.x = 整个屏宽）下，无论菜单多宽，
 * 前两个锚点相对候选都必然「放不下」，照样被推到窗口最右。详见
 * [EmbyLibraryVisibilityMenu] 的定位说明。
 *
 * 库名过长由 `Ellipsis` 截断，不会把菜单撑开。
 */
private val VISIBILITY_MENU_WIDTH = 240.dp

/**
 * 「调整顺序号」对话框：把首页媒体库的显示顺序一屏管全。
 *
 * 为什么用「−/+ 步进」而不是让用户填数字：改号是**互换**语义（见
 * [LibraryOrdering.assignNumber]），点一下 ＋ 就等价于「和下面那位换位置」，
 * 结果马上在首页可见；直接填数字反而要用户自己保证不撞号。
 *
 * 置顶的库也在这里出现（放在最前、带「已置顶」标注），
 * 因为它们的序号要等取消置顶后才生效，不给个说法会让人以为改不动。
 *
 * 版式（第三版，头两版都被嫌「丑」）：
 * - 第一版：两段说明堆顶 + 一排裸 `− 3 +`，没有视觉锚点；
 * - 第二版：每行一张灰卡片 + 序号小徽标 + 库名，卡片套卡片显得糊、序号也弱；
 * - **现在**：序号做成**左端一个方章**（`primary` 实心、比正文大一号，整行最强的锚点），
 *   库名跟在右边；`−/+` 用 `FilledTonalIconButton` 给两个明确的按钮形状，
 *   不再是无边的裸图标；容器用**一张** `surfaceVariant` 大圆角卡把整列装起来，
 *   行与行之间只用一条细分隔线 —— 取代「每行一张卡」的层层套娃；
 *   置顶行**不整行染色**，只在名字前钉一枚小图钉 + 一枚淡色「置顶」小标签，
 *   背景与其它行一致，靠小标记区分，视觉噪音小得多。
 */
@Composable
fun EmbyLibraryOrderDialog(
  /** 已经按当前显示顺序排好的媒体库列表 */
  libraries: List<EmbyItem>,
  pinnedIds: List<String>,
  numbers: Map<String, Int>,
  onNumberChange: (libraryId: String, newNumber: Int) -> Unit,
  onDismissRequest: () -> Unit,
) {
  val maxNumber = LibraryOrdering.maxNumber(numbers, libraries.mapNotNull { it.Id })
    .coerceAtLeast(1)
  val rows = libraries.mapNotNull { lib -> lib.Id?.let { lib to it } }

  AlertDialog(
    onDismissRequest = onDismissRequest,
    title = { Text("调整顺序号") },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        // 说明压成一句：细节（互换、置顶优先）在本行内内联到具体行上，不必在顶部全讲一遍
        Text(
          text = "序号越小越靠前；改成已占用的序号时两者互换",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))

        // 容器只有这一张：整列裹在一个圆角面里，行与行之间用细分隔线，
        // 不再每行各套一张卡（套娃是上一版显得脏的主因）
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
          Column(
            modifier = Modifier
              .heightIn(max = 380.dp)
              .verticalScroll(rememberScrollState()),
          ) {
            rows.forEachIndexed { index, (library, id) ->
              val number = numbers[id] ?: 0
              val pinned = id in pinnedIds
              if (index > 0) {
                HorizontalDivider(
                  modifier = Modifier.padding(start = 56.dp),
                  thickness = 0.6.dp,
                  color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
              }
              LibraryOrderRow(
                name = library.Name ?: "媒体库",
                number = number,
                pinned = pinned,
                canDecrease = number > 1,
                canIncrease = number in 1 until maxNumber,
                onDecrease = { onNumberChange(id, number - 1) },
                onIncrease = { onNumberChange(id, number + 1) },
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismissRequest) { Text("完成") }
    },
  )
}

/**
 * 顺序号弹窗里的一行：左端序号方章 + 库名（置顶带图钉与标签）+ 右端 `−/+`。
 *
 * 抽出来只是为了 [EmbyLibraryOrderDialog] 的 `forEachIndexed` 读起来清爽，
 * 没有状态，纯展示 + 两个回调。
 */
@Composable
private fun LibraryOrderRow(
  name: String,
  number: Int,
  pinned: Boolean,
  canDecrease: Boolean,
  canIncrease: Boolean,
  onDecrease: () -> Unit,
  onIncrease: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // 序号方章：整个弹窗里最重的一块 —— 它就是用户在找的那个数字。
    // `primary` 实心 + `onPrimary` 字，深浅主题下都够对比，不硬编码任何颜色。
    Surface(
      shape = RoundedCornerShape(9.dp),
      color = if (number > 0) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
      },
      modifier = Modifier.size(30.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(
          text = if (number > 0) number.toString() else "—",
          style = MaterialTheme.typography.labelLarge,
          color = if (number > 0) {
            MaterialTheme.colorScheme.onPrimary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
    }

    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (pinned) {
          Icon(
            Icons.Default.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
          text = name,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (pinned) {
        Text(
          text = "置顶 · 取消后按序号排",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // `−/+` 做成有形状的 tonal 按钮：比裸图标可点、也比裸图标"整齐"，
    // 两个并排时大小一致、不会显得一重一轻。
    FilledTonalIconButton(
      onClick = onDecrease,
      enabled = canDecrease,
      modifier = Modifier.size(32.dp),
    ) {
      Icon(Icons.Default.Remove, contentDescription = "序号前移", modifier = Modifier.size(16.dp))
    }
    FilledTonalIconButton(
      onClick = onIncrease,
      enabled = canIncrease,
      modifier = Modifier.size(32.dp),
    ) {
      Icon(Icons.Default.Add, contentDescription = "序号后移", modifier = Modifier.size(16.dp))
    }
  }
}

/**
 * 「刷新元数据」的二级选项框：两个复选框 + 确定。
 *
 * 对应 Emby 刷新接口的两个参数：
 * - 覆盖所有元数据 → `ReplaceAllMetadata`（连同已存在的片名 / 简介一起重刮）
 * - 替换所有图片 → `ReplaceAllImages` + `ImageRefreshMode=FullRefresh`（重新下载封面、背景图）
 */
@Composable
fun EmbyRefreshMetadataDialog(
  name: String,
  kindLabel: String,
  onDismissRequest: () -> Unit,
  onConfirm: (replaceAllMetadata: Boolean, replaceAllImages: Boolean) -> Unit,
) {
  // 默认都不勾：Emby 的默认刷新只补缺失字段，勾上才是「连已有的也一起换掉」
  var replaceAllMetadata by remember { mutableStateOf(false) }
  var replaceAllImages by remember { mutableStateOf(false) }

  EmbyActionSheet(
    title = "刷新元数据",
    onDismissRequest = onDismissRequest,
    confirmLabel = "确定",
    onConfirm = { onConfirm(replaceAllMetadata, replaceAllImages) },
  ) {
    Column {
      Text(
        text = name,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "对整${kindLabel}重新刮削元数据，耗时取决于媒体数量",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Spacer(modifier = Modifier.height(12.dp))

      CheckboxRow(
        checked = replaceAllMetadata,
        onCheckedChange = { replaceAllMetadata = it },
        title = "覆盖所有元数据",
        subtitle = "已存在的片名、简介、演职员也会被重新刮削覆盖",
      )
      CheckboxRow(
        checked = replaceAllImages,
        onCheckedChange = { replaceAllImages = it },
        title = "替换所有图片",
        subtitle = "重新下载并替换已有的封面、背景图",
      )
    }
  }
}

/**
 * 操作框里的一个可点击行（图标 + 标题）。
 *
 * 只有一行文字 —— 之前每项底下还挂了一行灰色说明，七项叠起来整个框高得要滚屏，
 * 而这些说明（「重新刮削片名、简介…」）看图标 + 标题已经能猜到，性价比不高。
 */
@Composable
fun ActionRow(
  icon: @Composable () -> Unit,
  title: String,
  onClick: () -> Unit,
  tint: Color = MaterialTheme.colorScheme.primary,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      icon()
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = tint,
        modifier = Modifier.padding(start = 12.dp),
      )
    }
  }
}

/** 复选框整行可点：点文字也能切换，不用非得戳中那个小方框 */
@Composable
private fun CheckboxRow(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  title: String,
  subtitle: String,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onCheckedChange(!checked) }
      .padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    Column(modifier = Modifier.padding(start = 8.dp)) {
      Text(text = title, style = MaterialTheme.typography.bodyMedium)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
