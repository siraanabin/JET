package com.jet.downloader.di

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import coil.ImageLoader
import com.jet.downloader.data.JetDatabase
import com.jet.downloader.data.PreferencesManager
import com.jet.downloader.network.JetOkHttpDownloader
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE downloads ADD COLUMN thumbnailUrl TEXT NOT NULL DEFAULT ''")
    }
}

val appModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    single { JetOkHttpDownloader(get()) }

    single {
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    single {
        Room.databaseBuilder(
            androidContext(),
            JetDatabase::class.java,
            "jet_downloads.db"
        ).addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration().build()
    }

    single { get<JetDatabase>().downloadDao() }

    single { PreferencesManager(androidContext()) }

    single {
        ImageLoader.Builder(androidContext())
            .okHttpClient(get<OkHttpClient>())
            .crossfade(true)
            .build()
    }
}
