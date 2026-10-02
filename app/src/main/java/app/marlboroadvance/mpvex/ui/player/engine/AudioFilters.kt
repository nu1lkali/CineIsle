package app.marlboroadvance.mpvex.ui.player.engine

import app.marlboroadvance.mpvex.preferences.AudioChannels
import app.marlboroadvance.mpvex.preferences.AudioPreferences

/**
 * 音频滤镜链（mpv `af`）的统一组装。
 *
 * 背景：`af` 是一个**整串**属性，谁最后写谁生效。历史上音量均衡（`dynaudnorm`）和
 * 「反向立体声」（`pan=...`）各自直接 `setOptionString("af", ...)` / `setPropertyString("af", ...)`，
 * 于是两个功能互斥 —— 开了一个另一个就被静默抹掉。这里把两者拼成一条链，
 * 谁想改 `af` 都走这里，避免再互相覆盖。
 *
 * 注意：
 * - 返回**空串**表示这条链上没有任何滤镜。写空串给 mpv 是合法的（等于清空），
 *   调用方不要因为空串就跳过写入 —— 那会导致「关了开关，滤镜却还挂着」。
 * - 组装顺序固定为 `pan` → `dynaudnorm`：先交换声道再统一响度，
 *   与「先均衡后换声道」在听感上等价，但前者不会让 pan 把归一化后的增益再算一遍。
 */
object AudioFilters {
  /** 反向立体声：左右声道互换。与 [AudioChannels.ReverseStereo] 的值保持一致。 */
  private const val PAN_REVERSE_STEREO = "pan=[stereo|c0=c1|c1=c0]"

  /**
   * 按当前偏好组装完整的 `af` 字符串。
   *
   * @return 以逗号分隔的滤镜链；没有任何滤镜时返回空串。
   */
  fun buildAf(
    prefs: AudioPreferences,
    channels: AudioChannels,
  ): String {
    val parts = buildList {
      if (channels == AudioChannels.ReverseStereo) add(PAN_REVERSE_STEREO)
      if (prefs.volumeNormalization.get()) add(prefs.normalizationStrength.get().afFragment)
    }
    return parts.joinToString(",")
  }

  /**
   * 组装并写入 mpv 的 `af` 属性（运行时生效）。
   *
   * 初始化阶段请改用 [buildAf] 配合 `setOptionString`（属性在文件加载前不可写）。
   */
  fun applyAf(
    prefs: AudioPreferences,
    channels: AudioChannels,
  ) {
    // PlayerLib 内部对非 mpv 内核会自动 no-op，这里不必再判一次
    PlayerLib.setPropertyString("af", buildAf(prefs, channels))
  }
}
