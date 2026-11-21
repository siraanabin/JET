package com.jet.downloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.Coil
import coil.ImageLoader
import com.jet.downloader.di.appModule
import com.jet.downloader.network.JetOkHttpDownloader
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.schabi.newpipe.extractor.NewPipe

class JetApplication : Application() {

    private val newPipeDownloader: JetOkHttpDownloader by inject()
    private val coilImageLoader: ImageLoader by inject()

    override fun onCreate() {
        super.onCreate()
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
