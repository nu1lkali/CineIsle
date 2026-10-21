package app.marlboroadvance.mpvex.domain.emby

/**
 * 多版本媒体的**优先选择规则** —— 用户在设置里挑一条，
 * 进入播放时据此从 `MediaSources` 里自动挑一个版本，而不是每次都点下拉框。
 *
 * ## 为什么规则是「排序」而不是「过滤」
 *
 * 过滤（"只要 4K"）在库里没有 4K 时会**一条都不剩** ⇒ 播不了。
 * 排序则是「尽量满足，实在没有就给最好的」—— 任何情况下都有结果。
 * 所以每条规则都带一个方向：[preference] 表达"更想要什么"，
 * 排序键则按这个方向把候选排好序，取第一个。
 *
 * ## 缺失值怎么算
 *
 * 服务端对某些字段会返回 null（老版本没扫出分辨率、码率未知…）。
 * 一律当**最低**处理：排在有值的候选之后。
 * 绝不因为"缺信息"就把条目排除 —— 那会让规则变成隐式过滤（回到上面的坑）。
 */
enum class MediaSourcePreference(
  /** 设置项上显示的名字（**持久化用这个**，改名会让老用户设置丢失）。 */
  val key: String,
) {
  /** 不自动选，按服务端默认版本走（等于关掉这个功能）。 */
  DEFAULT("default"),

  /** 分辨率越高越好（4K > 1080P > 720P）。 */
  HIGHEST_RESOLUTION("highest_resolution"),

  /** 分辨率越低越省流量（适合移动网络）。 */
  LOWEST_RESOLUTION("lowest_resolution"),

  /** 码率越高画质越好。 */
  HIGHEST_BITRATE("highest_bitrate"),

  /** 码率越低越省流量。 */
  LOWEST_BITRATE("lowest_bitrate"),

  /** 体积最小。 */
  SMALLEST_SIZE("smallest_size"),

  /** 同分辨率下优先体积更小的版本（画质够就行，省流量）。 */
  BEST_QUALITY_PER_SIZE("best_quality_per_size"),
  ;

  companion object {
    fun fromKey(key: String?): MediaSourcePreference =
      entries.firstOrNull { it.key == key } ?: DEFAULT
  }
}

/**
 * 从 `MediaSources` 里按规则挑一个版本，**返回它的 `MediaSourceId`**。
 *
 * 纯逻辑、无 Android 依赖 ⇒ 能跑 JVM 单测。
 *
 * ⚠️ 返回的是 **Id 字符串**而不是 `EmbyMediaSource`：调用方（`effectiveEmbyMediaSourceId`）
 * 要的就是 id，而它同时还要从原列表里再取一次对象去算显示名 ——
 * 返回对象等于让调用方反向查 id 变回对象，多一次无谓的查找。
 *
 * @return 选中项的 `MediaSourceId`；候选不足或无法判定时返回 **null**
 *   （调用方据此回退到服务端默认 —— 红线 43c：
 *   服务端没回我们要的那个版本时返回 null，**不要退回首条**）。
 */
fun selectPreferredMediaSourceId(
  sources: List<EmbyMediaSource>,
  preference: MediaSourcePreference,
): String? {
  if (preference == MediaSourcePreference.DEFAULT) return null
  // 只有一条时没什么可选的，仍然返回 null 让走原来的路 —— 零改动。
  if (sources.size < 2) return null

  // 每条候选带上排序键。缺失值统一用 null，comparator 里按"最小"处理。
  val ranked =
    sources.map { src ->
      val video = src.MediaStreams?.firstOrNull { it.Type.equals("Video", true) }
      val height = video?.Height
      // 分辨率用**像素总数**而不是 Height：1080P(1920x1080) 与 720P 竖版
      // (1280x720) 光看 Height 会被竖版误导（720 < 1080 但可能总像素更少）。
      val pixels = if (video?.Width != null && height != null) video.Width.toLong() * height else null
      Candidate(
        source = src,
        id = src.Id,
        pixels = pixels,
        height = height,
        bitrate = src.Bitrate ?: video?.BitRate,
        size = src.Size,
      )
    }.filter { it.id != null }

  if (ranked.size < 2) return null

  // ⚠️ 必须显式给类型：`when` 里有一个分支是 `return null`（不是表达式），
  // 编译器无法从分支推断出 comparator 的类型，`minWithOrNull` 就找不到重载。
  val comparator: Comparator<Candidate> =
    when (preference) {
      MediaSourcePreference.HIGHEST_RESOLUTION ->
        compareBy<Candidate> { it.pixels ?: Long.MIN_VALUE }
          .thenBy { it.bitrate ?: Long.MIN_VALUE }
      MediaSourcePreference.LOWEST_RESOLUTION ->
        // 有值的排在缺失的**前面**（缺失当"最小"→ 反向取反即"最前"要小心）
        compareBy<Candidate> { if (it.pixels == null) 1 else 0 }
          .thenBy { it.pixels ?: Long.MAX_VALUE }
      MediaSourcePreference.HIGHEST_BITRATE ->
        compareBy<Candidate> { it.bitrate ?: Long.MIN_VALUE }
          .thenBy { it.pixels ?: Long.MIN_VALUE }
      MediaSourcePreference.LOWEST_BITRATE ->
        compareBy<Candidate> { if (it.bitrate == null) 1 else 0 }
          .thenBy { it.bitrate ?: Long.MAX_VALUE }
      MediaSourcePreference.SMALLEST_SIZE ->
        compareBy<Candidate> { if (it.size == null) 1 else 0 }
          .thenBy { it.size ?: Long.MAX_VALUE }
      MediaSourcePreference.BEST_QUALITY_PER_SIZE ->
        // "画质够就行、挑更小的"：以分辨率为主键分组不现实（要跨条目比较体积），
        // 这里取一个务实的近似 —— 像素数相同则选小的；像素数不同仍取像素多的
        // （否则"4K 的 20G" 会输给 "1080P 的 300M"，那不是用户想要的意思）。
        compareBy<Candidate> { -(it.pixels ?: Long.MIN_VALUE) }
          .thenBy { it.size ?: Long.MAX_VALUE }
      MediaSourcePreference.DEFAULT -> return null
    }

  return ranked.minWithOrNull(comparator)?.id
}
/** 比较用的中间结构。放在文件里而不是内联，避免闭包里造一堆 Pair。 */
private data class Candidate(
  val source: EmbyMediaSource,
  val id: String?,
  val pixels: Long?,
  val height: Int?,
  val bitrate: Long?,
  val size: Long?,
)
