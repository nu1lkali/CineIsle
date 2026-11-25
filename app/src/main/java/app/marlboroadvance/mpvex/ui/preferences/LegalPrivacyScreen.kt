package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.privacy.AppStartupCoordinator
import app.marlboroadvance.mpvex.privacy.ConsentRecord
import app.marlboroadvance.mpvex.privacy.LegalDocument
import app.marlboroadvance.mpvex.privacy.LegalDocumentScreen
import app.marlboroadvance.mpvex.privacy.PrivacyConsentRepository
import app.marlboroadvance.mpvex.privacy.PrivacyGateManager
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「法律与隐私」设置页。
 *
 * ## 为什么要有这一页
 *
 * 首次启动的确认弹窗只解决「第一次」。用户后来想复核协议、想知道自己同意的是
 * 哪一版、想撤回授权，都得有常驻入口 —— 只在启动时可见的协议等于不可查。
 *
 * ## 页面上放什么
 *
 * 1. 两份协议正文（随时重读，正文即启动时展示的那一份）；
 * 2. 协议版本与同意时间（回答「我同意的是什么版本」）；
 * 3. 一段隐私说明（把「哪些走本地、哪些发往服务器、哪些发往第三方」讲清楚，
 *    而不是让用户去 APK 里翻 HTML）；
 * 4. 撤回授权（撤回后立即回到确认页）。
 *
 * ⚠️ 版本号显示的是**记录里的值**而不是当前常量：用户需要知道「我同意的是 1.0」，
 * 而不是「应用现在是 1.2」。两者不一致时，说明协议已更新、下次启动会重新确认。
 */
@Serializable
object LegalPrivacyScreen : Screen {

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current

    // 只在进入页面时读一次：本页不含可编辑项，没有中途变化的可能。
    val repository = remember { PrivacyConsentRepository(context) }
    var record by remember { mutableStateOf(repository.read()) }
    var openedDocument by remember { mutableStateOf<LegalDocument?>(null) }
    var showRevokeConfirm by remember { mutableStateOf(false) }

