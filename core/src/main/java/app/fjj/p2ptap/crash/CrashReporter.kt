package app.fjj.p2ptap.crash

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Installs a process-wide [Thread.UncaughtExceptionHandler] that persists the
 * fatal exception into SharedPreferences. The next launch can then surface it
 * to the user via a dialog (see [consumePendingCrash]).
 *
 * Design notes:
 *  - Only one crash record is kept (the most recent). Older crashes are
 *    overwritten — the value here is "what just happened", not history.
 *  - The stack trace is truncated to [MAX_STACK_LENGTH] characters: a full
 *    Go JNI backtrace can be hundreds of KB, and SharedPreferences has a
 *    per-value limit of ~1 MB on some OEM ROMs.
 *  - The handler chains to the previous default handler (typically the system
 *    one that kills the process and logs to tombstones).
 */
object CrashReporter {
    private const val PREFS = "p2ptap_crash_prefs"
    private const val KEY_LAST_CRASH = "last_crash_json"
    private const val MAX_STACK_LENGTH = 8000
    private const val TAG = "CrashReporter"

    @Volatile
    private var installed = false
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var appVersion: String = "unknown"

    /** Register the handler. Call once from Application.onCreate(). */
    @JvmStatic
    fun install(context: Context) {
        if (installed) return
        installed = true
        val ctx = context.applicationContext
        appContext = ctx
        appVersion = resolveVersion(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val info = buildInfo(throwable, thread)
                prefs.edit().putString(KEY_LAST_CRASH, CrashInfo.toJSON(info)).commit()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist crash report", e)
            }
            // Hand off to the system handler (kills the process, logs tombstone).
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Returns and clears the pending crash record, or null if there was no
     * crash. The caller should show a dialog for the returned value.
     */
    @JvmStatic
    fun consumePendingCrash(context: Context): CrashInfo? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LAST_CRASH, null) ?: return null
        val info = CrashInfo.fromJSON(raw) ?: return null
        prefs.edit().remove(KEY_LAST_CRASH).commit()
        return info
    }

    /** Returns true if there is a pending crash record (does not clear it). */
    @JvmStatic
    fun hasPendingCrash(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.contains(KEY_LAST_CRASH)
    }

    private fun buildInfo(t: Throwable, thread: Thread): CrashInfo {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stack = sw.toString()
        val message = t.message ?: ""
        return CrashInfo(
            timestamp = System.currentTimeMillis(),
            threadName = thread.name,
            exceptionClass = t.javaClass.name,
            exceptionMessage = message,
            stackTrace = if (stack.length > MAX_STACK_LENGTH) {
                stack.substring(0, MAX_STACK_LENGTH) + "\n… (truncated)"
            } else {
                stack
            },
            appVersion = appVersion
        )
    }

    private fun resolveVersion(context: Context): String = try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = pInfo.versionName ?: "unknown"
        "v${versionName} (build ${pInfo.longVersionCode})"
    } catch (_: Exception) {
        "unknown"
    }
}
