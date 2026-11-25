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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 关于页主卡的**固定品牌色板**。
 *
 * ⚠️ 这是全 App 唯一刻意不跟随 MaterialTheme 的界面：设计稿 `about-privacy-redesign.html`
 * 给的是一套自洽的绿系视觉（中心亮黄绿 → 边缘薄荷绿 + 深墨绿字），它的漂亮恰恰来自
 * 这组固定色。之前把色值改成读 `colorScheme.primaryContainer/tertiaryContainer`，
 * 默认主题是紫（#794F81），容器色又被冲淡，渐变色差几乎为零 —— 真机就成了一块素卡，
 * 这正是「设计图漂亮、实际很丑」的根因。品牌展示卡用固定色是合理例外，与全局主题不冲突。
 *
 * 深浅两套各自保证对比度：浅色卡亮绿底 + 深墨绿字（>12:1），深色卡深绿底 + 浅薄荷字（>7:1）。
 * [accent] 是分组引导条的颜色（对应设计稿的 `--primary`）。
 */
private data class AboutPalette(
  val spot: Color,
  val mid: Color,
  val edge: Color,
  val ink: Color,
  val accent: Color,
  val isDark: Boolean,
)

private val ABOUT_PALETTE_LIGHT =
  AboutPalette(
    spot = Color(0xFFD7E3B4),
    mid = Color(0xFFCFE0CB),
    edge = Color(0xFFCFE6DA),
    ink = Color(0xFF0D2A22),
    accent = Color(0xFF2F5F4F),
    isDark = false,
  )

private val ABOUT_PALETTE_DARK =
  AboutPalette(
    spot = Color(0xFF3F735D),
    mid = Color(0xFF2E5847),
    edge = Color(0xFF203E33),
    ink = Color(0xFFE8F3EC),
    accent = Color(0xFFA9D6BC),
    isDark = true,
  )

