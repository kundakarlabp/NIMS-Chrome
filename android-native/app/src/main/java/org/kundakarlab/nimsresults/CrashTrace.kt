package org.kundakarlab.nimsresults

import android.content.Context

/** Captures only exception type and application method—not messages,
 * clinical data, stack arguments, cookies, patient IDs or report paths. */
object CrashTrace {
    private const val PREF = "nims_native_crash_v1"
    private const val KEY = "last"
    @Volatile private var installed = false
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val application = context.applicationContext
            val upstream = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                runCatching {
                    val type = failure.javaClass.simpleName.take(48)
                    val own = failure.stackTrace.firstOrNull {
                        it.className.startsWith("org.kundakarlab.nimsresults.")
                    }
                    val method = own?.className?.substringAfterLast('.')?.take(48).orEmpty() +
                        "." + own?.methodName.orEmpty().take(48)
                    application.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .edit().putString(KEY, type + "@" + method).commit()
                }
                if (upstream != null) upstream.uncaughtException(thread, failure)
                else android.os.Process.killProcess(android.os.Process.myPid())
            }
            installed = true
        }
    }
    fun last(context: Context): String = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "none").orEmpty()
}
