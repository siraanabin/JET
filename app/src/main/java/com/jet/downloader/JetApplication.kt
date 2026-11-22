package com.jet.downloader

import android.app.Application
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
        if (BuildConfig.DEBUG) {
            CrashReporter.install(this)
            // If the previous launch crashed, keep startup dependencies untouched so
            // MainActivity can show the saved report instead of crashing again.
            if (CrashReporter.hasPendingReport(this)) return
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
