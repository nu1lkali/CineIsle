package app.marlboroadvance.mpvex.ui.browser.emby.components

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.browser.emby.ExternalPlayerOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「用外部播放器打开」的选择弹窗。
 *
 * 为什么自己画一个、而不是直接甩系统选择器：
 *  - 系统选择器**没法排除影屿自己**（我们两个播放页也注册了视频类型，会出现「原地打转」的选项）；
 *  - 常用播放器没法排前面，列表顺序完全由系统的优先级决定；
 *  - 没法标出「上次用的那个」。
 *
 * 列表底部保留一个「系统选择器」入口当兜底 —— 万一我们的枚举在某个 ROM 上漏了什么，
 * 还有一条路可走。
 */
@Composable
fun ExternalPlayerPickerDialog(
  players: List<ExternalPlayerOption>,
  lastUsedKey: String?,
  onPick: (ExternalPlayerOption) -> Unit,
  onSystemChooser: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("用外部播放器打开") },
    text = {
      LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
        items(players, key = { it.key }) { player ->
          PlayerRow(
            label = player.label,
            packageName = player.packageName,
            highlighted = player.key == lastUsedKey,
            onClick = { onPick(player) },
          )
        }
        item {
          HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
          PlayerRow(
            label = "系统选择器（更多应用）",
            packageName = null,
            highlighted = false,
            onClick = onSystemChooser,
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}

@Composable
private fun PlayerRow(
  label: String,
  packageName: String?,
  highlighted: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AppIcon(packageName)
    Spacer(Modifier.width(12.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (highlighted) {
      Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = "上次使用",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/**
 * 应用图标。
 *
 * 用**框架自带**的 Drawable → Bitmap 手绘转换，不引 accompanist / core-ktx 的
 * `toBitmap` —— 图标加载是异步的（走 IO），列表滚动时不会卡主线程。
 */
@Composable
private fun AppIcon(packageName: String?) {
  val bitmap = rememberAppIconBitmap(packageName)
  Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
    if (bitmap != null) {
      Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
      )
    } else {
      Icon(
        imageVector = Icons.Filled.Apps,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
      )
    }
  }
}

@Composable
private fun rememberAppIconBitmap(packageName: String?): ImageBitmap? {
  val pm = LocalContext.current.packageManager
  val state =
    produceState<ImageBitmap?>(initialValue = null, packageName) {
      if (packageName == null) return@produceState
      value = withContext(Dispatchers.IO) { loadAppIconBitmap(pm, packageName) }
    }
  val bitmap = state.value

  // 回收上一张图（清单 #20 建议项）。图标是 96×96 ARGB_8888（每张约 36KB），
  // 播放器列表可能有几十个外部应用 —— 不回收就是几十张 native 图悬着等 GC。
  //
  // ⚠️ 用 `DisposableEffect(bitmap)` 而不是 `produceState` 里写 `onDispose`：
  // 后者是 Compose 的顶层 API，只能在 composable 函数体里调；
  // `produceState` 的 lambda 是 `ProduceStateScope`（协程作用域），不是 composable 作用域。
  //
  // key 用 `bitmap`（实例本身）：它每次加载都是新对象，
  // 所以 key 一变就意味着「旧的可以扔了」，正好是我们要的时机。
  DisposableEffect(bitmap) {
    onDispose {
      bitmap?.asAndroidBitmap()?.let { bmp ->
        if (!bmp.isRecycled) runCatching { bmp.recycle() }
      }
    }
  }

  return bitmap
}

private fun loadAppIconBitmap(
  pm: PackageManager,
  packageName: String,
  sizePx: Int = 96,
): ImageBitmap? =
  runCatching {
    val drawable = pm.getApplicationIcon(packageName)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    bitmap.asImageBitmap()
  }.getOrNull()
