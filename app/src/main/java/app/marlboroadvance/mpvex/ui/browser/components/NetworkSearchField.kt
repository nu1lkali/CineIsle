package app.marlboroadvance.mpvex.ui.browser.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/**
 * 网络模块的搜索输入框（根页搜连接 / 浏览页搜当前目录，共用同一套视觉）。
 *
 * 点顶栏放大镜出现：纯内存过滤，不发任何请求。
 * 打开即自动聚焦要键盘；尾部按钮有词先清词、无词才关搜索 ——
 * 清词比关搜索更常用，省得关掉再点放大镜。
 *
 * 为什么抽出来：根页（连接列表）和浏览页（文件列表）都要「点放大镜 → 出现搜索条」，
 * 各写一份的话圆角/字号/尾部按钮行为迟早跑偏（这个项目已经吃过这种亏）。
 *
 * @param placeholder 空内容时的提示语。根页是「搜索连接…」，浏览页是「搜索当前目录…」。
 */
@Composable
fun NetworkSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "搜索当前目录…",
) {
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(Unit) {
    // 要焦点弹键盘。偶发节点还没挂上（重组时序）会抛异常，吞掉不影响使用
    runCatching { focusRequester.requestFocus() }
  }

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Filled.Search,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.width(12.dp))
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
          .weight(1f)
          .focusRequester(focusRequester),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
          color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
          Box(contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
              Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            innerTextField()
          }
        },
      )
      IconButton(
        onClick = {
          if (query.isNotEmpty()) onQueryChange("") else onClose()
        },
      ) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = if (query.isNotEmpty()) "清空关键词" else "关闭搜索",
          modifier = Modifier.size(20.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
