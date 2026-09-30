package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.GsyPreferences
import app.marlboroadvance.mpvex.preferences.GsyFilterKind
import app.marlboroadvance.mpvex.preferences.GsyKernelKind
import app.marlboroadvance.mpvex.preferences.GsyRenderKind
import app.marlboroadvance.mpvex.preferences.GsyShowKind
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.FooterPreference
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject

/**
 * GSYVideoPlayer 的独立设置页。
 *
 * 为什么单开一页：
 * GSY 现在是**独立的一套播放页**（[app.marlboroadvance.mpvex.ui.player.GsyPlayerActivity]），
 * 底下还挂着 IJK / System / ExoPlayer 三种解码内核、三种渲染载体、GL 滤镜等等。
 * 这些开关和 mpv 播放页的同名功能（hwdec / 滤镜链 / 字幕排版）在语义上并不一一对应，
 * 放在同一个「播放器设置」里只会互相误导 —— 所以两边各占一页，各管各的：
 *
 *   · 这一页的每一项都写进 `GsyPreferences`（`gsy_` 前缀的 key），只影响 GSY 播放页；
 *   · mpv 那一页的每一项都写进 `PlayerPreferences`，只影响 mpv 播放页。
 *
 * 每一项都对应 GSY 官方文档里的一个 setter，见 `GsyPreferences` 的注释；
 * 没有官方对应物的（比如「滤镜」需要 GL 渲染）会在 summary 里写清前提条件。
 */
