package app.marlboroadvance.mpvex.ui.player.engine

/**
 * 画面旋转（顺时针 90° 步进）的纯逻辑与 mpv 属性封装。
 *
 * ## 为什么用 `video-rotate` 而不是 `vf rotate`
 *
 * 旧实现走 `vf add @mpvex_rotate:rotate=90:ow=rotw(90):oh=roth(90)`，在当前内核
 * （libmpv v0.41.0）上画面是**斜的**。根因是单位错配：
 *
 * - mpv v0.41 已无内置 `rotate` 滤镜，`vf=rotate=…` 经 lavfi-bridge 转给
 *   libavfilter 的 `rotate` 滤镜；
 * - libavfilter `rotate` 的角度单位是**弧度**，`rotate=90` 被当成 90 弧度，
 *   归一化后约 116.7°，于是画面明显倾斜；`rotw(90)/roth(90)` 同样按弧度算，
 *   输出尺寸也错。
 *
 * `video-rotate` 是 mpv 原生属性，规避了整类单位问题：
 *
 * - 角度单位是**度**，90° 步进精确；
 * - mpv 手册明确「该值**叠加**到容器旋转元数据之上」，`0` 即按容器元数据显示
 *   —— 不会覆盖手机竖拍自带的 `rotate=90`（旧设计担心的覆盖问题在 v0.41 上不成立）；
 * - mpv 自带菜单「Rotate clockwise」走的就是 `cycle-values video-rotate 90 180 270 0`。
 *
 * ## 换片行为
 *
 * `video-rotate` 是全局属性，同内核换片会保留，符合连播场景「拍摄方向通常一致、
 * 不必每集重转」的既有取舍。`applyRotationPolicy()` 在 `file-loaded` 以它为准
 * 校正内存状态；清理时写回 0。
 *
 * ## 宽高比
 *
 * `video-params/aspect` 只含容器旋转、**不含**用户的 `video-rotate`，所以
 * [MPVView.getVideoOutAspect] 仍需按本类的角度自行取倒数，不能算两遍容器旋转。
 *
 * ⚠️ 直出模式（`vo=mediacodec_embed`）下沿用原有 UI 限制：按钮不渲染、宫格入口
 * 明示拒绝。虽然 mpv 手册称 90° 步进在无 copy-back 的硬解上也能工作，但这里
 * 不扩大适用范围，避免把未验证的路径放给用户。
 */
object VideoRotate {
  /** mpv 原生旋转属性（度，叠加在容器旋转元数据之上）。 */
  const val PROPERTY: String = "video-rotate"

  /** 直出模式（零拷贝解码直出 Surface）下不开放旋转入口。 */
  fun isAvailable(directOutput: Boolean): Boolean = !directOutput

  /**
   * 把累计圈数折算成写入 `video-rotate` 的角度（度）。
   *
   * 恒落在 `0/90/180/270`；**直接写度数**，不要做任何弧度换算 —— 这正是旧版
   * `vf rotate` 倾斜的根因。
   */
  fun degreesOf(quarterTurns: Int): Int = (((quarterTurns % 4) + 4) % 4) * 90

  /** 角度 → 圈数，用于从 mpv 读回真实值后对齐内存状态。 */
  fun quarterTurnsOf(degrees: Int): Int = (((degrees % 360) + 360) % 360) / 90

  /** 应用某个角度。0° 写 0（等价于摘掉旋转），不留任何滤镜。 */
  fun apply(quarterTurns: Int) {
    PlayerLib.setPropertyInt(PROPERTY, degreesOf(quarterTurns))
  }

  /** 旋转复位到 0°。可在任意时刻重复调用。 */
  fun clear() {
    PlayerLib.setPropertyInt(PROPERTY, 0)
  }

  /**
   * 读 mpv 当前实际生效的旋转角度（度）；读不到返回 0。
   *
   * 只用于**校正内存状态**：内核被复用、外部脚本改过属性时，以这里读到的为准。
   * 归一化到 `0..359`（属性理论范围就是 0-359，防御性处理越界值）。
   */
  fun readAppliedDegrees(): Int {
    val raw = PlayerLib.getPropertyInt(PROPERTY) ?: return 0
    return ((raw % 360) + 360) % 360
  }
}

/**
 * 画面旋转的**累计状态**。不可变，便于单测与回滚。
 *
 * 语义是「顺时针步进 / 到 360° 归零」，不是有符号偏移 —— 用户心智里
 * 「转四次就转回来了」比「-90°」自然，也让 4 次点击后的画面与初始**像素级一致**
 * （属性写回 0，而不是留一个 360° 的等价旋转）。
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
