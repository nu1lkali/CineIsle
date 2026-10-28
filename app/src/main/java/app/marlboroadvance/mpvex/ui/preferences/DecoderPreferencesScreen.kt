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
import app.marlboroadvance.mpvex.ui.player.MPVProfile
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

              val tryHWDecoding by preferences.tryHWDecoding.collectAsState()
              SwitchPreference(
                icon = {
                  Icon(
                    Icons.Outlined.Hd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                },
                value = tryHWDecoding,
                onValueChange = {
                  preferences.tryHWDecoding.set(it)
                },
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_try_hw_dec_title),
                title = { Text(stringResource(R.string.pref_decoder_try_hw_dec_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

              // HDR 输出：与 gpu-next 解耦的独立开关。
              // 关掉 gpu-next 时本项无意义（只有 libplacebo 有这条通路），
              // 所以直接 disable —— 摆在那儿却怎么点都不生效是最糟的（红线 29）。
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
                enabled = gpuNext,
                modifier = Modifier.settingsAnchor(R.string.pref_decoder_hdr_title),
                title = { Text(stringResource(R.string.pref_decoder_hdr_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                summary = {
                  Text(
                    if (gpuNext) {
                      stringResource(R.string.pref_decoder_hdr_summary)
                    } else {
                      // 与上面 Vulkan「不支持」时用 error 色的做法一致：说清为什么不能开。
                      stringResource(R.string.pref_decoder_hdr_needs_gpu_next)
                    },
                    color = if (gpuNext) MaterialTheme.colorScheme.outline
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