/** 按当前主题的明暗选色板。用 `onBackground` 亮度判定，跟随 App 的深度模式设定（含 AMOLED）。 */
@Composable
private fun aboutPalette(): AboutPalette =
  if (MaterialTheme.colorScheme.onBackground.luminance() < 0.5f) ABOUT_PALETTE_LIGHT else ABOUT_PALETTE_DARK

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
        if (BuildConfig.ENABLE_UPDATE_FEATURE && updateViewModel != null) {
            when (updateState) {
                is UpdateViewModel.UpdateState.NoUpdate -> {
                    Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                    updateViewModel.dismissNoUpdate()
                }
                is UpdateViewModel.UpdateState.Error -> {
                    Toast.makeText(context, "检查更新失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
                    updateViewModel.dismissError()
                }
                else -> Unit
            }
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
      // 主卡用 [aboutPalette] 的固定品牌绿，刻意不跟随 MaterialTheme —— 原因见 AboutPalette 注释。
      // 光斑在三色之间缓慢摆动，中心亮点始终在文字「背后」流动，而不是从字上扫过去。
      val palette = aboutPalette()
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
        // ── 这张卡为什么不再用 PreferenceCard ──
        // PreferenceCard 内部是 `Card { Column(padding(vertical = 8.dp)) { content() } }`，
        // 也就是说它会在内容外面再套 8dp 的**上下**内边距。上一版把渐变画在内容里的
        // 一个 Box 上，那个 Box 于是比卡片上下各小 8dp、左右却等宽 —— 而
        // `drawRoundRect(cornerRadius = 28.dp)` 画的圆角矩形与卡片圆角错开 8dp，
        // 卡片上下就各露出一条 surfaceContainer 的灰带，左右是月牙形的灰边。
        // 看着就是「一张灰卡里嵌了一块方方的、上下有边的矩形」，比原来的平涂还难看。
        //
        // 现在整张卡自己画：裁切、底色、渐变、内边距绑在同一个节点上，
        // 绘制矩形与卡片外沿完全重合，圆角也天然对齐，不存在「嵌一层」的可能。
        // 外边距（16 / 8）与 PreferenceCard 保持一致，整页的卡片左边缘仍然对齐。
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp)
              .clip(RoundedCornerShape(cornerRadius))
              // 底色垫在渐变之下：圆角抗锯齿那半个像素需要它，否则边缘露出背景色、显得毛躁。
              // 用 edge 色而不是 surfaceContainer，描边处才是渐变的自然延续。
              .background(palette.edge)
              .drawWithCache {
                // 光斑只在中右 / 下半区摆动：文字在左上，让色彩始终在它「背后流」。
                val cx = size.width * (0.98f - 0.38f * fraction)
                val cy = size.height * (0.06f + 0.86f * fraction)
                // 半径跟着卡片尺寸走：同一套色在不同密度 / 屏宽上才有相近的分布。
                val radius = size.maxDimension * 1.05f

                // ① 主体：设计稿固定绿 —— 中心亮黄绿 → 中段薄荷绿 → 边缘深一档。
                //    这是全 App 唯一刻意不跟随主题的卡，色值见 AboutPalette。
                val body =
                  Brush.radialGradient(
                    0f to palette.spot,
                    0.55f to palette.mid,
                    1f to palette.edge,
                    center = Offset(cx, cy),
                    radius = radius,
                  )
                // ② 顶部柔光：很弱的一层，只提供玻璃质感，不再像以前那样把渐变整块冲淡
                //    （那层 scrim 是「设计图漂亮、真机发灰」的直接原因，已移除）。
                val sheen =
                  Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.5f,
                  )
                // ③ 1dp 内描边：上亮下暗的细边，把卡片从背景里「托」起来，与圆角完全对齐。
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
                    color = palette.ink,
                  )
                  Text(
                    text = stringResource(id = R.string.i18n_project_name_en),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    // 层级本身就是质感：标题 100%、英文名 88%、版本 82%、
                    // 设备信息 85%（字号最小，反而要比版本行实一点）。全部拉成
                    // 同一个「实」度会显得像一块印刷品，没有主次。
                    color = palette.ink.copy(alpha = 0.88f),
                  )
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = "v$versionName $buildType",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.ink.copy(alpha = 0.82f),
                  )
                }
              }

              Spacer(modifier = Modifier.height(22.dp))

              // ── 操作区 ──
              // 之前是「两颗实心主按钮 + 一颗全宽次要按钮 + 一条浅底胶囊」四种形态堆在一起，
              // 视觉重量全压在按钮上，反而没有主次。现在收成两层：
              //   ① 两颗并列按钮（开源许可 / GitHub）—— 真正会去点的两件事；
              //   ② 两条轻量行（检查更新 / 联系开发者）—— 状态型动作，右侧带当前值，
              //      用 hairline 分隔，语境与设置页的行式列表一致。
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                AboutGlassButton(
                  text = stringResource(id = R.string.pref_about_oss_libraries),
                  icon = Icons.AutoMirrored.Outlined.Article,
                  modifier = Modifier.weight(1f),
                  onClick = { backstack.add(LibrariesScreen) },
                )
                AboutGlassButton(
                  text = "GitHub",
                  // 品牌按钮用 GitHub 官方 mark（单色 vector，随 tint 适配深浅主题），
                  // 而不是通用的「代码」图标。
                  iconPainter = painterResource(R.drawable.ic_github),
                  modifier = Modifier.weight(1f),
                  onClick = {
                    // 没装浏览器时 startActivity 会抛 ActivityNotFoundException，静默吞掉
                    runCatching {
                      context.startActivity(
                        Intent(
                          Intent.ACTION_VIEW,
                          context.getString(R.string.github_repo_url).toUri(),
                        ),
                      )
                    }
                  },
                )
              }

              val checking = updateState is UpdateViewModel.UpdateState.Loading

              // 「检查更新」入口。此前只有一套「自动检查」开关，而那个开关默认关、
              // 界面上也没有开关项 ⇒ 用户点遍整个应用都触发不到。这里给一个显式入口：
              // 走同一套 UpdateViewModel，检查结果由 Navigator 里的对话框承接。
              // 分隔线前统一留 16dp：否则关掉更新功能的风味里，分割线会紧贴按钮下沿。
              Spacer(modifier = Modifier.height(16.dp))

              if (BuildConfig.ENABLE_UPDATE_FEATURE && updateViewModel != null) {
                HorizontalDivider(color = palette.ink.copy(alpha = 0.12f))
                AboutActionRow(
                  label = "检查更新",
                  icon = Icons.Outlined.Update,
                  value = if (checking) "正在检查…" else "当前 v$versionName",
                  enabled = !checking,
                  onClick = { updateViewModel.checkForUpdate(manual = true) },
                )
              }

              // 联系开发者：隐私政策与用户协议里承诺的反馈渠道，必须在应用内可点，
              // 否则「有问题联系这个邮箱」就是一句空话。
              // 只唤起邮件客户端（ACTION_SENDTO），不申请任何权限。
              HorizontalDivider(color = palette.ink.copy(alpha = 0.12f))
              AboutActionRow(
                label = stringResource(id = R.string.i18n_about_contact_developer),
                icon = Icons.Outlined.Mail,
                value = DEVELOPER_EMAIL,
                onClick = {
                  runCatching {
                    context.startActivity(
                      Intent(Intent.ACTION_SENDTO, "mailto:$DEVELOPER_EMAIL".toUri()),
                    )
                  }
                },
              )

              Spacer(modifier = Modifier.height(22.dp))

              // ── 设备信息 ──
              // 原来是一坨 8 行 `Key: value` 的 bodySmall 纯文本：字最小、行距最挤、
              // 英文键名与哈希值混在一起，扫读成本最高，也是整张卡最「廉价」的一处。
              // 现在按「应用与设备 / 播放内核」两组重排，键值两列对齐；
              // 复制动作收进右上角胶囊按钮 —— 整卡可点容易误触复制。
              // 复制的仍是 collectDeviceInfo() 原文（与崩溃页一致），
              // 只重排展示，不改变粘贴给开发者的那份诊断串。
              DeviceInfoPanel(
                raw = collectDeviceInfo(),
                // 与主卡共用同一个呼吸相位：设备面板不是「又一张静态卡」，
                // 而是浮在主卡渐变之上、跟着它一起起伏的一层玻璃。
                breath = fraction,
                onCopy = {
                  clipboardManager.setText(AnnotatedString(collectDeviceInfo()))
                  Toast.makeText(context, "已复制设备信息", Toast.LENGTH_SHORT).show()
                },
              )
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

