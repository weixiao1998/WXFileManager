# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line number information for stack traces and hide source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# LibVLC ProGuard rules
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.**
-keepclassmembers class * {
    native <methods>;
}
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer { *; }
-keep class androidx.media3.decoder.DecoderInputBuffer { *; }

# ---- App data models / Parcelable ----
# FileModel/SmbServer go through Parcel / Gson serialization
-keep class dev.weixiao.wxfilemanager.model.** { *; }

# Keep Parcelable CREATOR fields
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ---- Gson ----
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class * extends com.google.gson.TypeAdapter
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- SMBj / dcerpc (uses SLF4J and reflection) ----
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-keep class com.rapid7.client.** { *; }
-dontwarn com.rapid7.client.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn net.engio.mbassy.**

# ---- Glide ----
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# Keep our custom Glide loader (referenced via reflection from registry.append)
-keep class dev.weixiao.wxfilemanager.utils.SmbModelLoader { *; }
-keep class dev.weixiao.wxfilemanager.utils.SmbModelLoader$* { *; }
-keep class dev.weixiao.wxfilemanager.WXGlideModule { *; }

# ---- Prism4j (syntax highlighting; relies on annotation-generated grammar classes) ----
-keep class io.noties.prism4j.** { *; }
-dontwarn io.noties.prism4j.**
-keep class **.Prism_** { *; }
-keep class **.GrammarLocator** { *; }

# ---- juniversalchardet ----
-keep class org.mozilla.universalchardet.** { *; }
-dontwarn org.mozilla.universalchardet.**

# ---- AndroidX security-crypto / Tink ----
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---- Kotlin Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
