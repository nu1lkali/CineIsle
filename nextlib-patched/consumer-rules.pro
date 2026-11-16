# Preserve JNI entry points used by libmedia3ext.so.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer {
    *;
}
