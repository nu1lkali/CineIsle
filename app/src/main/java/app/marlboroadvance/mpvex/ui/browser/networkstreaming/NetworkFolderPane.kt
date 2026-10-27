package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkFile

/**
 * 双栏布局的**左栏**：上一级目录里的文件夹列表。
 *
 * ## 为什么是「同级导航」而不是预览
 *
 * 平板横屏的价值在于「少点几次返回」：在网盘里翻目录，最常见的动作是
 * 「进 A 看看 → 不满意 → 退回 → 进 B」。左栏把当前目录的**兄弟文件夹**摆出来，
 * 一次点击就能横跳，不用层层退回。
 *
 * 预览（左边选文件、右边看内容）在这个场景里收益很低 —— 网盘里点开文件就是
 * 直接播放 / 看图，做预览等于把已有的两个沉浸界面再拿小窗重做一遍。
 *
 * ## 空态 / 加载
 *
 * 上一级的文件夹列表是**另一次列目录请求**，慢一点很正常：
 * - 还没回来 → 一排淡淡的转圈；
 * - 回来了但是空的 → 一句「上一级没有其他文件夹」；
 * - 在连接根目录 → 这个栏根本不出现（调用方控制），不做空壳。
 */
@Composable
fun NetworkFolderPane(
  /** 上一级的显示名（栏头那行小字） */
  parentLabel: String,
  /** 上一级的子文件夹；null = 正在列 */
  folders: List<NetworkFile>?,
  /** 当前所在路径，用来高亮「我在这一个」 */
  currentPath: String,
  /** 点栏头上箭头 = 回上一级 */
  onGoUp: () -> Unit,
  onSelect: (NetworkFile) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxHeight(),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(modifier = Modifier.fillMaxHeight()) {
      // ── 栏头：上一级名 + 上行按钮 ──
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "上一级",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = parentLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = onGoUp, modifier = Modifier.size(32.dp)) {
          Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = "返回上一级",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      val list = folders
      when {
        list == null ->
          Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          }
        list.isEmpty() ->
          Text(
            text = "上一级没有其他文件夹",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          )
        else ->
          LazyColumn(
            modifier = Modifier.fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 12.dp),
          ) {
            items(items = list, key = { it.path }) { folder ->
              // 高亮 = 当前所在的那一个（点了是同位置，直接 no-op，不给误导性反馈）
              val selected = folder.path.trim('/') == currentPath.trim('/')
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .background(
                      color =
                        if (selected) {
                          MaterialTheme.colorScheme.secondaryContainer
                        } else {
                          Color.Transparent
                        },
                      shape = RoundedCornerShape(10.dp),
                    )
                    .clickable(enabled = !selected) { onSelect(folder) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Default.Folder,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                  tint =
                    if (selected) {
                      MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                      MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                  text = folder.name,
                  style = MaterialTheme.typography.bodyMedium,
                  color =
                    if (selected) {
                      MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                      MaterialTheme.colorScheme.onSurface
                    },
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
      }
    }
  }
}

/** 双栏中间那条 1dp 分隔线（放在 Row 里当兄弟节点，不用 VerticalDivider —— 少一个版本依赖） */
@Composable
fun PaneDivider() {
  Box(
    modifier =
      Modifier
        .width(1.dp)
        .fillMaxHeight()
        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
  )
}
