package app.marlboroadvance.mpvex.ui.player

/**
 * Codec allowlist for Android MediaCodec hardware decoding.
 *
 * Keep this aligned with `MPVView`'s `hwdec-codecs` option. Do not widen it to `all`:
 * legacy MPEG-4 ASP (Xvid/DivX), WMV and MPEG-1/4 variants are often missing the
 * codec-specific data MediaCodec needs, so they must be decoded by FFmpeg in software.
 * In direct-output mode, those software frames need a GPU video output instead of
 * `mediacodec_embed`.
 */
internal const val MPV_HWDEC_CODECS = "h264,hevc,mpeg2video,vc1,vp8,vp9,av1"

private val mpvHardwareCodecs = MPV_HWDEC_CODECS.split(',').toSet()

/**
 * Whether a codec must use a software-capable video output.
 *
 * A missing codec is inconclusive: callers should wait for mpv's track metadata
 * rather than switching renderers for audio-only or not-yet-loaded media.
 */
internal fun requiresSoftwareVideoOutput(codec: String?): Boolean {
  val normalized = codec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
  return normalized !in mpvHardwareCodecs
}
