package com.jet.downloader

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Stores uncaught exceptions for the next debug launch, without requiring logcat access. */
internal object CrashReporter {
    private const val REPORT_FILE_NAME = "jet_debug_last_crash.txt"

    fun install(application: Application) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stackTrace = StringWriter().also { writer ->
                    error.printStackTrace(PrintWriter(writer))
                }.toString()
                val report = buildString {
                    appendLine("JET Debug crash report")
                    appendLine("Time: ${Date()}")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(stackTrace)
                }
                File(application.filesDir, REPORT_FILE_NAME).writeText(report, Charsets.UTF_8)
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun hasPendingReport(context: Context): Boolean = pendingReportFile(context)?.length()?.let { it > 0L } == true

    fun pendingReportFile(context: Context): File? =
        File(context.filesDir, REPORT_FILE_NAME).takeIf { it.isFile && it.length() > 0L }
}