@Serializable
object GsyPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val prefs = koinInject<GsyPreferences>()
    val playerPrefs = koinInject<PlayerPreferences>()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_gsy_player),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        LazyColumn(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          // ────────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = "入口与内核") }

          item {
            PreferenceCard {
              // 这两项原本在 mpv 的「播放器」设置页里，已整块搬过来 ——
              // 「长按用哪个内核播」说的就是「长按进不进 GSY 播放页」。
              val playbackEngine by playerPrefs.playbackEngine.collectAsState()
              ListPreference(
                value = playbackEngine,
                onValueChange = { playerPrefs.playbackEngine.set(it) },
                title = { Text("默认播放内核") },
                values = listOf("MPV", "GSY"),
                valueToText = { value ->
                  AnnotatedString(if (value == "GSY") "GSYVideoPlayer" else "mpv")
                },
                summary = {
                  Text(
                    text = if (playbackEngine == "GSY") {
                      "默认用 GSY 播放页。GSY 是独立播放页，配置见本页；" +
                        "mpv 播放页的滤镜 / 字幕排版 / 解码器切换 / 章节在 GSY 下不可用"
                    } else {
                      "默认用 mpv 播放页（功能最全）；想临时用 GSY 就打开下面的「长按切换」"
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val longPressReverse by playerPrefs.longPressReverseEngine.collectAsState()
              SwitchPreference(
                value = longPressReverse,
                onValueChange = playerPrefs.longPressReverseEngine::set,
                title = { Text("长按用另一个内核播放") },
                summary = {
                  Text(
                    text =
                      if (longPressReverse) {
                        val base = if (playbackEngine == "GSY") "GSYVideoPlayer" else "mpv"
                        val other = if (playbackEngine == "GSY") "mpv" else "GSYVideoPlayer"
                        "当前默认 $base，所以长按视频会用 $other 打开。" +
                          "自建：某片在 mpv 里播不动时，长按即可换一套解码链路，不用改默认设置"
                      } else {
                        "关闭后长按与单击一致，都用上面选的默认内核"
                      },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val kernel by prefs.kernel.collectAsState()
              ListPreference(
                value = kernel,
                onValueChange = { prefs.kernel.set(it) },
                title = { Text("GSY 解码内核") },
                values = GsyKernelKind.entries,
                valueToText = { AnnotatedString(it.title) },
                summary = {
                  Text(
                    text = "PlayerFactory.setPlayManager，改完**下次起播**生效（当前：${kernel.title}）",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val hardwareDecode by prefs.hardwareDecode.collectAsState()
              SwitchPreference(
                value = hardwareDecode,
                onValueChange = { prefs.hardwareDecode.set(it) },
                title = { Text("硬解码（IJK MediaCodec）") },
                summary = {
                  Text(
                    text = "官方 GSYVideoType.enableMediaCodec()。硬解更省电，遇到花屏 / 黑屏时关掉可回软解",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val smartFallback by prefs.smartFallback.collectAsState()
              SwitchPreference(
                value = smartFallback,
                onValueChange = { prefs.smartFallback.set(it) },
                title = { Text("硬解失败自动回退软解") },
                summary = {
                  Text(
                    text = "官方 enableSmartMediaCodec()：仅在 IJK 硬解明确失败时重建一次软解",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val overrideExtension by prefs.overrideExtension.collectAsState()
              ListPreference(
                value = overrideExtension,
                onValueChange = { prefs.overrideExtension.set(it) },
                title = { Text("强制解封装器（Exo 内核）") },
                values = listOf("", "m3u8", "mpd", "ism"),
                valueToText = { AnnotatedString(if (it.isEmpty()) "自动" else it) },
                summary = {
                  Text(
                    text = "官方 setOverrideExtension。Exo 内核下地址没扩展名、或 DASH 解析失败时手动指定",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // ────────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = "画面") }

          item {
            PreferenceCard {
              val renderKind by prefs.renderKind.collectAsState()
              ListPreference(
                value = renderKind,
                onValueChange = { prefs.renderKind.set(it) },
                title = { Text("渲染方式") },
                values = GsyRenderKind.entries,
                valueToText = { AnnotatedString(it.title) },
                summary = {
                  Text(
                    text = "官方 GSYVideoType.setRenderType（全局，起播前生效）。" +
                      "要用「滤镜」必须先选 GLSurfaceView；列表 / 弹窗场景保持 TextureView 最稳",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showKind by prefs.showKind.collectAsState()
              ListPreference(
                value = showKind,
                onValueChange = { prefs.showKind.set(it) },
                title = { Text("显示比例") },
                values = GsyShowKind.entries,
                valueToText = { AnnotatedString(it.title) },
                summary = {
                  Text(
                    text = "官方 GSYVideoType.setShowType（全局）。" +
                      "竖屏视频建议留在「自适应」+ 打开下面的「竖屏视频自动竖屏全屏」，强行裁剪会切掉画面",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val filter by prefs.filter.collectAsState()
              ListPreference(
                value = filter,
                onValueChange = { prefs.filter.set(it) },
                title = { Text("画面滤镜（GL）") },
                values = GsyFilterKind.entries,
                valueToText = { AnnotatedString(it.title) },
                summary = {
                  Text(
                    text = "官方 setEffectFilter，内置效果取自 SDK 的 render/effect。" +
                      "硬前提：必须用 GLSurfaceView 渲染 —— 渲染方式不是 GL 时，播放页的滤镜按钮会自动切过去并重开播放页",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // ────────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = "播放行为") }

          item {
            PreferenceCard {
              val looping by prefs.looping.collectAsState()
              SwitchPreference(
                value = looping,
                onValueChange = { prefs.looping.set(it) },
                title = { Text("循环播放") },
                summary = { Text(text = "官方 setLooping", color = MaterialTheme.colorScheme.outline) },
              )

              PreferenceDivider()

              val defaultSpeed by prefs.defaultSpeed.collectAsState()
              ListPreference(
                value = defaultSpeed,
                onValueChange = { prefs.defaultSpeed.set(it) },
                title = { Text("起播倍速") },
                values = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f),
                valueToText = { AnnotatedString("${it}x") },
                summary = {
                  Text(
                    text = "官方 setSpeed。播放页底部的玻璃「倍速」按钮可以现场循环切换",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val soundTouch by prefs.soundTouch.collectAsState()
              SwitchPreference(
                value = soundTouch,
                onValueChange = { prefs.soundTouch.set(it) },
                title = { Text("变速不变调") },
                summary = {
                  Text(
                    text = "官方 setSoundTouch。倍速播放时保持音调（更吃性能）；System 内核不支持",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val cacheWithPlay by prefs.cacheWithPlay.collectAsState()
              SwitchPreference(
                value = cacheWithPlay,
                onValueChange = { prefs.cacheWithPlay.set(it) },
                title = { Text("边播边缓存") },
                summary = {
                  Text(
                    text = "官方 setUp 的 cacheWithPlay（ProxyCacheManager）。" +
                      "网络流缓存后回看更顺；Exo 内核播 DASH / m3u8 时建议关掉",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val resumePosition by prefs.resumePosition.collectAsState()
              SwitchPreference(
                value = resumePosition,
                onValueChange = { prefs.resumePosition.set(it) },
                title = { Text("续播到上次位置") },
                summary = {
                  Text(
                    text = "官方 setSeekOnStart。来源带播放进度时从该位置起播；带「从头播放」标记时忽略",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val closeAfterEnd by prefs.closeAfterEnd.collectAsState()
              SwitchPreference(
                value = closeAfterEnd,
                onValueChange = { prefs.closeAfterEnd.set(it) },
                title = { Text("播完自动退出播放页") },
                summary = {
                  Text(
                    text = "官方 VideoAllCallBack.onAutoComplete。关闭则停在最后一帧",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val muteOnStart by prefs.muteOnStart.collectAsState()
              SwitchPreference(
                value = muteOnStart,
                onValueChange = { prefs.muteOnStart.set(it) },
                title = { Text("进入即静音") },
                summary = {
                  Text(
                    text = "官方 GSYVideoManager.setNeedMute。播放页底部的玻璃「静音」按钮可随时切换",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // ────────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = "手势") }

          item {
            PreferenceCard {
              val touchGesture by prefs.touchGesture.collectAsState()
              SwitchPreference(
                value = touchGesture,
                onValueChange = { prefs.touchGesture.set(it) },
                title = { Text("非全屏响应手势") },
                summary = {
                  Text(
                    text = "官方 setIsTouchWiget：左侧上下滑调亮度、右侧上下滑调音量、横滑拖动进度",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val touchGestureFull by prefs.touchGestureFull.collectAsState()
              SwitchPreference(
                value = touchGestureFull,
                onValueChange = { prefs.touchGestureFull.set(it) },
                title = { Text("全屏响应手势") },
                summary = {
                  Text(
                    text = "官方 setIsTouchWigetFull。全屏时手势更顺手，一般保持开启",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val seekRatio by prefs.seekRatio.collectAsState()
              SliderPreference(
                value = seekRatio,
                onValueChange = { prefs.seekRatio.set(it) },
                title = { Text("横滑 seek 灵敏度") },
                valueRange = 0.5f..3f,
                summary = {
                  Text(
                    text = "官方 setSeekRatio：越大，横滑同样距离跳得越多（当前 ${"%.2f".format(seekRatio)}）",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
                onSliderValueChange = { prefs.seekRatio.set(it) },
                sliderValue = seekRatio,
              )

              PreferenceDivider()

              val wifiTip by prefs.wifiTip.collectAsState()
              SwitchPreference(
                value = wifiTip,
                onValueChange = { prefs.wifiTip.set(it) },
                title = { Text("移动网络提示") },
                summary = {
                  Text(
                    text = "官方 setNeedShowWifiTip：用流量播放前先问一句",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // ────────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = "控件") }

          item {
            PreferenceCard {
              val dismiss by prefs.dismissControlTimeMs.collectAsState()
              ListPreference(
                value = dismiss,
                onValueChange = { prefs.dismissControlTimeMs.set(it) },
                title = { Text("控件自动隐藏") },
                values = listOf(2000, 3000, 4000, 5000, 8000, 15000),
                valueToText = { AnnotatedString("${it / 1000} 秒") },
                summary = {
                  Text(
                    text = "官方 setDismissControlTime。播放中无操作多久后收起控件条",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val needLockFull by prefs.needLockFull.collectAsState()
              SwitchPreference(
                value = needLockFull,
                onValueChange = { prefs.needLockFull.set(it) },
                title = { Text("全屏显示屏幕锁") },
                summary = {
                  Text(
                    text = "官方 setNeedLockFull。全屏时右侧出现锁按钮，防止误触",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showDragText by prefs.showDragProgressText.collectAsState()
              SwitchPreference(
                value = showDragText,
                onValueChange = { prefs.showDragProgressText.set(it) },
                title = { Text("拖动进度条时显示目标时间") },
                summary = {
                  Text(
                    text = "官方 setShowDragProgressTextOnSeekBar",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showPauseCover by prefs.showPauseCover.collectAsState()
              SwitchPreference(
                value = showPauseCover,
                onValueChange = { prefs.showPauseCover.set(it) },
                title = { Text("暂停保留最后一帧") },
                summary = {
                  Text(
                    text = "官方 setShowPauseCover。关掉后暂停画面可能变黑",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // ────────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = "全屏与旋转") }

          item {
            PreferenceCard {
              val lockLand by prefs.lockLand.collectAsState()
              SwitchPreference(
                value = lockLand,
                onValueChange = { prefs.lockLand.set(it) },
                title = { Text("全屏锁横屏") },
                summary = { Text(text = "官方 setLockLand", color = MaterialTheme.colorScheme.outline) },
              )

              PreferenceDivider()

              val onlyRotateLand by prefs.onlyRotateLand.collectAsState()
              SwitchPreference(
                value = onlyRotateLand,
                onValueChange = { prefs.onlyRotateLand.set(it) },
                title = { Text("只允许横屏方向变化") },
                summary = {
                  Text(
                    text = "官方 setOnlyRotateLand：全屏时只在左右横屏之间翻转，不会掉回竖屏",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val autoFullWithSize by prefs.autoFullWithSize.collectAsState()
              SwitchPreference(
                value = autoFullWithSize,
                onValueChange = { prefs.autoFullWithSize.set(it) },
                title = { Text("竖屏视频自动竖屏全屏") },
                summary = {
                  Text(
                    text = "官方 setAutoFullWithSize：竖拍的片子点全屏不会硬转成横屏",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val rotateWithSystem by prefs.rotateWithSystem.collectAsState()
              SwitchPreference(
                value = rotateWithSystem,
                onValueChange = { prefs.rotateWithSystem.set(it) },
                title = { Text("跟随系统「自动旋转」") },
                summary = {
                  Text(
                    text = "关闭（默认）：播放页内始终响应重力感应，转手机就能转屏；" +
                      "开启：系统「自动旋转」一关，播放页也不再随重力旋转",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val fullAnimation by prefs.showFullAnimation.collectAsState()
              SwitchPreference(
                value = fullAnimation,
                onValueChange = { prefs.showFullAnimation.set(it) },
                title = { Text("全屏过渡动画") },
                summary = {
                  Text(
                    text = "官方 setShowFullAnimation。用 SurfaceView 渲染时动画会失效，属正常",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val fullHideStatusBar by prefs.fullHideStatusBar.collectAsState()
              SwitchPreference(
                value = fullHideStatusBar,
                onValueChange = { prefs.fullHideStatusBar.set(it) },
                title = { Text("全屏隐藏状态栏") },
                summary = {
                  Text(
                    text = "官方 setFullHideStatusBar",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val hideKey by prefs.hideKey.collectAsState()
              SwitchPreference(
                value = hideKey,
                onValueChange = { prefs.hideKey.set(it) },
                title = { Text("全屏隐藏虚拟按键") },
                summary = {
                  Text(
                    text = "官方 setHideKey。全屏时把三大金刚 / 手势条隐掉，画面更完整",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }

          // ────────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = "播放页系统栏") }

          item {
            PreferenceCard {
              val showStatusBar by prefs.showSystemStatusBar.collectAsState()
              SwitchPreference(
                value = showStatusBar,
                onValueChange = { prefs.showSystemStatusBar.set(it) },
                title = { Text("显示系统状态栏") },
                summary = {
                  Text(
                    text = "开着时顶栏会落在状态栏下方（避让刘海 / 挖孔）；关掉则顶栏贴屏幕上沿",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val showNavBar by prefs.showSystemNavigationBar.collectAsState()
              SwitchPreference(
                value = showNavBar,
                onValueChange = { prefs.showSystemNavigationBar.set(it) },
                title = { Text("显示系统导航栏") },
                summary = {
                  Text(
                    text = "开着时底部控件条会抬到手势条上方，避免进度条被压住",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              FooterPreference(
                summary = {
                  Text(
                    text = "本页全部设置只作用于 GSY 播放页，不会改变 mpv 播放页的任何表现；" +
                      "mpv 的设置见「播放与控件 → 播放器」",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )
            }
          }
        }
      }
    }
  }
}