/**
 * 开发者联系邮箱。
 *
 * 与 `assets/legal/privacy_policy.html`、`user_agreement.html` 里写的必须是同一个地址 ——
 * 用户读到「可以发邮件反馈」时，应用内得有个真的能点的地方。
 */
private const val DEVELOPER_EMAIL = "i@pwnhub.net"

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
      imageVector = Icons.Outlined.Code,
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

/* ---------------- 设备信息面板 ---------------- */

/** 一行键值：左键右值，值占满剩余宽度。 */
private data class DeviceInfoRow(
  val label: String,
  val value: String,
)

/** 一组同类信息（应用与设备 / 播放内核）。[icon] 给分组标题做视觉锚点。 */
private data class DeviceInfoGroup(
  val title: String,
  val icon: ImageVector,
  val rows: List<DeviceInfoRow>,
)

/**
 * 把 [collectDeviceInfo] 的 `Key: value` 原文拆成分组的展示行。
 *
 * 为什么不直接重写一份取值逻辑：那会与崩溃页的诊断串逐渐分岔（新增一项忘了同步）。
 * 这里只做「分组 + 中文键名」，值一律原样取自原文。
 * 解析不出来（格式变了）时回退成单行展示，不会白屏。
 */
private fun deviceInfoGroups(raw: String): List<DeviceInfoGroup> {
  val values =
    raw.lineSequence()
      .mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) {
          null
        } else {
          line.take(separator).trim() to line.drop(separator + 1).trim()
        }
      }.toMap()
  if (values.isEmpty()) return emptyList()

  fun valueOf(key: String): String = values[key].orEmpty()

  val groups =
    listOf(
      DeviceInfoGroup(
        title = "应用与设备",
        icon = Icons.Outlined.PhoneAndroid,
        rows =
          listOf(
            DeviceInfoRow("版本", valueOf("App version")),
            DeviceInfoRow("Android", valueOf("Android version")),
            DeviceInfoRow("品牌", valueOf("Device brand")),
            DeviceInfoRow("厂商", valueOf("Device manufacturer")),
            DeviceInfoRow("型号", valueOf("Device model")),
          ),
      ),
      DeviceInfoGroup(
        title = "播放内核",
        icon = Icons.Outlined.Movie,
        rows =
          listOf(
            DeviceInfoRow("mpv", valueOf("MPV version")),
            DeviceInfoRow("ffmpeg", valueOf("ffmpeg version")),
            DeviceInfoRow("libplacebo", valueOf("libplacebo version")),
          ),
      ),
    )

  // 空值行直接不渲染：真机上个别内核版本可能为空，
  // 留着一行「ffmpeg」右边什么都没有，比不显示更像缺陷。
  return groups
    .map { group -> group.copy(rows = group.rows.filter { it.value.isNotBlank() }) }
    .filter { it.rows.isNotEmpty() }
}

