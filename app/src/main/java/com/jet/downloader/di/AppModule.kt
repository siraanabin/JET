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
import okhttp3.Dispatcher
import okhttp3.ConnectionPool
import okhttp3.Protocol
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
        val dispatcher = Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 16
        }
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
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
