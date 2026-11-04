package app.marlboroadvance.mpvex.ui.player.feed

/**
 * 视界流里一条片子**起播时**该交给哪个内核。
 *
 * ── 为什么要在起播前就判一次 ──
 *
 * 内置内核（ExoPlayer + NextLib）的失败是「两段式」的：先由解复用器判断容器认不认识，
 * 再由渲染器判断有没有解码器。两段都在起播后才发现，用户付出的代价是
 * 「转圈 → 黑一下 → 又转圈 → 才切 mpv」。而这两件事在 `FeedItem` 的元数据里
 * （`container` / `videoCodec`）其实**起播前就知道了**。
 *
 * ⚠️ 这里的判据刻意**只收「必败」的组合**，不追求「尽量走 mpv」：
 * mpv 是进程级单例旁的旁路，滑到它时只能现 load，没有相邻页预缓冲，
 * 起播比 ExoPlayer 慢。预判错一次（本该走内置内核却被丢给 mpv）就是实打实的手感倒退，
 * 所以拿不准的一律留在 [INNER]，交给运行期兜底链去试。
 */
internal enum class FeedStartPlan {
  /** 默认：ExoPlayer（平台硬解优先，NextLib 的 FFmpeg 兜底） */
  INNER,

  /** 内置内核必解不了，直接交给 mpv */
  MPV,
}

/**
 * media3 自带的 Extractor **拆不开**的容器。
 *
 * 依据是 `DefaultExtractorsFactory` 的清单：mp4/mov、mkv/webm、ts/m2ts、ps/mpg/vob、
 * flv、avi、ogg(音频)、wav、mp3、aac、amr、flac、ac3/eac3。清单外的只有这几个常见：
 *
 * - `rm` / `rmvb`：RealMedia，media3 没有这个 Extractor；
 * - `wmv` / `asf`：ASF 容器，同上；
 * - `ogm` / `ogv`：Ogg 容器 media3 是有的，但它只解析 Vorbis/Opus/FLAC **音频**，
 *   遇到 Theora 视频轨直接判失败 —— 所以带画面的 ogv 实际等同于不支持。
 *
 * ⚠️ **NextLib 补不了这一层**：它的 AAR 里只有 libavcodec/libavutil/libswresample/
 * libswscale/libmedia3ext，**没有 libavformat**。容器不认识时换解码器毫无意义。
 */
private val MPV_ONLY_CONTAINERS = setOf(
  "rm",
  "rmvb",
  "wmv",
  "asf",
  "ogm",
  "ogv",
)

/**
 * 平台与 NextLib **都没有解码器**的视频编码。
 *
 * 这两边各有各的缺口，这里的编码是两缺口重叠的那部分：
 *
 * - 平台侧：AOSP 与厂商 MediaCodec 只覆盖 H.264 / HEVC / MPEG-4 Part 2 / VP8 / VP9 /
 *   AV1 / MPEG-2 这一批，VC-1、WMV3、RealVideo、VP6 一个都没有；
 * - NextLib 侧：FFmpeg 渲染器认哪些编码由 `FfmpegLibrary.getCodecName` 那张表决定，
 *   **视频只有 avc / hevc / mpeg / mpeg2 / vp8 / vp9 / av01 七项**。
 *   ⚠️ 这跟 FFmpeg 的编译配置无关 —— 打进包里的 `libavcodec.so` 是 mpv 那份
 *   （Lavc63），`mpeg4`、`wmv3`、`vc1`、`rv40`、`vp6` 全都在里面，
 *   只是那张表没把 mime 映射进去，扩展渲染器根本不会去问它。
 *
 * `mpeg4`（Xvid / DivX）**故意不在这个名单里**：平台普遍带 MPEG-4 Part 2 解码器，
 * 有些机型能正常出画面，预判成 mpv 会白白丢掉硬解。它的坏情况交给运行期兜底链。
 *
 * 同理，`wmv1` / `wmv2` / `msmpeg4v2` / `h263` 也**不收**：它们要么几乎只出现在
 * 已被容器名单覆盖的 `.wmv` / `.asf` 里，要么个别机型能用 MPEG-4 解码器兼容。
 * 名单越长，误判的机会越多；而误判的代价（能放的片子被丢给 mpv）远大于省下一轮失败。
 */
private val MPV_ONLY_CODECS = setOf(
  "wmv3",
  "vc1",
  "rv30",
  "rv40",
  "vp6",
  "vp6f",
  "msmpeg4v3",
)

/**
 * 这一条起播时该走哪个内核。容器优先于编码 —— 容器拆不开时，
 * 里面是什么编码都不重要。
 */
internal fun startPlanOf(item: FeedItem): FeedStartPlan {
  val container = item.container?.lowercase()?.trim().orEmpty()
  if (container in MPV_ONLY_CONTAINERS) return FeedStartPlan.MPV
  val codec = item.videoCodec?.lowercase()?.trim().orEmpty()
  if (codec in MPV_ONLY_CODECS) return FeedStartPlan.MPV
  return FeedStartPlan.INNER
}
