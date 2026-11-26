package app.marlboroadvance.mpvex.ui.player.engine

/**
 * 画面旋转（顺时针 90° 步进）的纯逻辑与 mpv 滤镜封装。
 *
 * ## 为什么走 `vf` 而不是 `--video-rotate`
 *
 * `video-rotate` 是**启动选项语义**的属性，且与容器自带的旋转矩阵共用一个值 ——
 * 手机竖拍的片子本身就带 `rotate=90`，硬写进去会把它**覆盖**掉，之后再想还原就得
 * 自己记账原始值，边界很脏。挂在 `vf` 链上则是可热插拔、可按标签幂等移除，
 * 且不动容器元数据，与 [VideoCrop] / 镜像 / 垂直翻转完全同构。
 *
 * ## `ow` / `oh` 不能省
 *
 * 只写 `rotate=90` 的话滤镜只做仿射变换、**不重建输出尺寸** —— 结果是被裁掉一半
 * 或整体拉伸。必须显式给出 `ow=rotw(<deg>):oh=roth(<deg>)`，下游（以及
 * `video-out-params/dw|dh`）才能拿到一个真正「竖过来」的帧。
 *
 * ## 与其它 vf 的关系
 *
 * 用独立标签 [LABEL]，与 `mpe_cropdetect` / `mpe_crop` / `mpvex_hflip` /
 * `mpvex_vflip` 互不干扰；[apply] 会先移除旧标签再加，所以连点是幂等的。
 *
 * ⚠️ 直出模式（`vo=mediacodec_embed`）**根本没有滤镜链**，调用方必须先判断
 * [isAvailable]，否则 mpv 会报「该 vo 不支持滤镜」并把整条播放链推翻（黑屏）。
 */
object VideoRotate {
  /**
   * 滤镜标签。
   *
   * ⚠️ 不要以 `@` 开头 —— `vf add` 的标签参数里 `@` 是分隔符，传 `"@x:rotate=…"`
   * 时 mpv 自己会补上，标签名带 `@` 会变成 `@@x` 之类读不回来的怪名字。
   */
  const val LABEL: String = "mpvex_rotate"

  /** 直出模式（零拷贝解码直出 Surface）下没有滤镜链，旋转折不了。 */
  fun isAvailable(directOutput: Boolean): Boolean = !directOutput

  /**
   * 把累计圈数折算成 mpv 滤镜参数。
   *
   * @return 形如 `rotate=90:ow=rotw(90):oh=roth(90)`；0° 返回 null（调用方应改为移除滤镜）。
   */
  fun filterArg(quarterTurns: Int): String? {
    val degrees = degreesOf(quarterTurns)
    if (degrees == 0) return null
    return "rotate=$degrees:ow=rotw($degrees):oh=roth($degrees)"
  }

  /** 圈数 → 角度，恒落在 `0/90/180/270`（负数也归一）。 */
  fun degreesOf(quarterTurns: Int): Int = (((quarterTurns % 4) + 4) % 4) * 90

  /** 角度 → 圈数，用于从 mpv 读回真实值后对齐内存状态。 */
  fun quarterTurnsOf(degrees: Int): Int = (((degrees % 360) + 360) % 360) / 90

  /**
   * 应用某个角度。
   *
   * 幂等：先按标签移除（移除不存在的标签是无害的），再按需添加。
   * 0° 时只移除，不会留下一个空转的滤镜。
   */
  fun apply(quarterTurns: Int) {
    PlayerLib.command("vf", "remove", "@$LABEL")
    filterArg(quarterTurns)?.let { PlayerLib.command("vf", "add", "@$LABEL:$it") }
  }

  /** 摘掉旋转滤镜。可在任意时刻重复调用。 */
  fun clear() {
    PlayerLib.command("vf", "remove", "@$LABEL")
  }

  /**
   * 读 mpv 当前实际挂着的旋转角度（度）；读不到返回 0。
   *
   * 用途只有一个：**校正内存状态**。内存状态与内核实际状态不一致时（退出/换内核
   * 后被复用、外部脚本动了 vf 链），以这里读到的为准。轮询 `vf-metadata` 拿到的是
   * 滤镜参数表，`rotate` 键即为当前角度。
   */
  fun readAppliedDegrees(): Int {
    val keys = listOf("vf-metadata/$LABEL/rotate", "vf-metadata/@$LABEL/rotate")
    for (key in keys) {
      val raw = PlayerLib.getPropertyString(key)?.trim().orEmpty()
      if (raw.isEmpty()) continue
      // 可能是 "90" 也可能是 "90.000000"，统一按 Double 解析再取整
      val value = raw.toDoubleOrNull() ?: continue
      return ((value.toInt() % 360) + 360) % 360
    }
    return 0
  }
}

/**
 * 画面旋转的**累计状态**。不可变，便于单测与回滚。
 *
 * 语义是「顺时针步进 / 到 360° 归零」，不是有符号偏移 —— 用户心智里
 * 「转四次就转回来了」比「-90°」自然，也让 4 次点击后的画面与初始**像素级一致**
 * （滤镜被彻底移除，而不是挂一个 0° 的空 rotate）。
 */
data class RotationState(
  val quarterTurns: Int = 0,
) {
  val degrees: Int get() = VideoRotate.degreesOf(quarterTurns)

  val isRotated: Boolean get() = degrees != 0

  /** 顺时针再转 90°；从 270° 回到 0°。 */
  fun next(): RotationState = RotationState(((((quarterTurns + 1) % 4) + 4) % 4))

  fun reset(): RotationState = RotationState(0)

  companion object {
    val Identity: RotationState = RotationState(0)

    fun ofDegrees(degrees: Int): RotationState = RotationState(VideoRotate.quarterTurnsOf(degrees))
  }
}
