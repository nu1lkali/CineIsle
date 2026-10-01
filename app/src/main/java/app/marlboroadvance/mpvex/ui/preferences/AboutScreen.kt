package app.marlboroadvance.mpvex.ui.preferences

import android.content.Intent
import android.content.pm.PackageManager
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.crash.CrashActivity.Companion.collectDeviceInfo
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.update.UpdateViewModel
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable
object AboutScreen : Screen {
  @Suppress("DEPRECATION")
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val clipboardManager = LocalClipboardManager.current
    val packageManager: PackageManager = context.packageManager
    val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
    val versionName = packageInfo.versionName?.substringBefore('-') ?: packageInfo.versionName ?: BuildConfig.VERSION_NAME
    val buildType = BuildConfig.BUILD_TYPE

    // Conditionally initialize update feature based on build config
    val updateViewModel: UpdateViewModel? = if (BuildConfig.ENABLE_UPDATE_FEATURE) {
      viewModel(context as androidx.activity.ComponentActivity)
    } else {
      null
    }
    val updateState by (updateViewModel?.updateState ?: MutableStateFlow(UpdateViewModel.UpdateState.Idle)).collectAsState()

    // Show toast when no update is available after manual check (only if update feature is enabled)
    LaunchedEffect(updateState) {
        if (BuildConfig.ENABLE_UPDATE_FEATURE && updateViewModel != null && updateState is UpdateViewModel.UpdateState.NoUpdate) {
            Toast.makeText(context, "Already using latest version", Toast.LENGTH_SHORT).show()
            updateViewModel.dismissNoUpdate()
        }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { 
            Text(
              text = stringResource(id = R.string.pref_about_title),
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
    ) { paddingValues ->
      val cs = MaterialTheme.colorScheme
      val colorPrimary = cs.primaryContainer
      val colorTertiary = cs.tertiaryContainer
      val transition = rememberInfiniteTransition()
      val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
          infiniteRepeatable(
            animation = tween(durationMillis = 5000),
            repeatMode = RepeatMode.Reverse,
          ),
      )
      val cornerRadius = 28.dp
      
      Column(
        modifier =
          Modifier
            .padding(paddingValues)
            .verticalScroll(rememberScrollState()),
      ) {
        PreferenceCard {
          Box(
            modifier =
              Modifier
                .drawWithCache {
                  val cx = size.width - size.width * fraction
                  val cy = size.height * fraction

                  val gradient =
                    Brush.radialGradient(
                      colors = listOf(colorPrimary, colorTertiary),
                      center = Offset(cx, cy),
                      radius = 800f,
                    )

                  onDrawBehind {
                    drawRoundRect(
                      brush = gradient,
                      cornerRadius =
                        CornerRadius(
                          cornerRadius.toPx(),
                          cornerRadius.toPx(),
                        ),
                    )
                  }
                }
                .padding(16.dp),
          ) {
            Column {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(64.dp)) {
                  AndroidView(
                    modifier = Modifier.matchParentSize(),
                    factory = { ctx ->
                      ImageView(ctx).apply {
                        setImageResource(R.mipmap.ic_launcher)
                      }
                    },
                  )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = stringResource(id = R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.onPrimaryContainer,
                  )
                  Text(
                    text = stringResource(id = R.string.i18n_project_name_en),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onPrimaryContainer.copy(alpha = 0.9f),
                  )
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = "v$versionName $buildType",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                  )
                }
              }

              Spacer(modifier = Modifier.height(20.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                val btnContainer = cs.primary
                val btnContent = cs.onPrimary
                Button(
                  onClick = { backstack.add(LibrariesScreen) },
                  modifier =
                    Modifier
                      .weight(1f)
                      .height(56.dp),
                  shape = RoundedCornerShape(16.dp),
                  colors =
                    ButtonDefaults.buttonColors(
                      containerColor = btnContainer,
                      contentColor = btnContent,
                    ),
                ) {
                  Text(
                    text = stringResource(id = R.string.pref_about_oss_libraries),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                }

                Button(
                  onClick = {
                    context.startActivity(
                      Intent(
                        Intent.ACTION_VIEW,
                        context.getString(R.string.github_repo_url).toUri(),
                      ),
                    )
                  },
                  modifier =
                    Modifier
                      .weight(1f)
                      .height(56.dp),
                  shape = RoundedCornerShape(16.dp),
                  colors =
                    ButtonDefaults.buttonColors(
                      containerColor = btnContainer,
                      contentColor = btnContent,
                    ),
                ) {
                  Text(
                    text = "GitHub",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                }
              }

              Spacer(modifier = Modifier.height(20.dp))

              Column(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable {
                      clipboardManager.setText(AnnotatedString(collectDeviceInfo()))
                    },
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(bottom = 8.dp),
                ) {
                  Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = stringResource(R.string.i18n_device_info),
                    modifier = Modifier.size(20.dp),
                    tint = cs.onPrimaryContainer,
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = stringResource(R.string.i18n_device_info),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onPrimaryContainer,
                  )
                }
                Text(
                  text = collectDeviceInfo(),
                  style = MaterialTheme.typography.bodySmall,
                  color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                )
              }
            }
          }
        }

        Spacer(Modifier.height(8.dp))

        // Acknowledgments Section
        // 这里只列**主要开源项目** —— 完整依赖清单在「开源许可」页（由 AboutLibraries 生成）。
        // 项目名与仓库地址写在 ACKNOWLEDGMENTS 里（专有名词 / 固定地址，不进资源表），
        // 只有说明文字走资源，便于本地化。
        PreferenceSectionHeader(
          title = stringResource(id = R.string.i18n_ack_title),
        )

        PreferenceCard {
          ACKNOWLEDGMENTS.forEachIndexed { index, ack ->
            if (index > 0) PreferenceDivider()
            AcknowledgmentRow(
              ack = ack,
              onClick = {
                // 个别机型没装浏览器时 startActivity 会抛 ActivityNotFoundException，
                // 静默吞掉即可 —— 点一下没反应好过直接崩
                runCatching {
                  context.startActivity(
                    Intent(Intent.ACTION_VIEW, ack.url.toUri()),
                  )
                }
              },
            )
          }
        }


        Spacer(Modifier.height(12.dp))
      }
    }
  }
}

