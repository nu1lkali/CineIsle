package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
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
 */
@Composable
fun EmbyItemActionsDialog(
  name: String,
  kindLabel: String,
  onDismissRequest: () -> Unit,
  onScan: () -> Unit,
  onRefreshMetadata: (replaceAllMetadata: Boolean, replaceAllImages: Boolean) -> Unit,
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
