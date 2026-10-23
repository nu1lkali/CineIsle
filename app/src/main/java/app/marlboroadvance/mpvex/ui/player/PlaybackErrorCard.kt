package app.marlboroadvance.mpvex.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放失败卡片：替代「点播放 → 整个 App 直接消失回桌面」。
 *
 * ## 这张卡解决的是什么
 *
 * 服务端返回 403 / 404 的死链（网盘直链过期、文件被移走、STRM 失效）如果直接交给
 * libmpv，崩的是 **native 层** —— 进程瞬间消失，App 自己的崩溃页都出不来，
 * 用户完全不知道发生了什么，只知道「这软件一播这个就闪退」。
 *
 * 现在的时序是：进播放页 → 先探测一次地址（[app.marlboroadvance.mpvex.domain.emby.EmbyClient.probeStreamUrl]）
 * → 明确不可播就**不调 playFile**（mpv 根本不碰那条死链，所以不会崩）
 * → 弹出这张卡，把「为什么」和「怎么办」一起给出来。
 *
 * ## 配色为什么全是黑底白字
 *
 * 播放器控制条之外不许用主题色（项目红线）。这里是压在视频画面上的浮层，
 * 用固定的「深黑半透明 + 白字」，与 `NextUpCard` / 缓冲转圈同一套中性色，
 * 换任何主题都不会打架，也不会在浅色主题下变成一块看不见的白雾。
 *
 * @param onRetry 重试：由调用方清掉错误态并重新探测播放
 * @param onBack 退出播放页
 */
@Composable
fun PlaybackErrorCard(
  viewModel: PlayerViewModel,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val message by viewModel.playbackError.collectAsState()

  AnimatedVisibility(
    visible = message != null,
    enter = fadeIn(tween(180)),
    exit = fadeOut(tween(140)),
    modifier = modifier,
  ) {
    val reason = message.orEmpty()
    Column(
      modifier =
        Modifier
          // 限宽而不是铺满：长文案在平板上铺成一行会有两百多个字符，读起来很累
          .widthIn(max = 320.dp)
          .padding(horizontal = 24.dp)
          .clip(RoundedCornerShape(20.dp))
          .background(Color.Black.copy(alpha = 0.86f))
          .padding(horizontal = 22.dp, vertical = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = "无法播放这个视频",
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(10.dp))
      Text(
        text = reason,
        color = Color.White.copy(alpha = 0.78f),
        fontSize = 13.sp,
        lineHeight = 19.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(6.dp))
      // 给一句「这不是你的错」：用户看到闪退时第一反应往往是「我是不是点坏了」
      Text(
        text = "已阻止把这个地址交给播放内核，所以这次不会再闪退。",
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 11.sp,
        lineHeight = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(18.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(
          onClick = onBack,
          colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.7f)),
        ) {
          Text("返回", fontSize = 14.sp)
        }
        Button(
          onClick = onRetry,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = Color.White,
              contentColor = Color.Black,
            ),
        ) {
          Text("重试", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
      }
    }
  }
}
