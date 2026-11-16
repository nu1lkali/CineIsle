package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.Format;
import org.junit.Test;

/** Regression tests for additional codec mappings used by the feed's FFmpeg fallback. */
public final class FfmpegCodecMappingTest {

  @Test
  public void mapsMpeg4AndDivxBitstreams() {
    assertEquals("mpeg4", codec("video/mp4v-es", "mp4v.20.3"));
    assertEquals("mpeg4", codec("video/mp4v-es", "XVID"));
    assertEquals("mpeg4", codec("video/divx", "DIVX"));
    assertEquals("msmpeg4v3", codec("video/mp4v-es", "DIV3"));
    assertEquals("msmpeg4v2", codec("video/mp42", "MP42"));
    assertEquals("msmpeg4v3", codec("video/mp43", "MP43"));
  }

  @Test
  public void mapsWmvProfilesIndividually() {
    assertEquals("wmv1", codec("video/x-ms-wmv", "WMV1"));
    assertEquals("wmv2", codec("video/x-ms-wmv", "WMV2"));
    assertEquals("wmv3", codec("video/x-ms-wmv", "WMV3"));
  }

  @Test
  public void mapsVc1RealVideoAndVp6Variants() {
    assertEquals("vc1", codec("video/wvc1", "WVC1"));
    assertEquals("rv30", codec("video/vnd.rn-realvideo", "RV30"));
    assertEquals("rv40", codec("video/vnd.rn-realvideo", "RV40"));
    assertEquals("vp6f", codec("video/x-vnd.on2.vp6", "VP6F"));
  }

  @Test
  public void keepsExistingMappings() {
    assertEquals("h264", codec("video/avc", "avc1.640028"));
    assertEquals("hevc", codec("video/hevc", "hvc1.1.6.L120"));
    assertEquals("vp9", codec("video/x-vnd.on2.vp9", "vp09.00.10.08"));
  }

  private static String codec(String mimeType, String codecs) {
    Format format =
        new Format.Builder()
            .setSampleMimeType(mimeType)
            .setCodecs(codecs)
            .build();
    return FfmpegLibrary.getCodecName(format);
  }
}
