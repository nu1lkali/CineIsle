package app.marlboroadvance.mpvex.ui.browser.dislike

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.dislike.DislikeMark
import app.marlboroadvance.mpvex.domain.dislike.DislikeRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImage
import app.marlboroadvance.mpvex.ui.browser.states.EmptyState
import app.marlboroadvance.mpvex.ui.player.DislikeRemarkDialog
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 「不喜欢视频管理」页。
 *
 * ## 数据来源
 *
 * 订阅 [DislikeRepository.all] —— 那**就是内存快照本身**（不是数据库的 Flow）。
 * 所以本页与播放器、详情页共享同一份真相：任何一处标记/取消，这里立刻变，
 * 不需要"标记完发广播通知管理页刷新"这类代码（那种代码一旦漏一处就不同步）。
 *
 * ## 行内滑动
 *
 * 每行左滑露出「取消标记 / 删除」。两个动作**颜色不同**（蓝 / 红），
 * 因为这是破坏性差异：取消标记可逆，删除不可逆。
 *
 * ## 播放
 *
 * 单击行 ⇒ 直接调播放器播放。
 * ⚠️ 播放需要 `serverId`（选服务器、拼地址），而本表**按 serverId 存了**，
 * 所以要重新查一次服务器配置。查不到（服务器已删除）就提示而不是崩。
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun DislikeManageContent(onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val repository = koinInject<DislikeRepository>()
  val embyRepository = koinInject<EmbyRepository>()
  val allMarks by repository.all.collectAsState()

  // ⭐ 列表只显示「当前仍生效」的标记。
  // 「取消标记」= **从这份列表里消失**（记录与原因其实还留着，下次重新标记会自动带出上次的理由）；
  // 「删除」= 连记录带原因一起忘掉。两者对用户的差别是：前者还能"后悔"，后者不能。
  // ⚠️ 别改成"留着并降低透明度" —— 那会让用户以为取消没生效（条目还在、只是变淡了）。
  val marks = allMarks.filter { it.active }

  // ── 片名自愈 ──
  //
  // 播放器里标记时手上**只有 serverId + itemId**（播放页不持有片名与封面），
  // 存下来的记录 `title` 是空串 ⇒ 管理页显示「（无标题）」、缩略图也是空的。
  // 这类**历史脏数据不会自己长出来**，只能回服务器补一次。
  //
  // ⚠️ 试过的条目要记住：查不到的（片子已从库里删了 / 服务器下线）永远查不到，
  //   每次重组都重查一遍只是白耗流量。这里改的是集合本身、**不触发重组**，
  //   正是我们要的"只试一次"。
  val metaRepairTried = remember { mutableSetOf<String>() }
  LaunchedEffect(marks) {
    val missing =
      marks.filter {
        it.title.isBlank() && it.key.serverId > 0L && it.rowId() !in metaRepairTried
      }
    if (missing.isEmpty()) return@LaunchedEffect
    missing.forEach { metaRepairTried.add(it.rowId()) }
    scope.launch {
      missing.forEach { mark ->
        val server =
          runCatching { embyRepository.getServer(mark.key.serverId) }.getOrNull()
            ?: return@forEach
        if (!server.isLoggedIn) return@forEach
        val item =
          runCatching { embyRepository.getItem(server, mark.key.itemId) }.getOrNull()
            ?: return@forEach
        val name = item.Name?.takeIf { it.isNotBlank() } ?: return@forEach
        val image =
          item.ImageTags["Primary"]?.let { tag ->
            embyRepository.imageUrl(server, mark.key.itemId, tag = tag, maxWidth = 320)
          }
        // ⚠️ 走 `updateMeta` 而不是 `mark`：补全**不是**一次新的表态，
        //   不能刷新「标记时间」—— 否则列表顺序会跳、时间显示变成"补全的那一刻"。
        repository.updateMeta(mark.key, name, image)
      }
      repository.flushNow()
    }
  }

  // 当前展开（滑动露出按钮）的行。**同一时刻只允许一行展开** ——
  // 两行同时开着会让"点空白处收起"变得不知道该收哪个。
  var revealedKey by remember { mutableStateOf<String?>(null) }

  /** 「全部取消标记」的确认框开关。批量删除走 [pendingServerDelete]（形态不同）。 */
  var pendingBulkUnmark by remember { mutableStateOf(false) }

  /**
   * 单条目的待确认动作。
   *
   * ⚠️ **删除影片是不可逆操作，必须二次确认**（且要求手输「删除」二字，见下方弹窗）——
   *  不能"侧滑出来点一下就没了"。列表本身是横向滑动露按钮的形态，误触概率比
   *  普通按钮高一档（滑动过程中手指停在按钮上松开，就成了点击），更要拦一道。
   */
  var pendingRow by remember { mutableStateOf<RowAction?>(null) }

  /**
   * 批量「删除影片」的待确认目标。
   *
   * 与 [pendingBulkUnmark]（本地操作）分开：这个是**真的删服务器上的文件**，
   * 确认框要列出视频名逐个点名，让用户看清"到底要删哪几个"。
   */
  var pendingServerDelete by remember { mutableStateOf<List<DislikeMark>?>(null) }

  /**
   * 服务端删除的进度（已完成 / 总数）。null 表示没有在跑。
   *
   * 删除一批可能要几十秒，必须让用户看到「已删除 3/12」——
   * 否则界面一动不动，用户以为卡死了会去强杀进程。
   */
  var deleteProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

  /**
   * 单击播放。
   *
   * ⚠️ **不能**写成 `LaunchedEffect(playingKey)` + effect 里 `playingKey = null`：
   *  置 null 会立刻触发重组，key 一变**旧协程就被取消** ——
   *  而后面那句 `playFromManageScreen` 是要查服务器的 suspend 调用，
   *  根本轮不到跑完。表现就是「点了完全没反应」，而且**没有任何报错**，极难查。
   *
   *  直接拿 [scope] 起一次一次性协程即可；防重复点击用一个布尔量就够
   *  （连续点两下会开两个播放器，那比"没反应"更烦人）。
   */
  var launchingPlayback by remember { mutableStateOf(false) }
  val launchPlayback: (DislikeMark) -> Unit = { mark ->
    if (!launchingPlayback) {
      launchingPlayback = true
      scope.launch {
        try {
          playFromManageScreen(context, mark)
        } finally {
          launchingPlayback = false
        }
      }
    }
  }

  /** 长按编辑备注的目标；非 null 时弹输入框。 */
  var remarkTarget by remember { mutableStateOf<DislikeMark?>(null) }

  /**
   * 执行「删除影片」：调服务器接口**物理删除**（媒体库 + 磁盘文件），逐条串行 + 进度。
   *
   * ## 为什么删完还要清本地记录
   *
   * 片子在服务器上已经不存在了，本机这条「不喜欢」记录还留着就是一条查不到内容的
   * 死记录（点击播放必然失败）。所以删服务器成功后**顺带把本地记录也删掉**，
   * 列表随之消失。
   *
   * ## 失败处理
   *
   * 单条失败（最常见是**账号没有管理员权限** → 403）不该中断整批，
   * 也不该把本地记录删掉（片还在服务器上，记录得留着）。
   * 最后用 Toast 汇总「成功 N / 失败 M」并在全败时带上首个失败原因 ——
   * 含糊地说一句"完成"是最坑人的做法。
   */
  val performServerDelete: (List<DislikeMark>) -> Unit = { targets ->
    if (deleteProgress == null && targets.isNotEmpty()) {
      scope.launch {
        deleteProgress = 0 to targets.size
        // 按 serverId 分组：一次删除只针对一台服务器，但列表里可能混着多台。
        // 逐条删时每条各自查它所属的服务器即可。
        var ok = 0
        var failed = 0
        var firstError: String? = null
        val succeeded = mutableListOf<DislikeMark>()
        targets.forEachIndexed { index, mark ->
          val server =
            runCatching { embyRepository.getServer(mark.key.serverId) }.getOrNull()
          if (server == null || !server.isLoggedIn) {
            failed++
            if (firstError == null) firstError = "找不到对应的服务器（可能已删除）"
          } else {
            val outcome = runCatching {
              embyRepository.deleteItem(server, mark.key.itemId)
            }
            if (outcome.isSuccess) {
              ok++
              succeeded += mark
            } else {
              failed++
              if (firstError == null) {
                firstError = outcome.exceptionOrNull()?.message
              }
            }
          }
          deleteProgress = index + 1 to targets.size
        }
        // 服务器确实删掉的，本地记录一并清掉（否则留下查不到内容的死记录）
        if (succeeded.isNotEmpty()) {
          repository.deleteAll(succeeded.map { it.key })
          repository.flushNow()
        }
        deleteProgress = null
        Toast.makeText(
            context,
            buildString {
              append("已删除 $ok 个")
              if (failed > 0) {
                append("，$failed 个失败")
                firstError?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
              }
            },
            Toast.LENGTH_LONG,
          )
          .show()
      }
    }
  }

  // ★ 空列表时把展开态清掉：最后一行被删掉后不该还留着"展开中"的悬空状态
  LaunchedEffect(marks.size) {
    if (revealedKey != null && marks.none { it.rowId() == revealedKey }) {
      revealedKey = null
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("不喜欢视频管理") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
        actions = {
          if (marks.isNotEmpty()) {
            // ⚠️ 中文按钮，两项并排在窄屏会挤（见 ui-patterns 的 Row 压缩教训）：
            // 这里用「文字按钮 + 图标」且给足 maxLines，让它挤不下时换行而不是裁字。
            TextButton(
              onClick = { pendingBulkUnmark = true },
              modifier = Modifier.padding(horizontal = 4.dp),
            ) { Text("全部取消", maxLines = 1) }
            TextButton(
              // 「全部取消」是本地操作（普通确认框）；
              // 「全部删除」是**服务器物理删除**（高危确认框，要手输「删除」）。
              onClick = { pendingServerDelete = marks },
              modifier = Modifier.padding(horizontal = 4.dp),
            ) {
              Text("全部删除", maxLines = 1, color = MaterialTheme.colorScheme.error)
            }
          }
        },
      )
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      if (marks.isEmpty()) {        EmptyState(
          icon = Icons.Outlined.Block,
          title = "还没有标记过不喜欢的视频",
          // ⚠️ **不要在文案里写死 `\n` 断行**。
          //  原先写的是「…控制栏上，\n点一下…」，加上 EmptyState 左右各 48dp 的内边距后，
          //  第二行正好被挤到只剩一个「点」字 —— 变成三行、中间孤零零一个字。
          //  断行位置取决于屏幕宽度与系统字号，写死必然对不齐；
          //  交给系统按容器宽度自动换行、并把句子拆短，才是稳的。
          message = "在详情页的「更多」菜单里，或播放器的控制栏上，点一下「不喜欢」就行。",
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        // 点空白处收起当前展开的行
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .background(Color.Transparent)
              .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
              ) { revealedKey = null },
        )
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(items = marks, key = { it.rowId() }) { mark ->
            DislikeRow(
              mark = mark,
              revealed = revealedKey == mark.rowId(),
              onRevealChange = { open -> revealedKey = if (open) mark.rowId() else null },
              onClick = { launchPlayback(mark) },
              onLongClick = { remarkTarget = mark },
              // ⚠️ 这里**不直接执行**，只记下"要对哪条做什么"，交给下面统一的
              //   确认弹窗去问一次。理由见 [pendingRow] 的注释：删除不可逆，
              //   而侧滑露按钮这种形态本身就容易误触。
              onUnmark = {
                revealedKey = null
                pendingRow = RowAction(mark, RowActionKind.UNMARK)
              },
              onDelete = {
                revealedKey = null
                pendingRow = RowAction(mark, RowActionKind.DELETE)
              },
            )
          }
        }
      }

      // ── 删除进度遮罩 ──
      //
      // 删除一批可能要几十秒（逐条串行）。没有这个遮罩，界面看起来毫无反应，
      // 用户会以为卡死而去强杀进程 —— 那可能让删到一半的批次更难追溯。
      // 遮罩同时**挡住列表交互**，避免删除过程中用户又去点别的条目。
      deleteProgress?.let { (done, total) ->
        Box(
          modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
          contentAlignment = Alignment.Center,
        ) {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
          ) {
            Column(
              modifier = Modifier.padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator()
              Spacer(Modifier.height(16.dp))
              Text("正在删除 $done/$total", style = MaterialTheme.typography.titleMedium)
              Text(
                "删除的是服务器上的源文件，请勿退出",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
              )
            }
          }
        }
      }
    }
  }

  // 批量「全部取消标记」的二次确认（纯本地操作，普通确认框即可）
  if (pendingBulkUnmark) {
    ConfirmDialog(
      title = "全部取消标记？",
      subtitle =
        "将取消全部 ${marks.size} 条不喜欢标记，它们会从这个列表里消失。\n" +
          "记录与原因会保留，之后重新标记时可自动带出。",
      onConfirm = {
        scope.launch {
          // ⚠️ 传**全量**而不是过滤后的列表：过滤列表只含当前生效的，
          //   用它会把"已取消但记录还在"的那些漏掉，状态就反了。
          repository.unmarkAll(allMarks.map { it.key })
          repository.flushNow()
        }
        Toast.makeText(context, "已全部取消标记", Toast.LENGTH_SHORT).show()
        pendingBulkUnmark = false
      },
      onCancel = { pendingBulkUnmark = false },
    )
  }

  // 批量「全部删除影片」——**物理删除服务器上的文件**，高危确认框（要手输「删除」）
  pendingServerDelete?.let { targets ->
    DangerConfirmDialog(
      title = "删除这 ${targets.size} 个视频文件？",
      warning =
        "将从 Emby 媒体库中移除，并删除服务器磁盘上的源文件。\n" +
          "此操作不可恢复，请务必确认。",
      itemLines = targets.map { it.title.ifBlank { "（无标题）" } },
      onConfirm = {
        pendingServerDelete = null
        performServerDelete(targets)
      },
      onDismiss = { pendingServerDelete = null },
    )
  }

  // ── 单条确认 ──
  //
  // 「取消标记」纯本地、可后悔 ⇒ 普通确认框即可；
  // 「删除影片」会**物理删除服务器上的文件** ⇒ 必须用要求手输「删除」的高危确认框。
  pendingRow?.let { (mark, kind) ->
    val displayName = mark.title.ifBlank { "（无标题）" }
    when (kind) {
      RowActionKind.UNMARK ->
        ConfirmDialog(
          title = "取消这条标记？",
          subtitle = "「$displayName」会从这个列表里消失。\n记录与原因会保留，之后重新标记时可自动带出。",
          onConfirm = {
            scope.launch {
              repository.unmark(mark.key)
              repository.flushNow()
            }
            Toast.makeText(context, "已取消标记", Toast.LENGTH_SHORT).show()
            pendingRow = null
          },
          onCancel = { pendingRow = null },
        )
      RowActionKind.DELETE ->
        DangerConfirmDialog(
          title = "删除这个视频文件？",
          warning =
            "将从 Emby 媒体库中移除，并删除服务器磁盘上的源文件。\n" +
              "此操作不可恢复，请务必确认。",
          itemLines = listOf(displayName),
          onConfirm = {
            pendingRow = null
            performServerDelete(listOf(mark))
          },
          onDismiss = { pendingRow = null },
        )
    }
  }

  // 长按某条 = 编辑备注。复用播放器那颗键用的同一个输入框（文案与交互保持一致）。
  remarkTarget?.let { mark ->
    DislikeRemarkDialog(
      initialValue = mark.reason,
      // 列表里全是 active 的（见上方过滤）⇒ 恒为「修改备注」，不是「标记为不喜欢」。
      // 措辞必须跟着实际会发生的事走，否则用户会以为界面弄错了状态。
      showMarked = true,
      onDismiss = { remarkTarget = null },
      onConfirm = { text ->
        scope.launch {
          // ⚠️ 走 `updateMeta` 而不是 `mark`：改备注**不是**一次新的表态，
          //   不该把「标记时间」刷成编辑的那一刻（列表是按它排序的，会跳）。
          repository.updateMeta(
            key = mark.key,
            title = mark.title,
            imageUrl = mark.imageUrl,
            reason = text.trim(),
          )
          repository.flushNow()
        }
        remarkTarget = null
        Toast.makeText(context, "备注已保存", Toast.LENGTH_SHORT).show()
      },
    )
  }
}

