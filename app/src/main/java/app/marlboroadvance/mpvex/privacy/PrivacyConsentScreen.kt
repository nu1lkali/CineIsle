package app.marlboroadvance.mpvex.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首次启动的隐私确认页。
 *
 * ## 交互上的几条硬约束
 *
 * - **不默认勾选**：复选框初值恒为 false。预勾选在合规上是明确禁止的。
 * - **未勾选不能继续**：主按钮 `enabled = checked`，但不隐藏它 ——
 *   隐藏按钮会让用户找不到出口；置灰 + 旁边有勾选框，因果是可读的。
 * - **不提供绕过的路径**：返回键被 [BackHandler] 吞掉；本页也不是对话框，
 *   没有「点外部关闭」这条口子。
 * - **「暂不同意」不锁死用户**：点了先弹一个说明，给出「退出应用」和
 *   「返回」两个选择。不直接 finish，是因为误触时直接退出等于惩罚用户。
 * - **协议入口是文字链接**：点进正文页再返回时，勾选状态由 [rememberSaveable]
 *   保留 —— 不能让用户「看完回来发现勾没了，又得重看一遍」。
 *
 * ## 为什么勾选框整行可点
 *
 * 只有 20dp 的方框可点，在手机上是要对准的。整行 `toggleable` +
 * [Role.Checkbox] 既扩大了热区，也让无障碍服务把它读成一个复选框。
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

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .safeDrawingPadding()
        .padding(horizontal = 24.dp),
  ) {
    Column(
      modifier =
        Modifier
          .weight(1f)
          .verticalScroll(rememberScrollState()),
    ) {
      Spacer(modifier = Modifier.height(40.dp))

      Text(
        text = "欢迎使用",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onSurface,
      )

      Spacer(modifier = Modifier.height(20.dp))

      Text(
        text = "感谢您使用本播放器。",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = 24.sp,
      )

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = "为了保障您的个人信息安全，请阅读并了解我们的《用户服务协议》和《隐私政策》。" +
          "我们将按照相关法律法规及本协议约定处理相关信息。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 23.sp,
      )

      Spacer(modifier = Modifier.height(24.dp))

      DocumentLink(title = "《用户服务协议》") { documents = LegalDocument.USER_AGREEMENT }
      DocumentLink(title = "《隐私政策》") { documents = LegalDocument.PRIVACY_POLICY }

      Spacer(modifier = Modifier.height(20.dp))

      Text(
        text = "您可以随时在「设置 → 法律与隐私」中重新阅读这两份协议，查看当前版本与同意时间，或撤回授权。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        lineHeight = 20.sp,
      )

      Spacer(modifier = Modifier.height(32.dp))
    }

    // ── 底部固定区：勾选 + 两个按钮 ──
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = { checked = it },
          )
          .padding(vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(checked = checked, onCheckedChange = null)
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = "我已阅读并同意《用户服务协议》和《隐私政策》",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
      modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
      Text(text = "同意并继续", style = MaterialTheme.typography.titleMedium)
    }

    TextButton(
      onClick = { showDecline = true },
      modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
      Text(
        text = "暂不同意",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))
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
 * 协议正文入口。
 *
 * 用主色 + 中等字重，看起来像链接但不用 `TextDecoration.Underline`：
 * 中文正文下划线会把「《》」也划进去，视觉上更乱。整行可点，热区给足。
 */
@Composable
private fun DocumentLink(
  title: String,
  onOpen: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(14.dp),
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
        .clip(RoundedCornerShape(14.dp))
        .clickable(onClick = onOpen),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = "查看",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
  }
}
