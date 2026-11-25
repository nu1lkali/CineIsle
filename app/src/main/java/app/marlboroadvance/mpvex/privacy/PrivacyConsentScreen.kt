package app.marlboroadvance.mpvex.privacy

import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.R

/**
 * 首次启动的隐私确认页。
 *
 * ## 交互上的几条硬约束（改版时不要动）
 *
 * - **不默认勾选**：复选框初值恒为 false。预勾选在合规上是明确禁止的。
 * - **未勾选不能继续**：主按钮 `enabled = checked`，但不隐藏它 ——
 *   隐藏按钮会让用户找不到出口；置灰 + 旁边有勾选框，因果是可读的。
 * - **不提供绕过的路径**：返回键被 [BackHandler] 吞掉；本页也不是对话框，
 *   没有「点外部关闭」这条口子。
 * - **「暂不同意」不锁死用户**：点了先弹一个说明，给出「退出应用」和
 *   「返回」两个选择。不直接 finish，是因为误触时直接退出等于惩罚用户。
 * - **协议入口是独立行**：点进正文页再返回时，勾选状态由 [rememberSaveable]
 *   保留 —— 不能让用户「看完回来发现勾没了，又得重看一遍」。
 *
 * ## 版式
 *
 * 上半区可滚动（信息量大、小屏可能放不下），下半区固定（勾选 + 两个动作），
 * 两区之间用一条 hairline 分开。这样不论滚动到哪里，出口永远在拇指够得着的位置。
 *
 * 视觉上只用一个重音：应用图标垫一层 primaryContainer 圆角底。
 * 正文全部走 onSurface / onSurfaceVariant，不铺渐变、不加大色块 ——
 * 门禁页的首要任务是「读得完」，不是「看得炫」。
 */
