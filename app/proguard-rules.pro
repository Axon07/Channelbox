# Channelbox release keep rules. Most libraries ship their own (Room, WorkManager, OkHttp, Coil,
# Media3, kotlinx.serialization); these cover the ones that use reflection or native code.

# Readable crash reports from users.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Native code calls back into these classes by name.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class com.wireguard.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.datasource.rtmp.** { *; }
-keep class io.antmedia.rtmp_client.** { *; }
-keep class net.butterflytv.rtmp_client.** { *; }

# Media3 finds optional extensions (FFmpeg, RTMP, HLS/DASH/SS/RTSP) by class name.
-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory { <init>(...); }
-keep class androidx.media3.exoplayer.dash.DashMediaSource$Factory { <init>(...); }
-keep class androidx.media3.exoplayer.smoothstreaming.SsMediaSource$Factory { <init>(...); }
-keep class androidx.media3.exoplayer.rtsp.RtspMediaSource$Factory { <init>(...); }

# SMB recordings: smbj and its event bus use reflection; BouncyCastle crypto is looked up by name.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.bouncycastle.**
-dontwarn javax.el.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**

# Gson is only used as a streaming reader (no reflection), but keep its internals tidy.
-dontwarn com.google.gson.**

# WorkManager workers and app components are created by name (covered by the manifest and
# WorkManager's own rules); keep our workers' constructors to be safe.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# OkHttp optional platforms.
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
