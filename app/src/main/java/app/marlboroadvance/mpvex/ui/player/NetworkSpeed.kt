package app.marlboroadvance.mpvex.ui.player

import app.marlboroadvance.mpvex.ui.player.engine.PlayerLib

/**
 * 播放器「网络加载速度」显示的共享工具：格式化、平滑、mpv 采样。
 *
 * mpv 与 GSY 两个内核的数据来源不同（见各自的采样实现），但「单位自适应的文案」
 * 和「消除离谱波动的平滑」是同一套逻辑，收在这里。
 */

/**
 * 把字节/秒格式化成「1.2 MB/s / 850 KB/s / 964 B/s」这类单位自适应的文案。
 *
 * 与 GSY 自带的 `getNetSpeedText()` 保持同一口径（KB 一位小数、MB 两位小数），
 * 但独立实现：mpv 侧拿不到 GSY 的 Converter，两边共用这一个函数显示才一致。
 */
fun formatNetworkSpeed(bytesPerSec: Long): String {
  if (bytesPerSec < 0) return "0 KB/s"
  val kb = 1024L
  val mb = kb * 1024
  val gb = mb * 1024
  return when {
    bytesPerSec < kb -> "$bytesPerSec B/s"
    bytesPerSec < mb -> String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSec.toDouble() / kb)
    bytesPerSec < gb -> String.format(java.util.Locale.US, "%.2f MB/s", bytesPerSec.toDouble() / mb)
    else -> String.format(java.util.Locale.US, "%.2f GB/s", bytesPerSec.toDouble() / gb)
  }
}

/**
 * 瞬时速度的平滑器。
 *
 * 底层速度（mpv 的 cache-speed / GSY 的 TcpSpeed）是按「最近一次读到的字节数」算的，
 * 网络稍有抖动就会在 0 和峰值之间跳——直接显示会一秒一个数，观感很糟。两层处理：
 *
 * 1. **尖峰钳制**：单次采样超过当前均值 4 倍 + 2MB/s 时，按「均值 × 2」参与计算，
 *    不让一次突发把显示顶上天（真正的持续高带宽会在后续几秒被均值追上）；
 * 2. **指数滑动平均（EMA，α=0.35）**：新值占 35%、历史占 65%，等效约 3 秒的惯性，
 *    既压平毛刺，又能让「真的断流」在一两秒内明显掉下来。
 */
class NetworkSpeedSmoother {
  private var ema = 0L
  private var hasValue = false

  /** 喂入一个瞬时采样（字节/秒），返回应显示的平滑值 */
  fun smooth(sampleBytesPerSec: Long): Long {
    var sample = sampleBytesPerSec
    if (sample < 0) sample = 0
    var accepted = sample
    if (hasValue && accepted > ema * 4 + 2L * 1024 * 1024) {
      accepted = ema * 2
    }
    ema = if (!hasValue) {
      hasValue = true
      accepted
    } else {
      (accepted * EMA_ALPHA + ema * (1.0 - EMA_ALPHA)).toLong()
    }
    return ema
  }

  /** 换片 / 停止时重置：上一条的残留速度不应带进下一条 */
  fun reset() {
    ema = 0L
    hasValue = false
  }

  companion object {
    private const val EMA_ALPHA = 0.35
  }
}

/**
 * mpv 内核的网速采样器。
 *
 * 数据源优先级（见 PlayerViewModel 里的轮询循环）：
 * 1. `cache-speed`（int64，字节/秒）：mpv 官方属性，「demuxer 正在从网络读数据的速度」，
 *    缓存满了停止下载时为 0 —— 语义与「当前网络加载速度」完全一致；
 * 2. 兜底 `demuxer-cache-state`（node）：个别 mpv 构建不带 cache-speed，
 *    退回「1 秒内向前缓冲区字节数（fw-bytes）的增量」自己算瞬时速度。
 *
 * 两个都拿不到（本地文件上 cache-speed 恒为 0 是正常值，不算拿不到）返回 0。
 */
object MpvNetSpeedSampler {
  private var lastFwBytes = -1L
  private var lastTimestampMs = 0L
  private val smoother = NetworkSpeedSmoother()

  /**
   * 采样一次。返回字节/秒；null 表示「当前不适用」（内核不是 mpv / 流不是网络流），
   * 调用方据此隐藏指示器。
   */
  fun sample(
    isMpv: Boolean,
    isPaused: Boolean,
  ): Long? {
    if (!isMpv) return null

    // 只对网络流显示：本地文件 / 本地内容 Uri 上这个指示器没有意义，恒隐藏。
    // `stream-open-filename` 是 mpv 实际打开的原始地址（Emby 直连、strm 解析后都是它）。
    val path = PlayerLib.getPropertyString("stream-open-filename")
      ?: PlayerLib.getPropertyString("path")
    if (path == null || !(path.startsWith("http://") || path.startsWith("https://"))) {
      return null
    }

    // 暂停：显示归零（策略见 PlayerViewModel 注释），采样基线也重置，
    // 避免恢复播放的第一秒把暂停期间攒下的读取量算成瞬时速度
    if (isPaused) {
      resetBaseline()
      return smoother.smooth(0)
    }

    // 主路径：cache-speed 直接给字节/秒
    val cacheSpeed = PlayerLib.getPropertyInt("cache-speed")
    val raw = if (cacheSpeed != null && cacheSpeed >= 0) {
      cacheSpeed.toLong()
    } else {
      // 兜底：fw-bytes（向前缓冲字节数）的 1 秒增量 ÷ 实际间隔。
      // seek 会把缓冲区整段换掉（fw-bytes 骤降/跳变），负增量跳过不计。
      sampleFromCacheState()
    }
    return smoother.smooth(raw)
  }

  /** 换片时调用：清掉上一条流的采样基线与平滑历史 */
  fun reset() {
    resetBaseline()
    smoother.reset()
  }

  private fun resetBaseline() {
    lastFwBytes = -1L
    lastTimestampMs = 0L
  }

  private fun sampleFromCacheState(): Long {
    val node = runCatching { PlayerLib.getPropertyNode("demuxer-cache-state") }.getOrNull()
      ?: return 0
    val fwBytes = runCatching {
      node.asMap()?.get("fw-bytes")?.asInt() ?: 0L
    }.getOrDefault(0L)

    val now = android.os.SystemClock.elapsedRealtime()
    val prevBytes = lastFwBytes
    val prevTs = lastTimestampMs
    lastFwBytes = fwBytes
    lastTimestampMs = now

    if (prevBytes < 0 || prevTs == 0L) return 0
    val delta = fwBytes - prevBytes
    if (delta <= 0) return 0
    val dt = (now - prevTs).coerceAtLeast(1L)
    return delta * 1000 / dt
  }
}
