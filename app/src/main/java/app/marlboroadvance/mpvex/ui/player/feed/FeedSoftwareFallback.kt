package app.marlboroadvance.mpvex.ui.player.feed

/**
 * Chooses the least expensive available software-decoding fallback for the feed.
 *
 * NextLib's FFmpeg MIME map isn't the only possible software decoder source:
 * some Android builds expose software MediaCodec decoders for additional codecs.
 */
internal enum class FeedSoftwareFallback {
  FFMPEG,
  PLATFORM,
  NONE,
}

internal fun chooseFeedSoftwareFallback(
  ffmpegSupportsMime: Boolean,
  platformHasSoftwareDecoder: Boolean,
): FeedSoftwareFallback =
  when {
    ffmpegSupportsMime -> FeedSoftwareFallback.FFMPEG
    platformHasSoftwareDecoder -> FeedSoftwareFallback.PLATFORM
    else -> FeedSoftwareFallback.NONE
  }
