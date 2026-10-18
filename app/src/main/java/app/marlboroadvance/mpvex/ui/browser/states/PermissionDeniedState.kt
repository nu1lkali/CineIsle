@file:Suppress("DEPRECATION")

package app.marlboroadvance.mpvex.ui.browser.states

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.R

@SuppressLint("UseKtx")
@Composable
fun PermissionDeniedState(
  onRequestPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var showExplanationDialog by remember { mutableStateOf(false) }

  // Determine if we're using MANAGE_EXTERNAL_STORAGE or scoped storage permissions
  val isPlayStoreBuild = remember { BuildConfig.SCOPED_STORAGE_ONLY }

  // Animated scale for the icon
  val infiniteTransition = rememberInfiniteTransition(label = "permission_icon")
  val scale by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.1f,
    animationSpec =
      infiniteRepeatable(
        animation = tween(2000, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse,
      ),
    label = "icon_scale",
  )

  Box(
    modifier = modifier
      .fillMaxSize()
      .padding(top = 40.dp, bottom = 100.dp) // Added top padding for icon, reduced bottom padding
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(32.dp) // Increased padding to prevent icon cutoff
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {

        // Animated Icon with Surface
        Surface(
          modifier =
            Modifier
              .size(152.dp) // Increased size to compensate for padding (120dp + 32dp padding)
              .padding(16.dp) // Added padding around the icon to prevent cutoff
              .scale(scale),
          shape = RoundedCornerShape(32.dp),
          color = MaterialTheme.colorScheme.errorContainer,
          tonalElevation = 3.dp,
        ) {
          Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            modifier =
              Modifier
                .padding(28.dp)
                .fillMaxSize(),
            tint = MaterialTheme.colorScheme.onErrorContainer,
          )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Title
        Text(
          text = stringResource(R.string.i18n_storage_required),
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description Card
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
          shape = RoundedCornerShape(20.dp),
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = if (isPlayStoreBuild) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  "mpvEx requires \"Photos and videos\" permission to access and play your video files stored on your device."
                } else {
                  "mpvEx requires \"Storage\" permission to access and play your media files stored on your device."
                }
              } else {
                "mpvEx requires \"All file access\" permission to discover media and subtitles on your device due to a change in security policy in Android 11 and later versions."
              },
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center,
            )
          }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Allow Access Button
        FilledTonalButton(
          onClick = {
            if (isPlayStoreBuild) {
              // Play Store build: Use regular permission request
              onRequestPermission()
            } else {
              // Standard build: Open All Files Access settings for Android 11+
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                  val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                  intent.data = Uri.parse("package:${context.packageName}")
                  context.startActivity(intent)
                } catch (_: Exception) {
                  // Fallback to general All Files Access settings
                  val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                  context.startActivity(intent)
                }
              } else {
                // For older Android versions, use the regular permission request
                onRequestPermission()
              }
            }
          },
          modifier =
            Modifier
              .fillMaxWidth()
              .height(56.dp),
          shape = RoundedCornerShape(16.dp),
        ) {
          Text(
            text = stringResource(R.string.i18n_allow_access),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Why do I see this? link
        TextButton(
          onClick = { showExplanationDialog = true },
        ) {
          Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = stringResource(R.string.i18n_why_see_this),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
          )
        }

        Spacer(modifier = Modifier.weight(1f))
      }
    }
  }

  // Explanation Dialog
  if (showExplanationDialog) {
    val uriHandler = LocalUriHandler.current
    val githubUrl = "https://github.com/marlboro-advance/mpvex"

    AlertDialog(
      onDismissRequest = { showExplanationDialog = false },
      icon = {
        Icon(
          imageVector = Icons.Outlined.Info,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      },
      title = {
        Text(
          text = stringResource(R.string.i18n_why_permission),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
        )
      },
      text = {
        Column(
          modifier =
            Modifier
              .heightIn(max = 400.dp)
              .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (isPlayStoreBuild) {
            // Play Store build explanation
            Text(
              text = "本应用需要读取你的视频文件，这是媒体播放器提供核心功能的前提。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
              text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "在 Android 13 及以上系统，这项权限让应用可以从设备存储（包括「下载」「电影」、DCIM 等目录）读取视频文件。"
              } else {
                "这项权限让应用可以读取设备上的媒体文件，用来播放视频与音频。"
              },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
              text = stringResource(R.string.i18n_permission_usage),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = FontWeight.Medium,
            )

            Text(
              text = "• 发现并展示你的视频文件\n• 播放媒体内容\n• 加载字幕文件",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            // Standard build explanation
            Text(
              text = "本应用一直需要存储访问权限：只有拿到它，才能找到设备上的全部媒体与字幕文件，包括系统本身不支持的那些格式。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
              text = "但由于安全策略调整，Android 11 及以上系统的应用需要额外授权才能继续这样做。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
              text = "请放心，这项权限只用于自动发现设备上的媒体 / 字幕文件，不会以任何方式读取其他应用存放的私有数据。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          Text(
            text = "本应用是完全开源的项目。你可以访问我们的 GitHub 仓库，核对源码与权限的实际用途：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          // Clickable GitHub link
          val annotatedString =
            buildAnnotatedString {
              pushStringAnnotation(
                tag = "URL",
                annotation = githubUrl,
              )
              withStyle(
                style =
                  SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                  ),
              ) {
                append(githubUrl)
              }
              pop()
            }

          ClickableText(
            text = annotatedString,
            style = MaterialTheme.typography.bodyMedium,
            onClick = { offset ->
              annotatedString
                .getStringAnnotations(
                  tag = "URL",
                  start = offset,
                  end = offset,
                ).firstOrNull()
                ?.let {
                  uriHandler.openUri(it.item)
                }
            },
          )

          Text(
            text = "请放心，你的隐私是我们的最高优先级：我们不会为其他目的访问你的文件，也不会上传或存储到我们的服务器 —— 文件始终留在你自己的设备上。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
          )
        }
      },
      confirmButton = {
        FilledTonalButton(
          onClick = { showExplanationDialog = false },
          shape = RoundedCornerShape(12.dp),
        ) {
          Text(stringResource(R.string.got_it))
        }
      },
      shape = RoundedCornerShape(24.dp),
    )
  }
}
