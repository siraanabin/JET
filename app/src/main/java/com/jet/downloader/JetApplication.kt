package com.jet.downloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import coil.Coil
import coil.ImageLoader
import com.jet.downloader.data.PreferencesManager
import com.jet.downloader.di.appModule
import com.jet.downloader.network.JetOkHttpDownloader
import com.yausername.ffmpeg.FFmpeg
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.schabi.newpipe.extractor.NewPipe
import kotlinx.coroutines.runBlocking

object JetFfmpegRuntime {
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(context: android.content.Context): Long {
        if (initialized) return 0L
        val startedAtMs = SystemClock.elapsedRealtime()
        try {
            FFmpeg.getInstance().init(context.applicationContext)
            initialized = true
            val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
            Log.i("JET-FFmpeg", "Bundled FFmpeg support files initialized successfully; initMs=" + elapsedMs)
            Log.d("ENGINE_DEBUG", "ffmpeg_runtime_init_ms=" + elapsedMs)
            return elapsedMs
        } catch (error: Exception) {
            Log.e("JET-FFmpeg", "Bundled FFmpeg initialization failed", error)
            throw IllegalStateException(
                "Bundled FFmpeg initialization failed: ${error.message ?: error.javaClass.simpleName}",
                error
            )
        }
    }
}

class JetApplication : Application() {

    private val newPipeDownloader: JetOkHttpDownloader by inject()
    private val coilImageLoader: ImageLoader by inject()

    override fun onCreate() {
        super.onCreate()

        val savedThemeMode = runBlocking {
            PreferencesManager(applicationContext).migrateLegacyThemePreference()
        }
        AppCompatDelegate.setDefaultNightMode(
            when (savedThemeMode) {
                PreferencesManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                PreferencesManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DownloadNotificationService.CHANNEL_ID,
                "Download Manager",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows active download progress and completion status." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        startKoin {
            androidContext(this@JetApplication)
            modules(appModule)
        }

        // Initialize Coil (Apache 2.0) and NewPipe Extractor (GPLv3)
        Coil.setImageLoader(coilImageLoader)
        NewPipe.init(newPipeDownloader)
    }
}
