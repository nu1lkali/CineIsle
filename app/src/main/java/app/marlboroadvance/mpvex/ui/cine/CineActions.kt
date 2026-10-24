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
 * 播放操作的控制器：详情页需要「播放 / 从头播放」两种语义，首页只需要前者。
 *
 * ## 为什么不是两个独立的 composable
 * 两套实现意味着两套 Toast、两套错误文案 —— 迟早出现「首页点播报错、详情页静默」。
 * 这里把「发起 + 失败提示」收成一份，[play] 与 [playFromStart] 只是参数不同。
 *
 * `fromStart` 传的是 [EmbyViewModel.play] 的**同名参数**，语义见那边的 KDoc
 * （剧集 = 强制第一集开头，忽略进度）。
 */
class CinePlayController internal constructor(
  private val invoke: (item: EmbyItem, resumeSeconds: Long, fromStart: Boolean) -> Unit,
) {
  /** 有进度就续播（[resumeSeconds] > 0），否则从头 */
  fun play(item: EmbyItem, resumeSeconds: Long = 0L) = invoke(item, resumeSeconds, false)

  /** 剧集「从头播放」：第一集开头，哪怕第 1 集已经看完 */
  fun playFromStart(item: EmbyItem) = invoke(item, 0L, true)
}

/** 带「从头播放」能力的播放控制器（详情页用） */
@Composable
fun rememberCinePlayController(): CinePlayController {
  val context = LocalContext.current
  val app = context.applicationContext as Application
  val viewModel: EmbyViewModel = viewModel(factory = EmbyViewModel.factory(app))
  val server by viewModel.currentServer.collectAsState()
  val scope = rememberCoroutineScope()
  val failedText = stringResource(R.string.cine_play_failed)

  return remember(viewModel, server, context, failedText) {
    CinePlayController { item, resumeSeconds, fromStart ->
      val current = server
      if (current != null) {
        scope.launch {
          runCatching {
            viewModel.play(current, item, resumeSeconds = resumeSeconds, fromStart = fromStart)
          }.onFailure { error ->
            Toast.makeText(context, error.message ?: failedText, Toast.LENGTH_SHORT).show()
          }
        }
      }
    }
  }
}

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
  val controller = rememberCinePlayController()
  return { item, resumeSeconds -> controller.play(item, resumeSeconds) }
}
