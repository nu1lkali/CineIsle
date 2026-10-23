package app.marlboroadvance.mpvex.ui.cine

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.ui.browser.emby.EmbyViewModel
import kotlinx.coroutines.launch

/**
 * 「点一下就开始播」的统一实现。
 *
 * ## 为什么抽出来
 * 首页的三个分区、搜索页的结果、"全部"网格里点卡片都要播同一个东西。
 * 如果每处各写一遍 `scope.launch { viewModel.play(...) }`，失败提示就会各写各的 ——
 * 现实里必然出现「首页点播没反应也不报错、搜索页会弹 toast」这种不一致。
 *
 * `play()` 内部已经处理了容器类条目（剧集挑「有进度的第一集」/「第一集未看的」）、
 * 多集连播的播放列表、预解析直链，这里只负责**发起 + 失败提示**。
 *
 * @return `(item, resumeSeconds) -> Unit`。`resumeSeconds > 0` = 从该秒续播。
 */
@Composable
fun rememberCinePlay(): (EmbyItem, Long) -> Unit {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val server by viewModel.currentServer.collectAsState()
  val scope = rememberCoroutineScope()
  val failedText = stringResource(R.string.cine_play_failed)

  return remember(viewModel, server, context, failedText) {
    { item, resumeSeconds ->
      val current = server
      if (current != null) {
        scope.launch {
          runCatching { viewModel.play(current, item, resumeSeconds = resumeSeconds) }
            .onFailure { error ->
              Toast.makeText(
                context,
                error.message ?: failedText,
                Toast.LENGTH_SHORT,
              ).show()
            }
        }
      }
      // 没有服务器时静默返回：能走到这里的界面本来就只在有服务器时才显示
    }
  }
}
