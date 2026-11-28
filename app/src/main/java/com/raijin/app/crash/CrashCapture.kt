package com.raijin.app.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the reason for the last crash on the phone so Settings can show it (and the owner can copy it).
 * Nothing is sent anywhere. Three sources:
 *  - a Java crash: the uncaught-exception handler writes the stack trace;
 *  - a native crash or kill: Android's exit record (API 30+) is read on the next launch;
 *  - breadcrumbs: the last step before a native call, so a native crash says where it happened.
 */
object CrashCapture {
    private const val DIR = "crash"
    private const val REPORT = "last_crash.txt"
    private const val CRUMB = "breadcrumb.txt"
    private const val SEEN = "exit_seen.txt"
    private const val MAX_CHARS = 6000

    private fun dir(c: Context) = File(c.filesDir, DIR).apply { mkdirs() }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeReport(app, format(t.name, e, System.currentTimeMillis(), lastBreadcrumb(app))) }
            previous?.uncaughtException(t, e)
        }
    }

    fun format(thread: String, e: Throwable, now: Long, crumb: String?): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
        val body = "Java crash $stamp\nthread: $thread\nlast step: ${crumb ?: "none"}\ndevice: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n$sw"
        return body.take(MAX_CHARS)
    }

    fun writeReport(context: Context, text: String) {
        File(dir(context), REPORT).writeText(text.take(MAX_CHARS))
    }

    fun read(context: Context): String? =
        File(dir(context), REPORT).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(dir(context), REPORT).delete()
    }

    /** Records the step about to run. Synchronous and tiny, so it survives a native crash. */
    fun breadcrumb(context: Context, step: String) {
        runCatching {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            File(dir(context), CRUMB).writeText("$step at $stamp")
        }
    }

    fun lastBreadcrumb(context: Context): String? =
        File(dir(context), CRUMB).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    /** Reads Android's record of how the previous run ended and keeps a report for crashes and kills. */
    fun collectExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val seenFile = File(dir(context), SEEN)
            val seen = seenFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
            val bad = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.timestamp > seen }
                .filter {
                    it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        it.reason == ApplicationExitInfo.REASON_SIGNALED ||
                        it.reason == ApplicationExitInfo.REASON_ANR ||
                        it.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                }
            val newest = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.timestamp
            if (newest != null) seenFile.writeText(newest.toString())
            val e = bad.firstOrNull() ?: return
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e.timestamp))
            val kind = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                ApplicationExitInfo.REASON_SIGNALED -> "Killed by signal"
                ApplicationExitInfo.REASON_ANR -> "App not responding"
                else -> "Start-up failure"
            }
            val report = "$kind $stamp\nprocess: ${e.processName}\ndescription: ${e.description ?: "none"}\nstatus: ${e.status}\nlast step: ${lastBreadcrumb(context) ?: "none"}\n" +
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            writeReport(context, report)
        }
    }
}
