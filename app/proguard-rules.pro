# Preserve Kotlin serialization models and generated serializers.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers class **$$serializer { *; }
-keep class kotlinx.serialization.** { *; }

# Keep methods exposed to the WebView JavaScript bridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep extractor and downloader reflection/native integration stable under R8.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class com.yausername.youtubedl_android.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}

# Rhino's optional Java SE integration references APIs absent on Android.
# These JavaBeans and javax.script adapters are not available/used on Android.
-dontwarn java.beans.**
-dontwarn javax.script.**
