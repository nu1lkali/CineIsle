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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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

/**
 * 卡片光斑中段（55% 半径处）的颜色：tertiary 容器色按这个比例混进基色。
 *
 * 0 = 纯基色（最清晰、也最平），1 = 原样 tertiary（最好看、文字最危险）。
 * 鲜色本身交给 [0f] 那一档（直接用 `tertiaryContainer`），这里只负责中段的过渡，
 * 所以可以给到 0.45 而不是把整块压成基色 —— KPI 是「文字区靠护罩保读、色彩区照旧艳」。
 */
private const val ABOUT_CARD_SPOT_MIX = 0.45f

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
            Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
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
      // ── 这张卡片的「底色 / 文字」口径 ──
      // 底色是 tertiaryContainer → primaryContainer 的径向渐变，文字一律 onPrimaryContainer。
      // 「on 色」只保证在**自己的**容器色上够对比，所以光斑扫到文字上时对比度就会掉 ——
      // 但反过来说，把光斑整块压成基色又会变成一块素色（看着就廉价）。
      // 现在的分工是：
      //   · 色彩：光斑中心直接用 tertiaryContainer（不掺水），覆盖右上→右下的大半张卡；
      //   · 可读：文字区单独垫一层基色柔光（[AboutCardScrim]），把字底拉回主色域；
      //   · 质感：顶部柔光 + 1dp 上亮下暗的内描边（玻璃边），由 drawWithCache 里两层叠出来。
      // 三者叠起来才是「有颜色、有光、有边」的一张卡，而不是一块平涂。
      val baseColor = cs.primaryContainer
      val spotColor = lerp(baseColor, cs.tertiaryContainer, ABOUT_CARD_SPOT_MIX)
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
                  // 光斑只在中右 / 下半区摆动：文字在左上，让色彩始终在它「背后流」，
                  // 而不是从它身上扫过去 —— 扫过去的那几帧正是对比度最差的时刻。
                  val cx = size.width * (0.98f - 0.38f * fraction)
                  val cy = size.height * (0.06f + 0.86f * fraction)
                  // 半径跟着卡片尺寸走：原来写死 800px，同一套色在不同密度 / 屏宽上分布
                  // 完全不同（低密度屏几乎糊成纯色，高密度屏只剩一小块亮斑）。
                  val radius = size.maxDimension * 1.05f

                  // ① 主体：鲜 → 过渡 → 稳
                  val body =
                    Brush.radialGradient(
                      0f to cs.tertiaryContainer,
                      0.55f to spotColor,
                      1f to baseColor,
                      center = Offset(cx, cy),
                      radius = radius,
                    )
                  // ② 顶部柔光：像一片光从上沿打下来 —— 玻璃质感主要靠这一层，
                  //    纯渐变是「色块」，加了高光才是「材质」。
                  val sheen =
                    Brush.verticalGradient(
                      colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                      startY = 0f,
                      endY = size.height * 0.55f,
                    )
                  // ③ 1dp 内描边：上亮下暗的细边，把卡片从背景里「托」起来。
                  //    不能用 Box 的 border（那是画在外沿），这里跟圆角完全对齐。
                  val edge =
                    Brush.verticalGradient(
                      colors =
                        listOf(
                          Color.White.copy(alpha = 0.20f),
                          Color.White.copy(alpha = 0.02f),
                        ),
                    )
                  val cr = CornerRadius(cornerRadius.toPx())
                  onDrawBehind {
                    drawRoundRect(brush = body, cornerRadius = cr)
                    drawRoundRect(brush = sheen, cornerRadius = cr)
                    drawRoundRect(brush = edge, cornerRadius = cr, style = Stroke(1.dp.toPx()))
                  }
                }
                .padding(16.dp),
          ) {
            AboutCardScrim(baseColor = baseColor)
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
                    // 层级本身就是质感：标题 100%、英文名 88%、版本 82%、
                    // 设备信息 85%（字号最小，反而要比版本行实一点）。全部拉成
                    // 同一个「实」度会显得像一块印刷品，没有主次。
                    color = cs.onPrimaryContainer.copy(alpha = 0.88f),
                  )
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = "v$versionName $buildType",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onPrimaryContainer.copy(alpha = 0.82f),
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
                  // bodySmall 字号最小，是整卡最容易糊的一处 ⇒ 不跟版本行一起压到 0.82
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
      name = "DanmakuFlameMaster",
      summaryRes = R.string.i18n_ack_danmaku_summary,
      url = "https://github.com/bilibili/DanmakuFlameMaster",
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
    Acknowledgment(
      name = "Telephoto",
      summaryRes = R.string.i18n_ack_telephoto_summary,
      url = "https://github.com/saket/telephoto",
    ),
    Acknowledgment(
      name = "Coil 3",
      summaryRes = R.string.i18n_ack_coil_summary,
      url = "https://github.com/coil-kt/coil",
    ),
  )

/**
 * 卡片里压在文字底下的一层基色柔光。
 *
 * 只干一件事：把左上（标题 / 版本 / 设备信息所在）的底色拉回 `primaryContainer`，
 * 让 `onPrimaryContainer` 的对比度重新有效；右下那片鲜色照旧流动，所以整张卡不会变平。
 * 用径向而不是整块填充，是为了不留一条能看见的硬边 —— 这也是「看起来贵」和
 * 「看起来像贴了个白色色块」的区别。
 */
@Composable
private fun BoxScope.AboutCardScrim(baseColor: Color) {
  Box(
    modifier =
      Modifier
        .matchParentSize()
        .drawBehind {
          drawRect(
            brush =
              Brush.radialGradient(
                0f to baseColor.copy(alpha = 0.62f),
                1f to Color.Transparent,
                center = Offset(size.width * 0.28f, size.height * 0.24f),
                radius = size.maxDimension * 0.95f,
              ),
          )
        },
  )
}

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
