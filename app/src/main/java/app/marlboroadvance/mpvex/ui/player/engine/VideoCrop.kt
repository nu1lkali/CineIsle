package app.marlboroadvance.mpvex.ui.player.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 黑边自动裁切（基于 libavfilter 的 `cropdetect`）。
 *
 * 工作方式分两步，必须在运行时做（不是启动选项）：
 * 1. 往 `vf` 链里挂一条带标签的 `cropdetect`，它会在解码每一帧后更新元数据里的 `crop` 值；
 * 2. 轮询 mpv 的 `vf-metadata/<label>/crop` 属性读到 `w:h:x:y`，摘掉探测滤镜，
 *    再挂一条 `crop=w:h:x:y` 落地。
 *
 * 为什么不一次性挂 `cropdetect` 就完事：cropdetect 本身**不改变画面**，它只是测量；
 * 而且它会一直跑（每帧都在算），既费 CPU 又会让用户改分辨率后画面反复跳。
 * 所以探测到一次就摘掉。
 *
 * ⚠️ 兼容性：`vf-metadata/<label>/<key>` 这套属性是 mpv 0.34+ 才有的；
 * 老内核读不到值时本方法会**超时返回 null**，调用方据此提示「未能识别黑边」，
 * 不会挂上任何错误滤镜 —— 即失败是安全的、可回退的。
 *
 * ⚠️ 本对象只负责滤镜读写，不持有偏好。什么时候该裁、该不该自动裁，由调用方决定。
 */
object VideoCrop {
  private const val DETECT_LABEL = "mpe_cropdetect"
  private const val APPLY_LABEL = "mpe_crop"

  /** cropdetect 的 limit：低于该亮度的像素视为黑边。24 是 mpv 文档推荐的安全值。 */
  private const val DETECT_LIMIT = 24

  /** 轮询间隔与单次轮询的读取方式（毫秒）。 */
  private const val POLL_INTERVAL_MS = 200L

  /**
   * 当前是否挂着裁切滤镜。
   *
   * mpv 没有「按标签查 vf 链」的接口，所以只能自己记账。注意它**不保证**与 mpv 实际状态
   * 始终一致（比如外部脚本动了 vf 链），因此只在「需要避免重复 add」这类幂等判断里用它，
   * 真正的清理一律走 [clear]（重复 remove 不存在的标签是无害的）。
   */
  @Volatile
  var isApplied: Boolean = false
    private set

  /**
   * 探测画面有效区域并应用裁切。
   *
   * @param timeoutMs 探测最长等待时间；超时视为「未能识别」，不会挂任何滤镜。
   * @return 探测到的 crop 串（形如 `1920:816:0:132`）；失败返回 null。
   */
  suspend fun detectAndApply(timeoutMs: Long = 6000L): String? {
    clear()
    PlayerLib.command("vf", "add", "@$DETECT_LABEL:cropdetect=limit=$DETECT_LIMIT:round=2:reset=0")

    var detected: String? = null
    withTimeoutOrNull(timeoutMs) {
      while (detected == null) {
        detected = readDetectedCrop()
        if (detected == null) delay(POLL_INTERVAL_MS)
      }
    }

    // 无论成败都先摘掉探测滤镜，避免它一直跑着占 CPU / 污染日志
    PlayerLib.command("vf", "remove", "@$DETECT_LABEL")

    val crop = detected
    if (crop != null) {
      PlayerLib.command("vf", "add", "@$APPLY_LABEL:crop=$crop")
      isApplied = true
    } else {
      isApplied = false
    }
    return crop
  }

  /** 摘掉探测与裁切两条滤镜。可在任意时刻重复调用。 */
  fun clear() {
    PlayerLib.command("vf", "remove", "@$DETECT_LABEL")
    PlayerLib.command("vf", "remove", "@$APPLY_LABEL")
    isApplied = false
  }

  /**
   * 读一次 cropdetect 元数据。
   *
   * 标签在 `vf-metadata` 属性路径里带不带 `@` 在 mpv 各版本间不一致，两个都试一遍；
   * 全黑 / 全屏时 cropdetect 会给出 `0:0:0:0`，这不算有效值。
   */
  private fun readDetectedCrop(): String? {
    val keys =
      listOf(
        "vf-metadata/$DETECT_LABEL/crop",
        "vf-metadata/@$DETECT_LABEL/crop",
      )
    for (key in keys) {
      val value = PlayerLib.getPropertyString(key)?.trim().orEmpty()
      if (value.isNotEmpty() && value != "0:0:0:0") return value
    }
    return null
  }
}
