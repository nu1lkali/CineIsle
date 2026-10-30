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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkFile

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
) {
  val sheetState = rememberModalBottomSheetState()
  // 抽屉内部自己的当前位置（相对路径，根目录 = 空串）
  var path by remember { mutableStateOf(startPath.trim('/')) }
  var folders by remember { mutableStateOf<List<NetworkFile>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  // 每换一层就重列一次。⚠️ 用 path 当 key：返回上一层时也会重新列（不复用旧结果），
  // 因为「刚才可能在里面建了目录 / 删了东西」，过期的空列表会让人以为目录没了。
  LaunchedEffect(path) {
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
