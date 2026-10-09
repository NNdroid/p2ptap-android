package app.fjj.p2ptap.crash

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Installs a process-wide [Thread.UncaughtExceptionHandler] that persists the
 * fatal exception into SharedPreferences. The next launch can then surface it
 * to the user via a dialog (see [consumePendingCrash]).
 *
 * Additionally, [installNativeCrashHandler] configures the Go runtime to write
 * fatal-error output (goroutine stacks, "fatal error: ..." lines) and native
 * signal crashes (SIGSEGV, SIGABRT, …) into a plain-text crash file. This
 * covers crashes in p2ptap-core that bypass the Java exception handler.
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
    private const val NATIVE_CRASH_FILE = "native_crash.txt"
    private const val MAX_NATIVE_CRASH_LENGTH = 16000

    @Volatile
    private var installed = false
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var appVersion: String = "unknown"

    /** Register the Java crash handler. Call once from Application.onCreate(). */
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
     * Configure the Go runtime to write fatal-error output and native signal
     * crashes to a file in the app's private storage. Call from
     * Application.onCreate() after [install]. The crash file is read by
     * [consumePendingNativeCrash] on next launch.
     */
    @JvmStatic
    fun installNativeCrashHandler(context: Context) {
        try {
            val path = File(
                context.applicationContext.filesDir,
                NATIVE_CRASH_FILE
            ).absolutePath
            com.p2ptap.P2PTap.P2PTap.setCrashFilePath(path)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install native crash handler", e)
        }
    }

    /**
     * Returns and clears the pending Java crash record, or null if there was
     * no crash. The caller should show a dialog for the returned value.
     */
    @JvmStatic
    fun consumePendingCrash(context: Context): CrashInfo? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LAST_CRASH, null) ?: return null
        val info = CrashInfo.fromJSON(raw) ?: return null
        prefs.edit().remove(KEY_LAST_CRASH).commit()
        return info
    }

    /** Returns true if there is a pending Java crash record (does not clear it). */
    @JvmStatic
    fun hasPendingCrash(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.contains(KEY_LAST_CRASH)
    }

    /**
     * Returns and deletes the pending native crash file content, or null if
     * there is no native crash. This covers Go runtime fatal errors (e.g.
     * "sync: unlock of unlocked mutex") and native segfaults that bypass the
     * Java UncaughtExceptionHandler.
     */
    @JvmStatic
    fun consumePendingNativeCrash(context: Context): String? {
        val ctx = context.applicationContext
        val file = File(ctx.filesDir, NATIVE_CRASH_FILE)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val content = file.readText()
            file.delete()
            if (content.length > MAX_NATIVE_CRASH_LENGTH) {
                content.substring(0, MAX_NATIVE_CRASH_LENGTH) + "\n… (truncated)"
            } else {
                content
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read native crash file", e)
            null
        }
    }

    /** Returns true if there is a pending native crash file (does not clear it). */
    @JvmStatic
    fun hasPendingNativeCrash(context: Context): Boolean {
        val file = File(context.applicationContext.filesDir, NATIVE_CRASH_FILE)
        return file.exists() && file.length() > 0L
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
