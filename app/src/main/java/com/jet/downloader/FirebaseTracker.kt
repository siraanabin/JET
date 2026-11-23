package com.jet.downloader

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object FirebaseTracker {
    private const val TAG = "JET-Firebase"

    fun isAvailable(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context.applicationContext).isNotEmpty() }.getOrDefault(false)

    fun initialize(context: Context) {
        if (!isAvailable(context)) {
            Log.i(TAG, "Firebase is not configured for this variant; tracking disabled.")
            return
        }
        runCatching {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            }.getOrDefault("unknown")
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("app_version", version)
                setCustomKey("android_version", Build.VERSION.RELEASE ?: "unknown")
                setCustomKey("device_model", Build.MODEL ?: "unknown")
            }
            Log.i(TAG, "Firebase initialized.")
        }.onFailure { Log.w(TAG, "Firebase initialization failed safely.", it) }
    }

    fun recordWebViewError(context: Context, message: String, stack: String) {
        if (!isAvailable(context)) return
        runCatching {
            FirebaseCrashlytics.getInstance().recordException(
                IllegalStateException("WebView JavaScript error: $message\n$stack".take(8000))
            )
        }.onFailure { Log.w(TAG, "Could not record WebView error.", it) }
    }

    fun logDownloadCompleted(context: Context, sourceUrl: String, mediaType: String) {
        if (!isAvailable(context)) return
        runCatching {
            val host = runCatching {
                android.net.Uri.parse(if (sourceUrl.startsWith("http", true)) sourceUrl else "https://$sourceUrl")
                    .host.orEmpty().lowercase()
            }.getOrDefault("")
            val platform = when {
                host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> "youtube"
                host == "fb.watch" || host.endsWith("facebook.com") || host.endsWith("fb.com") -> "facebook"
                host.endsWith("instagram.com") -> "instagram"
                host.endsWith("tiktok.com") -> "tiktok"
                host == "x.com" || host.endsWith("twitter.com") || host == "t.co" -> "twitter_x"
                else -> "other"
            }
            FirebaseAnalytics.getInstance(context).logEvent("media_download_completed", Bundle().apply {
                putString("platform", platform)
                putString("media_type", if (mediaType.equals("audio", true)) "audio" else "video")
            })
        }.onFailure { Log.w(TAG, "Could not log completed download.", it) }
    }

    fun submitFeedback(
        context: Context,
        details: String,
        contact: String,
        attachments: List<Map<String, String>>,
        onResult: (Boolean, Exception?) -> Unit
    ) {
        if (!isAvailable(context)) {
            onResult(false, IllegalStateException("Firebase is unavailable in this build."))
            return
        }
        runCatching {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            val data = hashMapOf<String, Any>(
                "details" to details.take(1000),
                "contact" to contact.take(160),
                "appVersion" to version,
                "deviceModel" to (Build.MODEL ?: "unknown"),
                "androidVersion" to (Build.VERSION.RELEASE ?: "unknown"),
                "timestamp" to FieldValue.serverTimestamp()
            )
            if (attachments.isNotEmpty()) data["attachments"] = attachments.take(9)
            FirebaseFirestore.getInstance().collection("feedback").add(data)
                .addOnSuccessListener { onResult(true, null) }
                .addOnFailureListener { onResult(false, it) }
        }.onFailure { onResult(false, it as? Exception ?: IllegalStateException(it)) }
    }
}
