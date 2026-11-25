# Preserve generic type metadata and reflective Jackson model construction.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Preserve yt-dlp, FFmpeg, aria2c and their reflection/native integration.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# Preserve Jackson classes and TypeReference generic signatures/subclasses.
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * extends com.fasterxml.jackson.core.type.TypeReference { *; }
-dontwarn com.fasterxml.jackson.**

# Preserve NewPipe Extractor and OkHttp used by the custom download engine.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Preserve Kotlin serialization models and generated serializers.
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers class **$$serializer { *; }
-keep class kotlinx.serialization.** { *; }

# Keep methods exposed to the WebView JavaScript bridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclasseswithmembers class * {
    native <methods>;
}

# Rhino's optional Java SE integration references APIs absent on Android.
# These JavaBeans and javax.script adapters are not available/used on Android.
-dontwarn java.beans.**
-dontwarn javax.script.**
