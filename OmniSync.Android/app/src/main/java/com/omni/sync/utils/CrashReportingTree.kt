package com.omni.sync.utils

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashReportingTree(private val context: Context) : Timber.DebugTree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, tag, message, t)

        if (t != null) {
            val crashReport = formatCrashReport(t)
            writeCrashReportToFile(crashReport)
        }
    }

    private fun formatCrashReport(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()
        pw.close()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return """
            *** Crash Report ***
            Timestamp: $timestamp
            Exception: ${throwable.javaClass.name}
            Message: ${throwable.message}
            Stack Trace:
            $stackTrace
            ********************
        """.trimIndent()
    }

    private fun writeCrashReportToFile(report: String) {
        try {
            val crashDir = File(context.filesDir, "crash_reports")
            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val crashFile = File(crashDir, "crash_$timestamp.txt")
            crashFile.writeText(report)
            Timber.e("Crash report written to: ${crashFile.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to write crash report to file.")
        }
    }
}
