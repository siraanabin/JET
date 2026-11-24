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
import okhttp3.Dns
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import java.net.Inet4Address

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE downloads ADD COLUMN thumbnailUrl TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE downloads ADD COLUMN resolution TEXT NOT NULL DEFAULT ''")
    }
}

val appModule = module {
    single {
        OkHttpClient.Builder()
            .dns(Dns { hostname -> Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 } })
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
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).fallbackToDestructiveMigration().build()
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
