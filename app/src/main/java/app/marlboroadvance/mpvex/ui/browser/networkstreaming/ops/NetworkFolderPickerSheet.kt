package app.marlboroadvance.mpvex.ui.browser.networkstreaming.ops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import kotlinx.coroutines.launch

/**
 * 「选择目标文件夹」抽屉（复制 / 移动共用）。
 *
 * ## 为什么是抽屉而不是又一个系统目录选择器
 *
 * 目标是**当前连接上的远程目录**：只有本协议自己的 `fs/list` 才能列出来，
 * `FolderPickerDialog`（本地 SAF 目录选择器）在这里完全用不上。
 * 外观与「跳转到」[app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkBrowserScreen]
 * 的路径导航弹窗保持一致：底部抽屉、从根目录逐级下钻、当前层即目标。
 *
 * ## 交互约定
 *
 * - **只能往下钻，不能选「文件」**：目标是目录，列表里只列文件夹（文件不渲染）。
 * - **当前层就是目标**：不需要「先点进空白区再确认」，底部按钮写的是
 *   「复制到这里」——当前层级一眼可见（顶部那行路径），按钮也说得明白。
 * - **不跳出去**：抽屉里换目录不影响页面本身（这是「另一个浏览上下文」），
 *   关掉抽屉，页面还停在原来那一层。
 *
 * @param listDir 列一个目录（接 `ViewModel.listDirForPicker`）
 * @param startPath 打开时停在哪一层（调用方传当前目录 —— 最常见的操作是「复制到隔壁」）
 * @param createFolder 新建子目录（传**完整相对路径**，如 `电影/新片`）。为 null 时不显示
 *   「新建文件夹」入口 —— 非 AList 协议没有 `fs/mkdir`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkFolderPickerSheet(
  /** 标题：「复制到…」/「移动到…」 */
  title: String,
  /** 确认按钮文案：「复制到这里」/「移动到这里」 */
  confirmLabel: String,
  startPath: String,
  listDir: suspend (String) -> Result<List<NetworkFile>>,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
  createFolder: (suspend (String) -> Result<Unit>)? = null,
) {
  val sheetState = rememberModalBottomSheetState()
  val scope = rememberCoroutineScope()
  // 抽屉内部自己的当前位置（相对路径，根目录 = 空串）
  var path by remember { mutableStateOf(startPath.trim('/')) }
  var folders by remember { mutableStateOf<List<NetworkFile>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  // 新建文件夹的小弹窗
  var creating by remember { mutableStateOf(false) }
  var newName by remember { mutableStateOf("") }
  var createBusy by remember { mutableStateOf(false) }
  var createError by remember { mutableStateOf<String?>(null) }
  // 手动刷新计数：建完目录要重列一次，而 path 没变 ⇒ 只能靠这个 key 踢一脚
  var reloadTick by remember { mutableIntStateOf(0) }

  // 每换一层就重列一次。⚠️ 用 path 当 key：返回上一层时也会重新列（不复用旧结果），
  // 因为「刚才可能在里面建了目录 / 删了东西」，过期的空列表会让人以为目录没了。
  LaunchedEffect(path, reloadTick) {
    loading = true
    error = null
    listDir(path)
      .onSuccess { entries ->
        // 只留文件夹，并按名字排（与服务端返回顺序无关，跨驱动观感一致）
        folders = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        error = null
      }
      .onFailure { error = it.message ?: "无法读取目录" }
    loading = false
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
      Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        // 当前层路径：一行截断，超出部分省略（末尾才是当前目录名，用 Beginning 会看不懂）
        Text(
          text = if (path.isEmpty()) "根目录" else "/$path",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Spacer(Modifier.height(8.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

      // 根目录 / 上一级 两枚快捷入口（与「跳转到」弹窗的层级感一致，但更省地方）
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        TextButton(onClick = { if (path.isNotEmpty()) path = "" }, enabled = path.isNotEmpty()) {
          Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("根目录")
        }
        TextButton(
          onClick = { path = path.substringBeforeLast('/', "") },
          enabled = path.isNotEmpty(),
        ) {
          Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text("上一级")
        }
        // 新建目录：以前只能退出去、在浏览页建好再回来选 —— 而「想放到一个还不存在的
        // 文件夹里」正是最常见的念头（按片名/日期归档就这么来的）
        if (createFolder != null) {
          TextButton(
            onClick = {
              createError = null
              newName = ""
              creating = true
            },
            enabled = !loading,
          ) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            // 只写「新建」：这一行已经有三颗按钮，大字体下长文案会互相挤
            Text("新建")
          }
        }
      }

      Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
        contentAlignment = Alignment.Center,
      ) {
        when {
          loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
          error != null ->
            Text(
              text = "读取失败：$error",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          folders.isEmpty() ->
            Text(
              text = "这个文件夹里没有子文件夹\n可以直接点下方按钮选它当目标",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          else ->
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
              items(folders, key = { it.path }) { folder ->
                FolderRow(name = folder.name, onClick = { path = folder.path.trim('/') })
              }
            }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
      // 冲突策略必须**事先说清**：真撞名时我们自动改名，不覆盖任何东西。
      // 不写这一行的话，用户复制一份副本会得到「a(1).mp4」却不知道规矩从哪来。
      Text(
        text = "同名条目会自动改名为「名字(1)」，不会覆盖已有内容",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = onDismiss) { Text("取消") }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = { onConfirm(path) }) { Text(confirmLabel) }
      }
    }
  }

  // 新建子目录的小弹窗。只收**一段名字**：父路径由当前层决定，
  // 让用户在这里输 `a/b/c` 只会制造「路径拼错」这类自找的失败。
  if (creating && createFolder != null) {
    AlertDialog(
      onDismissRequest = { if (!createBusy) creating = false },
      title = { Text("新建文件夹") },
      text = {
        Column {
          Text(
            text = if (path.isEmpty()) "建在根目录" else "建在 /$path",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.height(10.dp))
          OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            singleLine = true,
            label = { Text("文件夹名") },
            isError = createError != null,
            modifier = Modifier.fillMaxWidth(),
          )
          if (createError != null) {
            Spacer(Modifier.height(6.dp))
            Text(
              text = createError.orEmpty(),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      },
      confirmButton = {
        TextButton(
          enabled = !createBusy && newName.isNotBlank(),
          onClick = {
            val name = newName.trim().trim('/')
            if (name.isEmpty()) return@TextButton
            createBusy = true
            createError = null
            scope.launch {
              val target = if (path.isEmpty()) name else "$path/$name"
              createFolder(target)
                .onSuccess {
                  createBusy = false
                  creating = false
                  // 建好就**进去**：用户建这个目录就是为了往里面放东西，
                  // 停在原地等于让他再点一次自己的新目录
                  path = target
                  reloadTick++
                }
                .onFailure {
                  createBusy = false
                  createError = it.message ?: "新建文件夹失败"
                }
            }
          },
        ) {
          Text(if (createBusy) "创建中…" else "创建")
        }
      },
      dismissButton = {
        TextButton(onClick = { creating = false }, enabled = !createBusy) { Text("取消") }
      },
    )
  }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Default.Folder,
      contentDescription = null,
      modifier = Modifier.size(22.dp),
      tint = MaterialTheme.colorScheme.secondary,
    )
    Spacer(Modifier.width(14.dp))
    Text(
      text = name,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Icon(
      Icons.AutoMirrored.Filled.KeyboardArrowRight,
      contentDescription = null,
      modifier = Modifier.size(20.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
