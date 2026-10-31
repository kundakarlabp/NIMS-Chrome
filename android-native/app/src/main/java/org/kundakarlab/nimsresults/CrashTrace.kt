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
                    // Prefer our own frame; framework-only exceptions such as
                    // FrameLayout.onMeasure still need a usable crash location.
                    // Never persist exception messages, stack arguments, URLs or
                    // patient information.
                    val cause = failure.cause ?: failure
                    val trace = if (cause.stackTrace.isNotEmpty()) cause.stackTrace
                        else failure.stackTrace
                    val frame = trace.firstOrNull {
                        it.className.startsWith("org.kundakarlab.nimsresults.")
                    } ?: trace.firstOrNull()
                    val where = frame?.let {
                        it.className.substringAfterLast('
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
).substringAfterLast('.')
                            .take(48) + "." + it.methodName.take(48)
                    } ?: "unknown"
                    val type = cause.javaClass.simpleName.take(48)
                    application.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .edit().putString(KEY, type + "@" + where).commit()
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
