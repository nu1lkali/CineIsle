package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import java.util.Locale;

/** Configures and queries the underlying native library. */
@UnstableApi
public final class FfmpegLibrary {

  static {
    MediaLibraryInfo.registerModule("media3.decoder.ffmpeg");
  }

  private static final String TAG = "FfmpegLibrary";

  private static final LibraryLoader LOADER =
      new LibraryLoader("media3ext") {
        @Override
        protected void loadLibrary(String name) {
          System.loadLibrary(name);
        }
      };

  private static @MonotonicNonNull String version;
  private static int inputBufferPaddingSize = C.LENGTH_UNSET;

  private FfmpegLibrary() {}

  /**
   * Override the names of the FFmpeg native libraries. If an application wishes to call this
   * method, it must do so before calling any other method defined by this class, and before
   * instantiating a {@link FfmpegAudioRenderer} instance.
   *
   * @param libraries The names of the FFmpeg native libraries.
   */
  public static void setLibraries(String... libraries) {
    LOADER.setLibraries(libraries);
  }

  /** Returns whether the underlying library is available, loading it if necessary. */
  public static boolean isAvailable() {
    return LOADER.isAvailable();
  }

  /** Returns the version of the underlying library if available, or null otherwise. */
  @Nullable
  public static String getVersion() {
    if (!isAvailable()) {
      return null;
    }
    if (version == null) {
      version = ffmpegGetVersion();
    }
    return version;
  }

  /**
   * Returns the required amount of padding for input buffers in bytes, or {@link C#LENGTH_UNSET} if
   * the underlying library is not available.
   */
  public static int getInputBufferPaddingSize() {
    if (!isAvailable()) {
      return C.LENGTH_UNSET;
    }
    if (inputBufferPaddingSize == C.LENGTH_UNSET) {
      inputBufferPaddingSize = ffmpegGetInputBufferPaddingSize();
    }
    return inputBufferPaddingSize;
  }

  /**
   * Returns whether the underlying library supports the specified MIME type.
   *
   * @param mimeType The MIME type to check.
   */
  public static boolean supportsFormat(String mimeType) {
    if (!isAvailable()) {
      return false;
    }
    @Nullable String codecName = getCodecName(mimeType);
    return supportsCodec(codecName);
  }

  /**
   * Returns whether FFmpeg supports the actual codec described by a Media3 track format.
   *
   * MIME-only lookup is ambiguous for families such as WMV and MPEG-4 Part 2.
   */
  public static boolean supportsFormat(Format format) {
    if (!isAvailable()) {
      return false;
    }
    return supportsCodec(getCodecName(format));
  }

  private static boolean supportsCodec(@Nullable String codecName) {
    if (codecName == null) {
      return false;
    }
    if (!ffmpegHasDecoder(codecName)) {
      Log.w(TAG, "No " + codecName + " decoder available. Check the FFmpeg build configuration.");
      return false;
    }
    return true;
  }

  /**
   * Returns the name of the FFmpeg decoder that could be used to decode the format, or {@code null}
   * if it's unsupported.
   */
  @Nullable
  /* package */ static String getCodecName(String mimeType) {
    return switch (mimeType) {
      // Audio codecs
      case MimeTypes.AUDIO_AAC -> "aac";
      case MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> "mp3";
      case MimeTypes.AUDIO_AC3 -> "ac3";
      case MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3";
      case MimeTypes.AUDIO_TRUEHD -> "truehd";
      case MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "dca";
      case MimeTypes.AUDIO_VORBIS -> "vorbis";
      case MimeTypes.AUDIO_OPUS -> "opus";
      case MimeTypes.AUDIO_AMR_NB -> "amrnb";
      case MimeTypes.AUDIO_AMR_WB -> "amrwb";
      case MimeTypes.AUDIO_FLAC -> "flac";
      case MimeTypes.AUDIO_ALAC -> "alac";
      case MimeTypes.AUDIO_MLAW -> "pcm_mulaw";
      case MimeTypes.AUDIO_ALAW -> "pcm_alaw";

      // Video codecs
      case MimeTypes.VIDEO_H264 -> "h264";
      case MimeTypes.VIDEO_H265 -> "hevc";
      case MimeTypes.VIDEO_AV1 -> "libdav1d";
      case MimeTypes.VIDEO_MPEG -> "mpegvideo";
      case MimeTypes.VIDEO_MPEG2 -> "mpeg2video";
      case MimeTypes.VIDEO_VP8 -> "vp8";
      case MimeTypes.VIDEO_VP9 -> "vp9";
      case MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_DIVX -> "mpeg4";
      case MimeTypes.VIDEO_MP42 -> "msmpeg4v2";
      case MimeTypes.VIDEO_MP43 -> "msmpeg4v3";
      case "video/3gpp" -> "h263";
      case "video/wvc1", "video/vc1" -> "vc1";
      case "video/x-ms-wmv" -> "wmv3";
      case "video/vnd.rn-realvideo" -> "rv40";
      case "video/x-vnd.on2.vp6" -> "vp6";
      case "video/x-vnd.on2.vp6f" -> "vp6f";
      case "video/x-vnd.on2.vp6a" -> "vp6a";
      default -> null;
    };
  }

  /** Format-aware codec mapping for MIME families that contain multiple bitstream codecs. */
  @Nullable
  /* package */ static String getCodecName(Format format) {
    String mimeType = format.sampleMimeType;
    if (mimeType == null) {
      return null;
    }
    String codecs = format.codecs == null ? "" : format.codecs.toLowerCase(Locale.US);
    String compactCodecs = codecs.replaceAll("[^a-z0-9]", "");

    if (MimeTypes.VIDEO_MP42.equals(mimeType)) {
      return "msmpeg4v2";
    }
    if (MimeTypes.VIDEO_MP43.equals(mimeType)) {
      return "msmpeg4v3";
    }
    if (MimeTypes.VIDEO_DIVX.equals(mimeType)) {
      return "mpeg4";
    }
    if (MimeTypes.VIDEO_MP4V.equals(mimeType)) {
      if (compactCodecs.contains("div3") || compactCodecs.contains("mp43")
          || compactCodecs.contains("msmpeg4v3")) {
        return "msmpeg4v3";
      }
      if (compactCodecs.contains("mp42") || compactCodecs.contains("msmpeg4v2")) {
        return "msmpeg4v2";
      }
      return "mpeg4";
    }
    if ("video/x-ms-wmv".equals(mimeType)) {
      if (compactCodecs.contains("wmv1")) return "wmv1";
      if (compactCodecs.contains("wmv2")) return "wmv2";
      return "wmv3";
    }
    if ("video/vnd.rn-realvideo".equals(mimeType)) {
      if (compactCodecs.contains("rv10")) return "rv10";
      if (compactCodecs.contains("rv20")) return "rv20";
      if (compactCodecs.contains("rv30")) return "rv30";
      return "rv40";
    }
    if ("video/x-vnd.on2.vp6".equals(mimeType)) {
      if (compactCodecs.contains("vp6a")) return "vp6a";
      if (compactCodecs.contains("vp6f")) return "vp6f";
      return "vp6";
    }
    return getCodecName(mimeType);
  }

  private static native String ffmpegGetVersion();

  private static native int ffmpegGetInputBufferPaddingSize();

  private static native boolean ffmpegHasDecoder(String codecName);
}
