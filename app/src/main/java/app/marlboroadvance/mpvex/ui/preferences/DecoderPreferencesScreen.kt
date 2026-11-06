package app.marlboroadvance.mpvex.ui.preferences

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.player.Debanding
import app.marlboroadvance.mpvex.ui.player.DefaultDecoder
import app.marlboroadvance.mpvex.ui.player.MPVProfile
import app.marlboroadvance.mpvex.ui.player.PlaybackColorPolicy
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.ui.preferences.VulkanUtils
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject

@Serializable
object DecoderPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val preferences = koinInject<DecoderPreferences>()
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val isVulkanSupported = remember { VulkanUtils.isVulkanSupported(context) }
    // 设备是否报告 HDR 显示能力：HDR 输出开关据此决定是否可用，
    // 避免「开着没效果」却看不出原因（旧实现的痛点）。
    val isHdrDevice = remember { PlaybackColorPolicy.deviceSupportsHdr(context) }
    var showGpuNextWarning by remember { mutableStateOf(false) }
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_decoder),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Default.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        val listState = rememberLazyListState()
        SettingsSpotlightReveal(listState, DecoderPreferencesScreen)
        LazyColumn(
          state = listState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_decoder))
          }

          item {
            PreferenceCard {
              val profile by preferences.profile.collectAsState()
              val currentProfile = MPVProfile.fromValue(profile)
              ListPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = currentProfile,
                onValueChange = { preferences.profile.set(it.value) },
                values = MPVProfile.entries,
                valueToText = { value -> AnnotatedString(stringResource(value.displayNameRes)) },
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_profile_title),
                title = { Text(stringResource(R.string.pref_decoder_profile_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(currentProfile.displayNameRes),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 默认解码器（v218）：把「要不要硬解」从布尔开关升级成三选一。
              // 旧开关只有「硬解（copyback）／软解」两档，「只用硬解、不回退」这种
              // 排查与救急场景没有入口 —— 而它恰恰是判断「花屏是硬解的锅还是软解的锅」
              // 最直接的手段。播放中面板里的那个「解码器」是实时读数，跟这里不是一件事。
              val directOutput by preferences.directOutput.collectAsState()
              val decoderMode by preferences.decoderMode.collectAsState()
              ListPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Hd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = decoderMode,
                onValueChange = { preferences.decoderMode.set(it) },
                values = DefaultDecoder.entries,
                valueToText = { value -> AnnotatedString(stringResource(value.titleRes)) },
                // 直出模式会强制 `mediacodec` 零拷贝，跟这三档不是一个维度 ⇒ 置灰说清楚，
                // 别让人以为「选了软解却还在硬解」。
                enabled = !directOutput,
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_default_title),
                title = { Text(stringResource(R.string.pref_decoder_default_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    if (directOutput) {
                      stringResource(R.string.pref_decoder_default_overridden)
                    } else {
                      stringResource(decoderMode.titleRes)
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 直出模式：零拷贝硬解。4K 高帧率片源从「每帧回拷 12.4 MB」降到零拷贝，
              // 代价是 mpv 侧的字幕 / 着色器 / 截图全部失效，所以默认关、写在文案里。
              // （`directOutput` 在上面那条列表项之前已 collect，这里直接复用。）
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = directOutput,
                onValueChange = {
                  preferences.directOutput.set(it)
                },
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_direct_output_title),
                title = { Text(stringResource(R.string.pref_decoder_direct_output_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(R.string.pref_decoder_direct_output_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val gpuNext by preferences.gpuNext.collectAsState()
              val useVulkan by preferences.useVulkan.collectAsState() // Added to check Vulkan state
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = gpuNext,
                onValueChange = { enabled ->
                    if (enabled && !gpuNext && !useVulkan) { // Only show warning if Vulkan is disabled
                        showGpuNextWarning = true
                    } else {
                        preferences.gpuNext.set(enabled)
                        if (enabled && !useVulkan) { // Only disable Anime4K if Vulkan is disabled
                            preferences.enableAnime4K.set(false)
                        }
                    }
                },
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_gpu_next_title),
                title = { Text(stringResource(R.string.pref_decoder_gpu_next_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(R.string.pref_decoder_gpu_next_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              if (showGpuNextWarning) {
                  AlertDialog(
                      onDismissRequest = { showGpuNextWarning = false },
                      title = { Text(stringResource(R.string.pref_decoder_gpu_next_enable_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                      text = {
                          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                              Text(stringResource(R.string.pref_decoder_gpu_next_warning))
                              Text(stringResource(R.string.pref_decoder_gpu_next_purple_screen_fix))
                              
                              Surface(
                                  color = MaterialTheme.colorScheme.errorContainer,
                                  shape = MaterialTheme.shapes.small
                              ) {
                                  Column(modifier = Modifier.padding(8.dp)) {
                                      Text(
                                          text = stringResource(R.string.pref_anime4k_incompatibility),
                                          style = MaterialTheme.typography.titleSmall,
                                          color = MaterialTheme.colorScheme.onErrorContainer
                                      )
                                      Text(
                                          text = stringResource(R.string.pref_anime4k_gpu_next_error),
                                          style = MaterialTheme.typography.bodySmall,
                                          color = MaterialTheme.colorScheme.onErrorContainer
                                      )
                                  }
                              }
                          }
                      },
                      confirmButton = {
                          Button(onClick = {
                              preferences.gpuNext.set(true)
                              preferences.enableAnime4K.set(false) // Ensure Anime4K is disabled on confirmation
                              showGpuNextWarning = false
                          }) {
                              Text(stringResource(R.string.pref_decoder_gpu_next_enable_anyway))
                          }
                      },
                      dismissButton = {
                          TextButton(onClick = { showGpuNextWarning = false }) {
                              Text(stringResource(R.string.generic_cancel))
                          }
                      }
                  )
              }

              PreferenceDivider()

              // 杜比视界 Profile 5 颜色修正：只对 P5 让路，其余片源继续硬解。
              // 依赖 gpu-next（libplacebo 才读 DV RPU）+ 非直出（要有 GPU 渲染链），
              // 条件不满足时置灰并写明原因 —— 摆在那儿却点不动是最糟的。
              val dvP5Fix by preferences.dolbyVisionColorFix.collectAsState()
              val dvP5Usable = gpuNext && !directOutput
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.ColorLens,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = dvP5Fix,
                onValueChange = { preferences.dolbyVisionColorFix.set(it) },
                enabled = dvP5Usable,
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_dv_p5_title),
                title = { Text(stringResource(R.string.pref_decoder_dv_p5_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    if (dvP5Usable) {
                      stringResource(R.string.pref_decoder_dv_p5_summary)
                    } else {
                      stringResource(R.string.pref_decoder_dv_p5_needs_gpu_next)
                    },
                    color = if (dvP5Usable) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.error,
                  )
                },
              )

              PreferenceDivider()

              // HDR 显示输出：按片源切 Window 的 COLOR_MODE_HDR，与渲染器无关。
              // 设备不报告 HDR 能力时置灰 —— 这正是旧实现「开着没效果」时
              // 最该直接告诉用户的事，而不是让人反复重进播放器试。
              val hdrEnabled by preferences.hdrEnabled.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.HighQuality,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = hdrEnabled,
                onValueChange = { preferences.hdrEnabled.set(it) },
                enabled = isHdrDevice,
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_hdr_title),
                title = { Text(stringResource(R.string.pref_decoder_hdr_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    if (isHdrDevice) {
                      stringResource(R.string.pref_decoder_hdr_summary)
                    } else {
                      stringResource(R.string.pref_decoder_hdr_no_device_support)
                    },
                    color = if (isHdrDevice) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.error,
                  )
                },
              )

              PreferenceDivider()

              // val useVulkan by preferences.useVulkan.collectAsState() // Moved up for gpuNext logic
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = useVulkan,
                onValueChange = { enabled ->
                  preferences.useVulkan.set(enabled)
                  // When Vulkan is disabled, ensure Anime4K and GPU Next are not both enabled
                  if (!enabled) {
                    val anime4kEnabled = preferences.enableAnime4K.get()
                    val gpuNextEnabled = preferences.gpuNext.get()
                    if (anime4kEnabled && gpuNextEnabled) {
                      // Disable GPU Next to keep Anime4K
                      preferences.gpuNext.set(false)
                    }
                  }
                },
                enabled = isVulkanSupported,
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_vulkan_title),
                title = { Text(stringResource(R.string.pref_decoder_vulkan_title) + " (Experimental)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(
                      if (isVulkanSupported) R.string.pref_decoder_vulkan_summary
                      else R.string.pref_decoder_vulkan_not_supported
                    ),
                    color = if (isVulkanSupported) MaterialTheme.colorScheme.outline
                           else MaterialTheme.colorScheme.error,
                  )
                },
              )

              PreferenceDivider()

              val debanding by preferences.debanding.collectAsState()
              ListPreference(
                icon = {
                  Icon(
                    Icons.Outlined.BlurOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = debanding,
                onValueChange = { preferences.debanding.set(it) },
                values = Debanding.entries,
                valueToText = { value -> AnnotatedString(stringResource(value.titleRes)) },
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_debanding_title),
                title = { Text(stringResource(R.string.pref_decoder_debanding_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    debanding.name,
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val useYUV420p by preferences.useYUV420P.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.ColorLens,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = useYUV420p,
                onValueChange = {
                  preferences.useYUV420P.set(it)
                },
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_yuv420p_title),
                title = { Text(stringResource(R.string.pref_decoder_yuv420p_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    stringResource(R.string.pref_decoder_yuv420p_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              
              val enableAnime4K by preferences.enableAnime4K.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = enableAnime4K,
                onValueChange = { enabled ->
                    preferences.enableAnime4K.set(enabled)
                    if (enabled && !useVulkan) { // Only disable GPU Next if Vulkan is disabled
                        preferences.gpuNext.set(false)
                    }
                },
                modifier = Modifier.settingsAnchor(R.string.pref_anime4k_title),
                title = { Text(stringResource(R.string.pref_anime4k_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Column {
                    Text(
                      stringResource(R.string.pref_anime4k_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                      text = "github.com/bloc97/Anime4K",
                      color = MaterialTheme.colorScheme.primary,
                      style = MaterialTheme.typography.bodySmall,
                      textDecoration = TextDecoration.Underline,
                      modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bloc97/Anime4K"))
                        context.startActivity(intent)
                      }
                    )
                  }
                },
              )
            }
          }
        }
      }
    }
  }
}

object VulkanUtils {
    private const val TAG = "VulkanUtils"

    /**
     * Checks if the device supports Vulkan for MPV rendering
     *
     * Requirements for MPV androidvk context:
     * - Android 13 (API 33) minimum for Vulkan 1.3
     * - Vulkan 1.3 (0x00403000) hardware version
     * - GPU must also support OpenGL ES 3.1 or higher
     *
     * @return true if Vulkan 1.3+ is supported for MPV, false otherwise
     */
    fun isVulkanSupported(context: Context): Boolean {
        try {
            // Vulkan 1.3 requires Android 13 (API 33) minimum
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Log.d(TAG, "Vulkan not supported: Android version ${Build.VERSION.SDK_INT} < 33 (Tiramisu)")
                return false
            }

            val packageManager = context.packageManager

            // Check for OpenGL ES 3.1+ support (required by Android for Vulkan)
            val configInfo = packageManager.systemAvailableFeatures
                .firstOrNull { it.name == null }

            val glesVersion = configInfo?.reqGlEsVersion ?: 0
            val glesMajor = glesVersion shr 16
            val glesMinor = glesVersion and 0xFFFF

            Log.d(TAG, "Device OpenGL ES version: $glesMajor.$glesMinor (raw: 0x${glesVersion.toString(16)})")

            // OpenGL ES 3.1 = 0x00030001
            if (glesVersion < 0x00030001) {
                Log.d(TAG, "Vulkan not supported: OpenGL ES $glesMajor.$glesMinor < 3.1")
                return false
            }

            // Check for Vulkan 1.3 hardware version (required for proper MPV support)
            if (packageManager.hasSystemFeature(
                    PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
                    0x00403000 // Vulkan 1.3
                )) {
                Log.d(TAG, "Vulkan 1.3 supported ✓")
                return true
            }

            Log.d(TAG, "Vulkan not supported: Vulkan 1.3 not available")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "Error checking Vulkan support", e)
            return false
        }
    }
}