/**
 * 设备信息面板。
 *
 * 层级只用三个量级，不堆分隔线：
 * 靶——组标题（labelMedium + 0.08sp 字距 + 62% 透明度）；
 * 中——键名（labelMedium + 55%）；
 * 重——值（bodyMedium + Medium 字重 + 96%）。
 * 键名定宽 88dp 而不是右对齐：值长短差异大（从「12 (37)」到带哈希的 mpv 版本），
 * 右对齐会让键名和值中间拉出一条很长的空白带。
 */
@Composable
private fun DeviceInfoPanel(
  raw: String,
  breath: Float,
  onCopy: () -> Unit,
) {
  val palette = aboutPalette()
  val groups = remember(raw) { deviceInfoGroups(raw) }
  val shape = RoundedCornerShape(20.dp)

  // 这层不铺任何不透明底色：主卡是一张会呼吸的彩色渐变玻璃卡，设备面板浮在它
  // 上面，只叠「极淡磨砂 + 一层随同一相位游走的柔光 + 顶部高光 + 玻璃内描边」，
  // 于是底下那片渐变照旧透出来，光斑跟着主卡一起起伏 —— 有渐变、会呼吸、有质感，
  // 却不再是闷在渐变里的一块浅蓝，也不是干巴巴一片分隔线。
  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(shape)
        .drawWithCache {
          // 呼吸光斑：与主卡共用 [breath]，但在面板范围内走一条更短的路径，
          // 看上去就是这层玻璃在跟着大卡一起「吸气 / 呼气」。
          val cx = size.width * (0.20f + 0.60f * breath)
          val cy = size.height * (0.12f + 0.72f * (1f - breath))
          val radius = size.maxDimension * 1.05f
          val frost = Color.White.copy(alpha = 0.09f)
          val glow =
            Brush.radialGradient(
              0f to Color.White.copy(alpha = 0.22f),
              0.5f to Color.White.copy(alpha = 0.07f),
              1f to Color.Transparent,
              center = Offset(cx, cy),
              radius = radius,
            )
          val sheen =
            Brush.verticalGradient(
              colors = listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
              startY = 0f,
              endY = size.height * 0.5f,
            )
          // 上亮下暗的细边：与主卡同一套玻璃语言，把面板从渐变里「托」起来。
          val edge =
            Brush.verticalGradient(
              colors =
                listOf(
                  Color.White.copy(alpha = 0.50f),
                  Color.White.copy(alpha = 0.06f),
                ),
            )
          val cr = CornerRadius(20.dp.toPx())
          onDrawBehind {
            drawRoundRect(color = frost, cornerRadius = cr)
            drawRoundRect(brush = glow, cornerRadius = cr)
            drawRoundRect(brush = sheen, cornerRadius = cr)
            drawRoundRect(brush = edge, cornerRadius = cr, style = Stroke(1.dp.toPx()))
          }
        }
        .padding(horizontal = 18.dp, vertical = 16.dp),
  ) {
    Column {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Outlined.Devices,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
          tint = palette.ink.copy(alpha = 0.9f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
          text = stringResource(R.string.i18n_device_info),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = palette.ink.copy(alpha = 0.95f),
          modifier = Modifier.weight(1f),
        )
        Row(
          modifier =
            Modifier
              .clip(RoundedCornerShape(percent = 50))
              .background(Color.White.copy(alpha = 0.16f))
              .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(percent = 50))
              .clickable(onClick = onCopy)
              .padding(horizontal = 12.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = palette.ink.copy(alpha = 0.85f),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "复制",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = palette.ink.copy(alpha = 0.9f),
          )
        }
      }

      if (groups.isEmpty()) {
        // 回退分支：解析不出键值就原文输出，宁可丑一点也不丢信息。
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = raw,
          style = MaterialTheme.typography.bodySmall,
          color = palette.ink.copy(alpha = 0.85f),
        )
      } else {
        groups.forEachIndexed { groupIndex, group ->
          if (groupIndex > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            // 浅色卡上深墨绿的细分隔线比白线更清楚；深色卡上反过来用浅薄荷。
            HorizontalDivider(color = palette.ink.copy(alpha = 0.16f))
          }
          Spacer(modifier = Modifier.height(14.dp))
          // 分组标题：左侧一根品牌色引导条 + 图标，是整块玻璃里唯一的有色点缀，
          // 让「分组」被一眼读出来，而不是靠一堆分隔线硬切。
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier =
                Modifier
                  .width(3.dp)
                  .height(14.dp)
                  .clip(RoundedCornerShape(2.dp))
                  .background(palette.accent),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
              imageVector = group.icon,
              contentDescription = null,
              modifier = Modifier.size(15.dp),
              tint = palette.ink.copy(alpha = 0.72f),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
              text = group.title,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              letterSpacing = 0.08.sp,
              color = palette.ink.copy(alpha = 0.72f),
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
          group.rows.forEach { row ->
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Text(
                text = row.label,
                style = MaterialTheme.typography.labelMedium,
                color = palette.ink.copy(alpha = 0.55f),
                modifier = Modifier.width(88.dp),
              )
              Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.ink.copy(alpha = 0.96f),
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }
    }
  }
}

