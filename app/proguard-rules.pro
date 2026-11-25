-keepattributes JavascriptInterface,Signature,InnerClasses,EnclosingMethod,*Annotation*
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

# Preserve all app classes and members from R8 removal/obfuscation, including WebView bridge and serialized models.
-keep class com.jet.downloader.** { *; }

# Preserve application JSON payloads, models, events and Room data models used by reflection/serialization.
-keep class com.jet.downloader.model.** { *; }
-keep class com.jet.downloader.data.** { *; }

# Preserve Kotlin serialization models and generated serializers.
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers class **$$serializer { *; }
-keep class kotlinx.serialization.** { *; }

# Preserve WebView bridge annotations and every exposed bridge method.
-keepattributes JavascriptInterface,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
-keep class com.jet.downloader.MainActivity$JetNativeBridge { *; }
-keep class com.jet.downloader.MainActivity$JetNetworkBridge { *; }
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
