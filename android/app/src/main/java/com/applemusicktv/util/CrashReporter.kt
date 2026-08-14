package com.applemusicktv.util

import android.content.Context
import android.os.Build
import com.applemusicktv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the last uncaught exception to a file so it can be bundled into a bug report.
 * Chains to the previous handler, so the OS still tears the process down as usual.
 */
object CrashReporter {
    private const val FILE = "crash.log"

    fun install(context: Context) {
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching { write(app, thread, ex) }
            prev?.uncaughtException(thread, ex)
        }
    }

    /** The recorded crash text, or null if the app hasn't crashed since the log was last cleared. */
    fun lastCrash(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() && it.length() > 0 }?.readText()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    private fun write(context: Context, thread: Thread, ex: Throwable) {
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("=== CRASH $ts ===")
            appendLine("App v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread ${thread.name}")
            appendLine()
            append(sw.toString())
        }
        // Keep only the most recent crash to bound the file size.
        File(context.filesDir, FILE).writeText(text)
    }
}