/* ---------------- 操作区 ---------------- */

/**
 * 卡片内的并列按钮（玻璃式）。
 *
 * 不用 `Button` 的原因：默认底色是实心 primary，在这张带渐变的卡片上会压过卡片本身的色彩；
 * 而且两颗实心按钮并排会被读成「两个同等重要的主动作」。
 * 这里用 12% 同色底 + 16% 描边：既有按钮形状，又不抢主角，且浅色 / 深色主题下都自洽。
 */
@Composable
private fun AboutGlassButton(
  text: String,
  modifier: Modifier = Modifier,
  // 二选一：普通按钮给矢量图标；品牌按钮（GitHub）给单色 drawable，
  // 两者都随同一个 tint 着色，深浅主题自动适配。
  icon: ImageVector? = null,
  iconPainter: Painter? = null,
  onClick: () -> Unit,
) {
  val palette = aboutPalette()
  val shape = RoundedCornerShape(14.dp)
  val iconTint = palette.ink.copy(alpha = 0.9f)
  Box(
    modifier =
      modifier
        .height(48.dp)
        .clip(shape)
        .background(palette.ink.copy(alpha = 0.12f))
        .border(1.dp, palette.ink.copy(alpha = 0.16f), shape)
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      when {
        iconPainter != null ->
          Icon(
            painter = iconPainter,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = iconTint,
          )
        icon != null ->
          Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = iconTint,
          )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = palette.ink,
      )
    }
  }
}

/**
 * 卡片内的状态行：左标题、右当前值。
 *
 * 与设置页的行式列表同一语境：点一下就执行，不用再给一颗全宽按钮。
 * 右侧值是为了让「检查更新」能自带状态（当前版本 / 正在检查），
 * 否则点完只能等 Toast，看不出这一次到底有没有发生事。
 */
@Composable
private fun AboutActionRow(
  label: String,
  icon: ImageVector,
  value: String,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val palette = aboutPalette()
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick)
        .padding(vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.size(20.dp),
      tint = palette.ink.copy(alpha = if (enabled) 0.7f else 0.4f),
    )
    Spacer(modifier = Modifier.width(14.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = palette.ink.copy(alpha = if (enabled) 0.92f else 0.5f),
    )
    Spacer(modifier = Modifier.weight(1f))
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      color = palette.ink.copy(alpha = if (enabled) 0.62f else 0.38f),
    )
  }
}
