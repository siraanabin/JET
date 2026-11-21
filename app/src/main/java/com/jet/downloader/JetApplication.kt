package com.jet.downloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import android.util.Log
import coil.Coil
import coil.ImageLoader
import com.jet.downloader.di.appModule
import com.yausername.ffmpeg.FFmpeg
import com.jet.downloader.network.JetOkHttpDownloader
import com.yausername.ffmpeg.FFmpeg
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.schabi.newpipe.extractor.NewPipe

object JetFfmpegRuntime {
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(context: android.content.Context) {
        if (initialized) return
        FFmpeg.getInstance().init(context.applicationContext)
        initialized = true
    }
}

class JetApplication : Application() {

    private val newPipeDownloader: JetOkHttpDownloader by inject()
    private val coilImageLoader: ImageLoader by inject()

    override fun onCreate() {
        super.onCreate()

        // Initialize bundled FFmpeg at application startup without blocking the first screen.
        // The download path joins this synchronized initialization before invoking yt-dlp.
        Thread({
            runCatching { JetFfmpegRuntime.initialize(applicationContext) }
                .onFailure { Log.e("JET", "Bundled FFmpeg initialization failed", it) }
        }, "jet-ffmpeg-init").apply { isDaemon = true }.start()

        // Register the bundled FFmpeg executable early so every video download can mux
        // separate video/audio streams. Download initialization retries and validates it.
        runCatching { FFmpeg.getInstance().init(applicationContext) }
            .onFailure { Log.e("JET", "Bundled FFmpeg initialization failed", it) }

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