@Composable
fun PrivacyConsentScreen(
  onAgree: () -> Unit,
  onExit: () -> Unit,
) {
  var checked by rememberSaveable { mutableStateOf(false) }
  var documents by remember { mutableStateOf<LegalDocument?>(null) }
  var showDecline by remember { mutableStateOf(false) }

  // 返回键一律吞掉：这是门禁页，退回去就是「绕过」
  BackHandler(enabled = true) {}

  val openDocument = documents
  if (openDocument != null) {
    LegalDocumentScreen(document = openDocument, onBack = { documents = null })
    return
  }

  val cs = MaterialTheme.colorScheme

  Surface(modifier = Modifier.fillMaxSize(), color = cs.surface) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .safeDrawingPadding(),
    ) {
      // ── 可滚动区 ──
      Column(
        modifier =
          Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
      ) {
        Spacer(modifier = Modifier.height(36.dp))

        // 应用身份：图标 + 名称 + 版本。用户要先确认「打开的是哪个 App」，
        // 再谈同意哪份协议 —— 顺序反了会显得像是替别人做决定。
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier =
              Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.primaryContainer),
          ) {
            AndroidView(
              modifier = Modifier.matchParentSize().padding(6.dp),
              factory = { ctx ->
                ImageView(ctx).apply {
                  setImageResource(R.mipmap.ic_launcher)
                  scaleType = ImageView.ScaleType.FIT_CENTER
                }
              },
            )
          }

          Spacer(modifier = Modifier.width(14.dp))

          Column {
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = cs.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = "v${BuildConfig.VERSION_NAME}",
              style = MaterialTheme.typography.labelMedium,
              color = cs.outline,
            )
          }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
          text = "在使用前，请阅读并同意以下条款",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = cs.onSurface,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
          text = "为了保障您的个人信息安全，请阅读并了解《用户服务协议》与《隐私政策》。" +
            "我们将按照相关法律法规及协议约定处理相关信息。",
          style = MaterialTheme.typography.bodyMedium,
          color = cs.onSurfaceVariant,
          lineHeight = 23.sp,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 三条承诺：把「隐私政策里最该被知道的三件事」提前摆出来，
        // 让用户不必点进正文才知道自己在同意什么。措辞与正文严格一致。
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = cs.surfaceContainerLow,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            ConsentPoint(text = "服务器地址、账号与播放记录仅保存在本机")
            Spacer(modifier = Modifier.height(10.dp))
            ConsentPoint(text = "没有自建后台，媒体内容不会上传到开发者服务器")
            Spacer(modifier = Modifier.height(10.dp))
            ConsentPoint(text = "崩溃上报与媒体扫描在您同意后才初始化")
          }
        }

        Spacer(modifier = Modifier.height(18.dp))

        DocumentLink(title = "用户服务协议", subtitle = "使用规则、责任边界与争议解决") {
          documents = LegalDocument.USER_AGREEMENT
        }

        DocumentLink(title = "隐私政策", subtitle = "信息处理清单、第三方服务与您的权利") {
          documents = LegalDocument.PRIVACY_POLICY
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = "同意后可随时在「设置 → 法律与隐私」重新阅读、查看版本与同意时间，或撤回授权。",
          style = MaterialTheme.typography.bodySmall,
          color = cs.outline,
          lineHeight = 20.sp,
        )

        Spacer(modifier = Modifier.height(24.dp))
      }

      // ── 固定动作区 ──
      HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))

      Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { checked = it },
              )
              .padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(checked = checked, onCheckedChange = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "我已阅读并同意《用户服务协议》和《隐私政策》",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            lineHeight = 19.sp,
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
          onClick = {
            // 兜底：即使事件被误触发，也必须真的勾过才写记录
            if (checked) onAgree()
          },
          enabled = checked,
          shape = RoundedCornerShape(16.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = cs.primary,
              contentColor = cs.onPrimary,
            ),
          modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
          Text(
            text = "同意并继续",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }

        TextButton(
          onClick = { showDecline = true },
          modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
          Text(
            text = "暂不同意",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.outline,
          )
        }

        Spacer(modifier = Modifier.height(6.dp))
      }
    }
  }

  if (showDecline) {
    AlertDialog(
      onDismissRequest = { showDecline = false },
      title = { Text(text = "暂不同意") },
      text = {
        Text(
          text = "未同意《用户服务协议》和《隐私政策》时，本应用无法连接媒体服务器、" +
            "浏览本地文件或播放视频。\n\n您可以先退出应用，之后随时重新打开并阅读协议。",
          style = MaterialTheme.typography.bodyMedium,
          lineHeight = 22.sp,
        )
      },
      confirmButton = {
        TextButton(onClick = onExit) { Text(text = "退出应用") }
      },
      dismissButton = {
        TextButton(onClick = { showDecline = false }) { Text(text = "返回") }
      },
    )
  }
}

/**
 * 一条承诺。
 *
 * 用 6dp 圆点代替勾选图标：圆点没有「已完成」的语义，不会被读成任务清单；
 * 同时它足够小，不会把三条短句变成三个并列按钮。
 */
@Composable
private fun ConsentPoint(text: String) {
  val cs = MaterialTheme.colorScheme
  Row(verticalAlignment = Alignment.Top) {
    Box(
      modifier =
        Modifier
          .padding(top = 7.dp)
          .size(6.dp)
          .background(cs.primary.copy(alpha = 0.75f), CircleShape),
    )
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = text,
      style = MaterialTheme.typography.bodySmall,
      color = cs.onSurfaceVariant,
      lineHeight = 20.sp,
    )
  }
}

/**
 * 协议正文入口。
 *
 * 改成「标题 + 一行说明 + 右侧查看」的两级结构：只有标题时，用户不知道点进去
 * 会看到什么；加了说明就不必先点开再判断。整行可点，热区给足。
 */
@Composable
private fun DocumentLink(
  title: String,
  subtitle: String,
  onOpen: () -> Unit,
) {
  val cs = MaterialTheme.colorScheme
  Surface(
    color = cs.surfaceContainerLow,
    shape = RoundedCornerShape(16.dp),
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
        .clip(RoundedCornerShape(16.dp))
        .clickable(onClick = onOpen),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "《$title》",
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
          color = cs.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = cs.outline,
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = "查看",
        style = MaterialTheme.typography.labelLarge,
        color = cs.primary,
      )
    }
  }
}
