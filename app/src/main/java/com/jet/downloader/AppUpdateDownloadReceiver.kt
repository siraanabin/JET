package com.jet.downloader

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class AppUpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val preferences = context.getSharedPreferences("jet_app_update", Context.MODE_PRIVATE)
        if (completedId == -1L || completedId != preferences.getLong("download_id", -1L)) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(completedId)
        val cursor = manager.query(query)
        val completedByteCounts = cursor.use {
            if (it == null || !it.moveToFirst()) {
                null
            } else {
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val totalBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val downloadedBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                if (status == DownloadManager.STATUS_SUCCESSFUL && totalBytes > 0L && downloadedBytes == totalBytes) {
                    totalBytes to downloadedBytes
                } else {
                    null
                }
            }
        }

        if (completedByteCounts == null) {
            preferences.edit().remove("download_id").apply()
            Toast.makeText(context, "JET update download failed. Please try again.", Toast.LENGTH_LONG).show()
            return
        }

        val downloadDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDirectory == null) {
            Toast.makeText(context, "JET download storage is unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        val apkFile = File(downloadDirectory, "JET-update.apk")
        val expectedBytes = completedByteCounts.first
        if (!apkFile.isFile || apkFile.length() != expectedBytes) {
            preferences.edit().remove("download_id").apply()
            Toast.makeText(context, "Downloaded JET APK is incomplete. Please download it again.", Toast.LENGTH_LONG).show()
            return
        }
        val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        if (archiveInfo == null || archiveInfo.packageName != context.packageName) {
            preferences.edit().remove("download_id").apply()
            Toast.makeText(context, "Downloaded JET APK is invalid or does not match this app.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            Toast.makeText(context, "Allow JET to install apps, then tap the downloaded APK again.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            preferences.edit().remove("download_id").apply()
        } catch (error: Exception) {
            Toast.makeText(
                context,
                "Unable to open APK installer: ${error.message ?: "Unknown error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
