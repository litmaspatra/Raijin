package com.raijin.app.contact

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import io.raijin.brain.ContactScorer.ContextSignals
import java.util.Calendar

/**
 * CT-5 signal collection, on-device only. Reads the phone's own call log live
 * (nothing stored, nothing sent anywhere) to tell the contact ranking who the
 * owner calls often, how recently, and at this time of day.
 */
object CallHistorySignals {

    data class CallRow(val number: String, val dateMs: Long)

    private const val DAY_MS = 86_400_000L
    private const val WINDOW_DAYS = 90
    private const val MAX_ROWS = 2_000

    /** Stable key for matching a call-log number to an address-book number. */
    fun key(number: String): String = number.filter { it.isDigit() }.takeLast(10)

    fun daypartOf(hour: Int): Int = when (hour) {
        in 5..11 -> 0
        in 12..16 -> 1
        in 17..20 -> 2
        else -> 3
    }

    /** Pure: call rows -> per-number-key signals. */
    fun buildSignals(rows: List<CallRow>, nowMs: Long, nowHour: Int): Map<String, ContextSignals> {
        val nowPart = daypartOf(nowHour)
        val by = rows.filter { key(it.number).length >= 7 }.groupBy { key(it.number) }
        return by.mapValues { (_, calls) ->
            val inMonth = calls.count { nowMs - it.dateMs <= 30 * DAY_MS }
            val last = calls.maxOf { it.dateMs } / 1000L
            val samePart = calls.count { c ->
                val cal = Calendar.getInstance().apply { timeInMillis = c.dateMs }
                daypartOf(cal.get(Calendar.HOUR_OF_DAY)) == nowPart
            }
            ContextSignals(callCount30d = inMonth, lastContactEpochSec = last, sameDaypartCount = samePart)
        }
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /** Reads the last 90 days of calls; empty when permission is missing or the read fails. */
    fun load(context: Context, nowMs: Long = System.currentTimeMillis()): Map<String, ContextSignals> {
        if (!hasPermission(context)) return emptyMap()
        return try {
            val rows = mutableListOf<CallRow>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} > ?",
                arrayOf((nowMs - WINDOW_DAYS * DAY_MS).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val n = c.getColumnIndex(CallLog.Calls.NUMBER)
                val d = c.getColumnIndex(CallLog.Calls.DATE)
                while (c.moveToNext() && rows.size < MAX_ROWS) {
                    val number = c.getString(n) ?: continue
                    rows += CallRow(number, c.getLong(d))
                }
            }
            val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
            buildSignals(rows, nowMs, hour)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
