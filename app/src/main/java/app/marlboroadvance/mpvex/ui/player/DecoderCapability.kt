package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设备**视频硬件解码器**探测（「自动检测」两档的依据）。
 *
 * ## 它检测什么、不检测什么
 *
 * 检测的是**设备级**能力：这台机器上有没有能输出画面的硬件视频解码器（MediaCodec 里
 * `isHardwareAccelerated` 且不是编码器那些）。**不是逐片探测** —— 某条片子用什么编码、
 * 什么分辨率、什么 profile，要等开片后拿到 `track-list` 才知道，那是另一个时机的事
 * （`PlaybackColorPolicy` 处理杜比视界 P5 就是这么做的）。
 *
 * 所以这一层的结论只用于回答一个问题：**「这台机器值不值得把 `hwdec` 写成硬解」**。
 * 编码层面的回退仍交给 mpv 的 `,no` 兜底链 —— 两者是叠加关系，不是替代关系。
 *
 * ## 为什么值得单独探测
 *
 * 没有硬解器的设备（模拟器、少数廉价盒子）上，`hwdec=mediacodec` 配
 * `vo=mediacodec_embed` 会**直接黑屏**：mpv 拿不到解码器的 surface 输出，vo 又没有
 * 软解回退路径。这种情况下「自动检测」能把档位干净地落回软解，而不是让用户对着
 * 一块黑屏去猜是解码器的问题还是片源的问题。
 *
 * ## 成本
 *
 * `MediaCodecList(REGULAR_CODECS)` 要枚举全机的编解码器，几十毫秒量级 ——
 * 不能放在 `MPVView.initOptions`（主线程）。由 [app.marlboroadvance.mpvex.App] 在
 * `onCreate` 里丢到后台预热，播放器侧只读 [level] 的当前值。
 */
object DecoderCapability {
  private const val TAG = "DecoderCapability"

  /**
   * 设备级硬件解码能力。
   *
   * ⚠️ [Unknown] 的语义是「还没探测完」，消费方**按有硬解乐观处理** ——
   * 那正是旧版的固定行为，不会因为探测没跑完就让播放行为变差。
   */
  enum class Level {
    Unknown,
    Hardware,
    None,
  }

  private val _level = MutableStateFlow(Level.Unknown)
  val level: StateFlow<Level> = _level.asStateFlow()

  /** 探测到的硬件视频解码器个数；`-1` = 还没探测/探测失败。仅用于设置页展示。 */
  private val _hardwareDecoderCount = MutableStateFlow(-1)
  val hardwareDecoderCount: StateFlow<Int> = _hardwareDecoderCount.asStateFlow()

  @Volatile private var probed = false

  /** 幂等。多次调用只跑第一次，IO 线程调用。 */
  fun probe(context: Context) {
    if (probed) return
    probed = true
    val count =
      runCatching { countHardwareVideoDecoders() }.getOrElse {
        // 探测失败不该拖累播放：保持 Unknown ⇒ 消费方按有硬解处理（＝旧版行为）
        Log.w(TAG, "probe failed, keep Unknown", it)
        return
      }
    _hardwareDecoderCount.value = count
    _level.value = if (count > 0) Level.Hardware else Level.None
    Log.i(TAG, "hardware video decoders = $count => ${_level.value}")
  }

  private fun countHardwareVideoDecoders(): Int {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    return list.codecInfos.count { info ->
      !info.isEncoder &&
        isHardwareBacked(info) &&
        info.supportedTypes.any { it.startsWith("video/") }
    }
  }

  private fun isHardwareBacked(info: MediaCodecInfo): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      info.isHardwareAccelerated
    } else {
      // API 26~28 没有能力位，只能按命名排除 Google 的参考软解实现
      // （OMX.google.* 是 AOSP 自带的软件编解码器）。
      !info.name.startsWith("OMX.google.", ignoreCase = true)
    }
}
