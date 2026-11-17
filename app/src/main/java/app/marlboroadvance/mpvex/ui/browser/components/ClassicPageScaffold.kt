package app.marlboroadvance.mpvex.ui.browser.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Classic 页面的统一外壳（P0 地基）。
 *
 * 现在各页仍各自调 `Scaffold`，但底部留白、SnackbarHost、insets 的处理已经分叉：
 * 最近播放写 88dp、媒体库写 96dp、文件夹页又按 FAB 单独算。这里先把**SnackbarHost**
 * 和外壳入口收口，后续迁移不需要每页再记一遍「有没有 host、host 放哪」。
 *
 * 视觉与 Material3 `Scaffold` 默认值保持一致；这个改动不会改变任何页面的排版。
 * 页面要发 Snackbar 时从 [LocalClassicSnackbarHostState] 取同一个 host，
 * 不要再 `Toast.makeText(...)` —— Toast 无法撤销、也无法排队。
 */
@Composable
fun ClassicPageScaffold(
  modifier: Modifier = Modifier,
  topBar: @Composable () -> Unit = {},
  bottomBar: @Composable () -> Unit = {},
  floatingActionButton: @Composable () -> Unit = {},
  snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
  content: @Composable (PaddingValues) -> Unit,
) {
  androidx.compose.runtime.CompositionLocalProvider(
    LocalClassicSnackbarHostState provides snackbarHostState,
  ) {
    Scaffold(
      modifier = modifier,
      topBar = topBar,
      bottomBar = bottomBar,
      floatingActionButton = floatingActionButton,
      snackbarHost = { SnackbarHost(snackbarHostState) },
      content = content,
    )
  }
}

/** 当前 Classic 页面的 SnackbarHost；没有经过 [ClassicPageScaffold] 时为 null。 */
val LocalClassicSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }
