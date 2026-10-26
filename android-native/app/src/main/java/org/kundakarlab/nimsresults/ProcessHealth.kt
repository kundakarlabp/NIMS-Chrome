package org.kundakarlab.nimsresults

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Why a new process followed the previous one. Java exception hooks cannot
 * record Android LMK/native death/ANR; this uses system exit records.
 * Redacted: no patient, clinical, URL, cookie, process description or trace.
 */
object ProcessHealth {
    private const val PREFS = "nims_process_health"
    private const val LAST_STAGE = "last_stage"
    private const val LAST_TRIM = "last_trim"
    private const val LAST_TAB = "last_tab"
    private const val LAST_FIELD_COUNT = "last_result_count"
    private const val LAST_PDF_COUNT = "last_report_count"
    private const val STARTS = "starts"

    fun onLaunch(context: Context) {
        val pref = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pref.edit().putInt(STARTS, pref.getInt(STARTS, 0).coerceAtMost(999999) + 1).apply()
    }

    fun checkpoint(context: Context, stage: String, tab: String, results: Int, reports: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(LAST_STAGE, stage.take(35))
            .putString(LAST_TAB, tab.take(35))
            .putInt(LAST_FIELD_COUNT, results.coerceIn(0, 100000))
            .putInt(LAST_PDF_COUNT, reports.coerceIn(0, 100000))
            .apply()
    }

    fun onTrim(context: Context, level: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(LAST_TRIM, level).apply()
    }

    private fun recentExit(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "unsupported_os"
        return runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exit = manager.getHistoricalProcessExitReasons(
                context.packageName, 0, 8
            ).firstOrNull() ?: return@runCatching "no_prior_record"
            val type = when (exit.reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCES"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_OR_SYSTEM_STOP"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNAL"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                else -> "OTHER_" + exit.reason
            }
            type + " pssKb=" + exit.pss + " rssKb=" + exit.rss
        }.getOrDefault("unavailable")
    }

    fun snapshot(context: Context): String {
        val pref = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val heap = Runtime.getRuntime()
        val heapUsedMb = (heap.totalMemory() - heap.freeMemory()) / (1024 * 1024)
        val maxHeapMb = heap.maxMemory() / (1024 * 1024)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val m = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(m)
        return listOf(
            "process.launches=" + pref.getInt(STARTS, 0),
            "process.previousExit=" + recentExit(context),
            "process.lastCheckpoint=" + pref.getString(LAST_STAGE, "unknown"),
            "process.lastTab=" + pref.getString(LAST_TAB, "unknown"),
            "process.previousFields=" + pref.getInt(LAST_FIELD_COUNT, 0),
            "process.previousPdfCount=" + pref.getInt(LAST_PDF_COUNT, 0),
            "process.lastTrimLevel=" + pref.getInt(LAST_TRIM, -1),
            "memory.heapUsedMb=" + heapUsedMb,
            "memory.heapLimitMb=" + maxHeapMb,
            "memory.systemLow=" + m.lowMemory,
            "memory.availableMb=" + m.availMem / (1024 * 1024)
        ).joinToString("\n")
    }
}
