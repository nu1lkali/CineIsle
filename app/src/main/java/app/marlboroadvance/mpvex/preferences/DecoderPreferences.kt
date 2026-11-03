package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import app.marlboroadvance.mpvex.ui.player.Debanding

class DecoderPreferences(
  preferenceStore: PreferenceStore,
) {
  val profile = preferenceStore.getString("mpv_profile", "fast")
  val tryHWDecoding = preferenceStore.getBoolean("try_hw_dec", true)

  /**
   * 渲染器：true = gpu-next（libplacebo），false = 旧版 gpu。
   *
   * **默认开启**（mpv 上游 0.37 起的默认渲染器也是它）。这直接决定「杜比视界能不能播」：
   * DV Profile 5 的 IPTPQc2 色彩没有 HDR10 兜底层，只有 gpu-next 的 libplacebo
   * 会读 HEVC 流里的 DV RPU 元数据做映射 —— 旧版 gpu 下播放 Profile 5 会整屏紫绿。
   * Profile 7 / 8 的 RPU 同样由 gpu-next 处理。愿意接受旧渲染器的用户仍可关掉。
   */
  val gpuNext = preferenceStore.getBoolean("gpu_next", true)
  val useVulkan = preferenceStore.getBoolean("use_vulkan", false)
  val useYUV420P = preferenceStore.getBoolean("use_yuv420p", false)

  /**
   * 直出模式：`vo=mediacodec_embed` + `hwdec=mediacodec`（**零拷贝**硬解）。
   *
   * ## 解决什么
   *
   * 默认的 `vo=gpu / gpu-next` 拿不到 mpv 的零拷贝硬解 —— `hwdec=mediacodec` 要求
   * `vo=mediacodec_embed`，所以实际生效的只有 `mediacodec-copy`：解码完把整帧
   * **回拷到 CPU 内存**，再由 vo 重新上传成纹理。4K 一帧 12.4 MB，
   * 2160×3840@60 就是每帧 12.4 MB 拷出 + 12.4 MB 上传 ≈ 1.5 GB/s，
   * 中端 SoC 直接吃满 ⇒ 画面像幻灯片。
   *
   * 直出模式把解码器的输出 buffer 直接交给 Surface，**这两次搬运都不存在**，
   * 与 MX Player / 系统播放器的路径一致。
   *
   * ## 代价（所以默认关）
   *
   * mpv 侧的所有叠加层全部失效：**外挂/内嵌字幕不显示**、OSD 不显示、
   * GLSL 着色器（Anime4K / 锐化）无效、`grabThumbnail` 截图失败。
   * 播放器界面本身是 Compose 独立图层，不受影响。
   *
   * ⚠️ 只在「解码器能直接吃下这个规格」时才有意义；若设备拒绝该流
   * （`hwdec-current` 落到 `no`），直出模式也救不了 —— 那是设备能力问题。
   */
  val directOutput = preferenceStore.getBoolean("direct_output", false)

  /**
   * 杜比视界 Profile 5 颜色修正（硬解路径下自动生效）。
   *
   * ## 根因：Android 硬解拿不到 DV RPU
   *
   * P5 是单层、**没有 HDR10 兼容层**的 IPTPQc2 流。要还原正确颜色，必须读码流里
   * 每帧的 DV RPU 元数据交给 gpu-next / libplacebo 做重塑；而 Android 的
   * MediaCodec 解码器**不会把 RPU 挂到输出帧上**（`AV_FRAME_DATA_DOVI_METADATA`
   * 只有软解路径拿得到）。所以 `hwdec=mediacodec-copy` 播 P5 必然整屏紫绿反色，
   * 这不是调色调映射参数能补的 —— 硬解帧上根本没有那份元数据。
   *
   * ## 做法：只让 P5 让路，不全局关硬解
   *
   * 开片时读 `track-list` 各条的 `dolby-vision-profile`，命中 5 就把 `hwdec` 临时改成
   * `no`（软解 + gpu-next 重塑）；换到非 P5 片源立刻恢复 [defaultHwdec]。
   * 于是普通片、Profile 7 / 8（8 有 HDR10 兜底层，硬解也正确）全部继续走硬解，
   * 只有真正会偏色的 P5 付软解代价 —— 这是「正确色彩」与「硬解性能」的分界线，
   * 用户也不必为了看一部 P5 手动去关全局硬解开关。
   *
   * ⚠️ 依赖 gpu-next（libplacebo 才解析 RPU），且直出模式
   *    （`vo=mediacodec_embed`，没有 GPU 渲染链）下无法生效。
   */
  val dolbyVisionColorFix = preferenceStore.getBoolean("dv_p5_color_fix", true)

  /**
   * HDR 显示输出。
   *
   * ## 旧实现为什么「开着没效果」
   *
   * 之前只给 mpv 设了 `target-colorspace-hint=yes`：那是 `vo=gpu-next` 的
   * libplacebo 提示，在 Android 上**只有 Vulkan 后端（`gpu-context=androidvk`）**
   * 会把它转成 Surface 的 HDR dataspace，默认 OpenGL 后端直接忽略。
   * 更要紧的是：它既不检查设备是否支持 HDR，也从不把 **Window** 切进 HDR
   * 色彩模式 —— 系统压根没收到「现在要按 HDR 输出」的信号，屏幕亮度自然不会变。
   *
   * ## 现在的实现
   *
   * 由 [app.marlboroadvance.mpvex.ui.player.PlaybackColorPolicy] 在片源首帧后
   * 判定（PQ / HLG / DV / 静态峰值元数据）并调
   * `Window.setColorMode(COLOR_MODE_HDR)`：这是 Android 官方让系统切换 HDR
   * 显示模式的接口（API 26+，本项目 minSdk 26）。设备不报告任何 HDR 类型时不开，
   * SDR 片源 / 退出播放页时恢复 `COLOR_MODE_DEFAULT`，避免 Compose 控件层被
   * 拉进 HDR 亮度后发白。`target-colorspace-hint` 保留为辅助（Vulkan 后端下继续生效）。
   *
   * 保留手动开关的理由：部分设备切进 HDR 后 SDR 画面或播放器控件会偏暗 / 发灰，
   * 这是屏幕与系统能力差异，只能由用户按观感决定。
   */
  val hdrEnabled = preferenceStore.getBoolean("hdr_enabled", true)

  val debanding = preferenceStore.getEnum("debanding", Debanding.None)
  val debandIterations = preferenceStore.getInt("deband_iterations", 1)
  val debandThreshold = preferenceStore.getInt("deband_threshold", 48)
  val debandRange = preferenceStore.getInt("deband_range", 16)
  val debandGrain = preferenceStore.getInt("deband_grain", 32)

  val brightnessFilter = preferenceStore.getInt("filter_brightness")
  val saturationFilter = preferenceStore.getInt("filter_saturation")
  val gammaFilter = preferenceStore.getInt("filter_gamma")
  val contrastFilter = preferenceStore.getInt("filter_contrast")
  val hueFilter = preferenceStore.getInt("filter_hue")
  val sharpnessFilter = preferenceStore.getInt("filter_sharpness")

  // Anime4K Preferences
  val enableAnime4K = preferenceStore.getBoolean("enable_anime4k", false)
  val anime4kMode = preferenceStore.getString("anime4k_mode", "OFF")
  val anime4kQuality = preferenceStore.getString("anime4k_quality", "FAST")

  /**
   * `hwdec` 的基准值：初始化（`MPVView.initOptions`）与「P5 修正结束后恢复」
   * 必须共用这一份口径。谁在别处再手写一遍三态，将来改了一边就会串味。
   */
  fun defaultHwdec(): String =
    when {
      directOutput.get() -> "mediacodec,no"
      tryHWDecoding.get() -> "mediacodec-copy,no"
      else -> "no"
    }
}