/** 单条目的动作种类。取消标记纯本地、删除影片走服务器，二者确认文案与危险等级都不同。 */
private enum class RowActionKind { UNMARK, DELETE }

/** 「对某一条做什么」——确认弹窗的待办。 */
private data class RowAction(val mark: DislikeMark, val kind: RowActionKind)

/**
 * 一行：缩略图 + 标题 + 备注 + 标记时间，左滑露出两个动作。
 */
@Composable
private fun DislikeRow(
  mark: DislikeMark,
  revealed: Boolean,
  onRevealChange: (Boolean) -> Unit,
  onClick: () -> Unit,
  /** 长按 = 编辑备注。与播放器里那颗键的手势一致（单击切换 / 长按写原因）。 */
  onLongClick: () -> Unit,
  onUnmark: () -> Unit,
  onDelete: () -> Unit,
) {
  val density = androidx.compose.ui.platform.LocalDensity.current
  // 两个动作按钮各 ACTION_BUTTON_WIDTH_DP ⇒ 总宽。必须在像素域算吸附阈值。
  // ⚠️ 用常量而不是写死数字：按钮宽度一改这里就得跟着改，写死会滑不到位或滑过头。
  val actionsWidthPx =
    remember(density) { with(density) { (ACTION_BUTTON_WIDTH_DP * 2).dp.roundToPx() } }

  SwipeToRevealRow(
    revealed = revealed,
    onRevealChange = onRevealChange,
    actionsWidthPx = actionsWidthPx,
    modifier = Modifier.fillMaxWidth(),
    actions = {
      // ⚠️ `fillMaxHeight()` **必须给**。见 `SwipeToRevealRow` 里 `IntrinsicSize.Min`
      //   那一段：行高由内容层固有高度定死之后，`maxHeight` 才有界，
      //   这里（以及按钮自己）的 `fillMaxHeight()` 才真正生效、与卡片等高。
      Row(modifier = Modifier.fillMaxHeight()) {
        SwipeActionButton(
          label = "取消标记",
          icon = Icons.AutoMirrored.Filled.Undo,
          // ⚠️ 用主题语义色，不要硬编码。主题是用户可选的（紫/蓝/绿/橙…多套），
          //   写死一个蓝色在别的主题下就是一块突兀的色块。
          //   container/on-container 成对取，深浅色都自动有对比度。
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          onClick = onUnmark,
        )
        SwipeActionButton(
          // ⚠️ 叫「删除影片」，作用范围是**服务器**：调 Emby 的 DELETE /Items/{id}，
          //   按官方定义「从媒体库**和文件系统**删除」—— 是物理删除，不可逆。
          //   这与「取消标记」（纯本地、可后悔）完全是两个量级，所以两按钮
          //   一个用主色、一个用错误色，且点进来还要手输「删除」才放行。
          label = "删除影片",
          icon = Icons.Filled.Delete,
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          onClick = onDelete,
        )
      }
    },
  ) {
    // 列表已只含 active 项（见上方过滤），这里不再需要"取消后置灰"的分支 ——
    // 那套逻辑是"取消后仍留在列表里"的时代留下的，现在取消即消失。
    Card(
      modifier = Modifier
        .fillMaxWidth()
        // ⚠️ 撑满行高 + 兜底最小高度。
        //  · `fillMaxHeight()`：行高由 `SwipeToRevealRow` 的 `IntrinsicSize.Min` 定死
        //    （取值来源正是这张卡片），这里撑满才能和右侧动作按钮等高。
        //  · `defaultMinSize`：内容很矮时（无备注、缩略图也没加载出来）不让行塌下去。
        //    注意是 `defaultMinSize` 而不是 `heightIn` —— 后者会**参与固有高度计算**，
        //    与 IntrinsicSize 组合可能互相干扰；前者只在最终约束里托底下限。
        .fillMaxHeight()
        .defaultMinSize(minHeight = ROW_MIN_HEIGHT_DP.dp)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
      shape = RoundedCornerShape(ROW_CORNER_DP.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
      Row(
        // ⚠️ `fillMaxHeight()` 必须给到**卡片内这一层 Row**。
        //  上面已经让 Card 撑满了行高，但 Card 的内容默认是**垂直居中**排布的：
        //  只给 Card 撑满、Row 还是 wrap-content 的话，Row 只占内容高度，
        //  视觉上仍可能与右侧按钮差一截。
        modifier = Modifier.fillMaxHeight().fillMaxWidth().padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier =
            Modifier
              .size(56.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.surfaceContainerHighest),
          contentAlignment = Alignment.Center,
        ) {
          if (!mark.imageUrl.isNullOrBlank()) {
            // 用项目自己的 EmbyImage（走 EmbyImageLoader + OkHttp）。
            // ⚠️ 本项目**没有引入 coil**，写 coil.compose.AsyncImage 编译不过。
            EmbyImage(
              url = mark.imageUrl,
              contentDescription = null,
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
              // 加载中/失败时给个 ThumbDown 占位，与行尾图标呼应
              placeholder = Icons.Filled.HeartBroken,
              // 56dp 的小图，给 320 足够（默认 720 会白白多解一层）
              maxWidth = 320,
            )
          } else {
            Icon(
              Icons.Filled.HeartBroken,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(22.dp),
            )
          }
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = mark.title.ifBlank { "（无标题）" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (mark.reason.isNotBlank()) {
            Text(
              text = mark.reason,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          } else {
            Text(
              text = "未填写原因",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.outline,
              maxLines = 1,
            )
          }
          Text(
            text = "标记于 ${formatMarkedAt(mark.markedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
          )
        }

        Icon(
          Icons.Filled.HeartBroken,
          contentDescription = "已标记",
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

/** 标记时间的可读格式。格式坏了就退化成原始数字，不能抛异常把整页搞崩。 */
private fun formatMarkedAt(ts: Long): String =
  runCatching {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    fmt.format(java.util.Date(ts))
  }.getOrElse { ts.toString() }

private fun DislikeMark.rowId(): String = "${key.serverId}::${key.itemId}"

/**
 * 管理页的导航目标。
 *
 * 单独包一层 object 而不是让内容函数直接实现 [app.marlboroadvance.mpvex.presentation.Screen]：
 * 内容函数需要 `onBack` 回调，而导航框架是给 [Screen] 无参 `Content()` ——
 * 分开之后内容函数还能被别处直接复用（比如将来放进设置页的一个内嵌区域）。
 */
@kotlinx.serialization.Serializable
object DislikeManageRoute : Screen {
  @androidx.compose.runtime.Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    DislikeManageContent(onBack = { backstack.remove(DislikeManageRoute) })
  }
}