package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload

/**
 * 上传速度的滑动窗口估算器。
 *
 * ## 为什么不是「两个采样点相减」
 *
 * 老实现每秒拿一次 `sentBytes` 增量当瞬时速度，再用 `(旧 × 2 + 新) / 3` 做低通。
 * 两个毛病：
 *
 * 1. **起播那一两秒是被污染的**。`sentBytes` 计的是「从本地流读出来、写进 OkHttp
 *    缓冲」的字节数，TCP 慢启动 + 内核发送缓冲会在第一秒内吞掉好几 MB —— 瞬时速度
 *    能报到几十 MB/s，跟真实链路速度差一个数量级。低通只把新值按 1/3 权重并进去，
 *    要十几秒才把这个假尖峰洗掉，中间界面一直挂着错得离谱的剩余时间。
 * 2. **单点采样没有任何容错**。某一秒刚好撞上服务端处理（AList 落盘、nextcloud
 *    分片合并）就会采到 0，速度直接掉一半再慢慢爬。
 *
 * ## 现在的算法
 *
 * - 保留最近 [windowMs] 毫秒内的采样点（`(时刻, 已发字节)`），窗口跨度的**平均**就是
 *   「一秒能推多少」——等价于给速度做了一次固定长度的滑动平均，天然抗单点抖动，
 *   又能在网络真变了之后 [windowMs] 内收敛。
 * - 窗口跨度还不足 [warmupMs] 时不给结论（`speedBps = 0`）：起播的数据量里混着
 *   建连和缓冲填装，拿它算剩余时间只会得到一个荒谬的数。
 * - 窗口值明显高于**整批平均**（> 2.5 倍，且已经有 4 秒以上的历史）时按整批平均算：
 *   那是缓冲还在灌，不是链路真的快了。这一条把「一开始说 5 秒、下一秒变 5 分钟」
 *   的来回跳压掉。
 * - 采样回退（重试某个文件会把它的 `sentBytes` 清零）时把历史清空重新计数，
 *   否则会算出负速度。
 *
 * 时刻用 [android.os.SystemClock.elapsedRealtime]，用户改系统时间不会把速度算崩。
 *
 * 纯逻辑、无 Android 依赖，可单测。
 */
internal class UploadSpeedometer(
  private val windowMs: Long = 6_000L,
  private val warmupMs: Long = 2_000L,
) {
  private class Mark(val at: Long, val sent: Long)

  private val marks = ArrayDeque<Mark>()

  /** 整批的第一笔记录，用来算「整批平均」 */
  private var originAt = -1L
  private var originSent = 0L

  /** 记一次采样。[at] 必须是单调时钟（毫秒） */
  fun sample(at: Long, sent: Long) {
    val last = marks.lastOrNull()
    if (last == null || sent < last.sent) {
      // 换文件/重试会让 sentBytes 回退：历史全部作废，从这里重新算
      marks.clear()
      originAt = at
      originSent = sent
    }
    marks.addLast(Mark(at, sent))
    // 丢掉完全落在窗口外的点，但要留一个「窗口起点之前」的点 —— 否则窗口覆盖率不足，
    // 每次丢点都会让跨度骤减、速度忽高忽低
    while (marks.size >= 2 && marks[1].at <= at - windowMs) marks.removeFirst()
    // 兜底：采样间隔异常小（比如以后改成高频采样）时别把队列撑爆
    while (marks.size > 240) marks.removeFirst()
  }

  /** 一秒推多少字节；数据还不够或完全没在动时为 0 */
  val speedBps: Long
    get() {
      val head = marks.firstOrNull() ?: return 0
      val tail = marks.lastOrNull() ?: return 0
      val span = tail.at - head.at
      if (span < warmupMs) return 0
      val window = (tail.sent - head.sent) * 1000 / span
      if (window <= 0) return 0
      val overallSpan = tail.at - originAt
      if (overallSpan >= span + BURST_GUARD_HISTORY_MS) {
        val overall = (tail.sent - originSent) * 1000 / overallSpan
        // 窗口值高得离谱 ⇒ 是缓冲在灌，按整批平均走
        if (overall > 0 && window > overall * 5 / 2) return overall
      }
      return window
    }

  /** 还差 [remainingBytes] 字节时预计要多少**秒**；速度还不可信时返回 null */
  fun etaSeconds(remainingBytes: Long): Long? {
    if (remainingBytes <= 0) return 0L
    val speed = speedBps
    if (speed <= 0) return null
    return remainingBytes / speed
  }

  private companion object {
    /** 窗口之外还留着多久的历史，才允许拿「整批平均」当参照 */
    const val BURST_GUARD_HISTORY_MS = 4_000L
  }
}