@Suppress("DEPRECATION")
@Serializable
object LibrariesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val libraries by produceLibraries {
      context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
    }
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(id = R.string.pref_about_oss_libraries),
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
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          },
        )
      },
    ) { paddingValues ->
      LibrariesContainer(
        libraries = libraries,
        modifier =
          Modifier
            .fillMaxSize()
            .padding(paddingValues),
      )
    }
  }
}

/* ---------------- 致谢 ---------------- */

/**
 * 一条致谢。
 *
 * 项目名与仓库地址是**专有名词 / 固定地址**，直接写在代码里、不进资源表；
 * 只有说明文字需要翻译，走 [summaryRes]。
 */
private data class Acknowledgment(
  val name: String,
  val summaryRes: Int,
  val url: String,
)

/**
 * 「关于」页列出的是**主要开源项目** —— 完整依赖清单在「开源许可」页（AboutLibraries 生成）。
 *
 * 顺序从上游往下排：直接上游 → 两个播放内核 → Emby 客户端与投屏 → 其余库。
 */
private val ACKNOWLEDGMENTS =
  listOf(
    Acknowledgment(
      name = "mpvEx",
      summaryRes = R.string.i18n_ack_mpvex_summary,
      url = "https://github.com/marlboro-advance/mpvEx",
    ),
    Acknowledgment(
      name = "mpv-android / libmpv",
      summaryRes = R.string.i18n_ack_mpv_android_summary,
      url = "https://github.com/mpv-android/mpv-android",
    ),
    Acknowledgment(
      name = "GSYVideoPlayer",
      summaryRes = R.string.i18n_ack_gsy_summary,
      url = "https://github.com/CarGuo/GSYVideoPlayer",
    ),
    Acknowledgment(
      name = "AndroidX Media3 / ExoPlayer",
      summaryRes = R.string.i18n_ack_media3_summary,
      url = "https://github.com/androidx/media",
    ),
    Acknowledgment(
      name = "Emby Java SDK",
      summaryRes = R.string.i18n_ack_emby_summary,
      url = "https://github.com/MediaBrowser/Emby.SDK",
    ),
    Acknowledgment(
      name = "UPnPCast",
      summaryRes = R.string.i18n_ack_upnpcast_summary,
      url = "https://github.com/yinnho/UPnPCast",
    ),
    Acknowledgment(
      name = "AboutLibraries",
      summaryRes = R.string.i18n_ack_aboutlibraries_summary,
      url = "https://github.com/mikepenz/AboutLibraries",
    ),
    Acknowledgment(
      name = "SMBJ · Sardine · Commons Net · NanoHTTPD",
      summaryRes = R.string.i18n_ack_network_summary,
      url = "https://github.com/hierynomus/smbj",
    ),
  )

/**
 * 一行致谢：左边图标 + 项目名 / 说明，右边一枚「打开」箭头，**整行可点**。
 *
 * 箭头只是「点得开」的提示，不是独立按钮 —— 省一个触控目标，也避免误点。
 */
@Composable
private fun AcknowledgmentRow(
  ack: Acknowledgment,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Filled.Code,
      contentDescription = null,
      modifier = Modifier.size(24.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = ack.name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
      )
      Text(
        text = stringResource(id = ack.summaryRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    Spacer(modifier = Modifier.width(12.dp))
    Icon(
      imageVector = Icons.AutoMirrored.Filled.OpenInNew,
      contentDescription = stringResource(id = R.string.i18n_ack_tap_to_open),
      modifier = Modifier.size(18.dp),
      tint = MaterialTheme.colorScheme.outline,
    )
  }
}