    val openDocument = openedDocument
    if (openDocument != null) {
      LegalDocumentScreen(document = openDocument, onBack = { openedDocument = null })
      return
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = "法律与隐私",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(
                imageVector = Icons.AutoMirrored.Default.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      // ⚠️ `ProvidePreferenceLocals` 不能漏：Preference / PreferenceCard 组合时会读
      //   `LocalPreferenceTheme`，缺了这一层会在**进入页面那一刻**抛
      //   `IllegalStateException: CompositionLocal LocalPreferenceTheme not present`。
      //   栈顶落在 Icons.noLocalProvidedFor 上，看着像库内部问题，实际是调用方少提供一层。
      //   项目里所有设置页都有这层（对齐 AudioPreferencesScreen / DislikePreferencesScreen）。
      ProvidePreferenceLocals {
        Column(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .verticalScroll(rememberScrollState()),
        ) {
          PreferenceSectionHeader(title = "协议正文")

          PreferenceCard {
            DocumentRow(
              icon = Icons.Outlined.Gavel,
              title = "用户服务协议",
              summary = "使用规则、责任边界与争议解决",
            ) { openedDocument = LegalDocument.USER_AGREEMENT }

            PreferenceDivider()

            DocumentRow(
              icon = Icons.Outlined.Policy,
              title = "隐私政策",
              summary = "信息处理清单、第三方服务与您的权利",
            ) { openedDocument = LegalDocument.PRIVACY_POLICY }
          }

          PreferenceSectionHeader(title = "协议版本")

          PreferenceCard {
            InfoRow(
              title = "用户服务协议版本",
              value = record.userAgreementVersion.ifBlank { "—" },
              currentValue = PrivacyGateManager.USER_AGREEMENT_VERSION,
            )

            PreferenceDivider()

            InfoRow(
              title = "隐私政策版本",
              value = record.privacyPolicyVersion.ifBlank { "—" },
              currentValue = PrivacyGateManager.PRIVACY_POLICY_VERSION,
            )

            PreferenceDivider()

            Preference(
              title = { Text(text = "同意时间", maxLines = 1, overflow = TextOverflow.Ellipsis) },
              summary = {
                Text(
                  text = formatAgreedAt(record),
                  color = MaterialTheme.colorScheme.outline,
                )
              },
              icon = {
                Icon(
                  imageVector = Icons.Outlined.Description,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
              },
            )
          }

          PreferenceSectionHeader(title = "隐私相关说明")

          PreferenceCard {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
              Text(
                text = "本应用没有自建后台，不会把您的账号或媒体内容上传到开发者服务器。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 22.sp,
              )
              Spacer(modifier = Modifier.height(10.dp))
              Text(
                text = "· 服务器地址、账号与播放记录：仅保存在本机；凭据以系统密钥库加密。\n" +
                  "· 播放进度与收藏：开启同步时才写回您自己的 Emby 服务器。\n" +
                  "· 网络字幕与弹幕：会向对应第三方站点发送影片名构造的搜索关键词。\n" +
                  "· 崩溃上报：仅在您同意后初始化；F-Droid 版本不含该 SDK。\n" +
                  "· 本地媒体扫描：仅在您同意后随启动执行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 21.sp,
              )
            }
          }

          PreferenceSectionHeader(title = "授权管理")

          PreferenceCard {
            Preference(
              title = { Text(text = "撤回隐私授权") },
              summary = {
                Text(
                  text = "撤回后返回确认页；崩溃上报、自动连接与媒体扫描停止执行。已保存的服务器配置与播放记录不会删除。",
                  color = MaterialTheme.colorScheme.outline,
                )
              },
              icon = {
                Icon(
                  imageVector = Icons.Outlined.Restore,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
              },
              onClick = { showRevokeConfirm = true },
            )
          }

          Spacer(modifier = Modifier.height(32.dp))
        }
      }
    }

    if (showRevokeConfirm) {
      AlertDialog(
        onDismissRequest = { showRevokeConfirm = false },
        title = { Text(text = "撤回隐私授权") },
        text = {
          Text(
            text = "撤回后本应用会立即返回隐私确认页，主界面与播放功能在重新同意前不可用。\n\n" +
              "已保存的服务器配置、播放进度、收藏与已下载文件不会被删除；如需一并清除，" +
              "请在系统「应用信息」中清除应用数据。\n\n" +
              "本次运行中已初始化的组件会在重启应用后完全停止。",
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 22.sp,
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              showRevokeConfirm = false
              AppStartupCoordinator.onConsentWithdrawn()
              // 刷新本页读到的快照：确认页替换主界面之前，本页可能还组合着一帧
              record = repository.read()
            },
          ) {
            Text(text = "确认撤回")
          }
        },
        dismissButton = {
          TextButton(onClick = { showRevokeConfirm = false }) { Text(text = "取消") }
        },
      )
    }
  }
}

/** 协议正文入口行。 */
@Composable
private fun DocumentRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  summary: String,
  onOpen: () -> Unit,
) {
  Preference(
    title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    summary = {
      Text(text = summary, color = MaterialTheme.colorScheme.outline)
    },
    icon = {
      Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    },
    onClick = onOpen,
  )
}

/**
 * 只读信息行。
 *
 * 显示的是「已同意的版本」；当它与当前版本不一致时，摘要里直接点明
 * 「协议已更新，下次启动会重新确认」—— 用户在这里就能预判接下来的行为，
 * 不会被一个突然出现的弹窗吓一跳。
 */
@Composable
private fun InfoRow(
  title: String,
  value: String,
  currentValue: String,
) {
  val outdated = PrivacyGateManager.compareVersion(value, currentValue) < 0
  Preference(
    title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    summary = {
      Text(
        text =
          if (outdated) {
            "已同意 $value · 当前 $currentValue，协议已更新"
          } else {
            "已同意 $value"
          },
        color = if (outdated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
      )
    },
  )
}

/** 同意时间的人类可读格式；没有记录时明确写「未记录」。 */
private fun formatAgreedAt(record: ConsentRecord): String {
  if (!record.hasAgreed || record.agreedAt <= 0L) return "未记录"
  val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
  return formatter.format(Date(record.agreedAt))
}
