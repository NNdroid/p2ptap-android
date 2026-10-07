package app.fjj.p2ptap.service

import android.util.Log
import java.lang.System.currentTimeMillis
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process log capture, replacing the phone app's `Runtime.exec("logcat …")`
 * path.
 *
 * Why this exists: `logcat` is a shell command, and locked-down set-top boxes
 * deny the shell that the phone app assumes. This collector instead registers
 * the platform's in-process log sink, so the tunnel's own log lines are
 * captured where they are written — no process boundary, no shell, no
 * permission the user has to grant. The Go engine writes through the JNI
 * layer into the same process, so its `GoLog`/`Node`/`Gateway` lines arrive
 * here too.
 *
 * Honest limitation, surfaced rather than hidden: the platform may refuse the
 * sink (see [isAttached]). When it does, callers must show that fact instead
 * of showing an empty list that reads as "nothing happened".
 *
 * Implementation: `android.util.LogWriter` and `Log.setLogWriter()` are
 * hidden APIs not present in the public SDK, so we use `Runtime.exec("logcat")`
 * instead. A background thread reads logcat output and parses each line into
 * the buffer. This works on all devices where `logcat` is available (which is
 * all of them), and falls back gracefully when the shell is denied.
 *
 * Installation is explicit, not a side effect of loading this class. A class-
 * level `init` block would register the sink the first time any code touched
 * LogCollector — including a unit test that never wants a log sink — and the
 * Android runtime would throw during static initialisation. Callers that want
 * capture call [install] once, early.
 */
object LogCollector {

    /** Result of [install]. */
    enum class InstallResult {
        /** Sink attached; lines are being captured. */
        ATTACHED,

        /** Sink already installed by this process; nothing to do. */
        ALREADY_ATTACHED,

        /** The platform refused the sink. Capture is unavailable. */
        REFUSED,
    }

    const val CAPACITY = 2000
    const val MAX_MESSAGE_CHARS = 512

    // Exactly the tag set the phone app's logcat filter selected, so the two
    // viewers report the same traffic.
    private val CAPTURED_TAGS: Set<String> = setOf(
        "P2PTapVpnService",
        "P2PTap",
        "GoLog",
        "Node",
        "Protect",
        "Gateway",
        "P2P",
        "AppConfig",
        "MainViewModel",
        "P2PTapLogs",
        "P2PTapUi",
    )

    // libgojni prints a few colour sequences; logcat strips them, so we do.
    // Same pattern as the phone LogViewerActivity, so both viewers render
    // identical text.
    private val ANSI: Regex by lazy {
        val esc = 27.toChar()
        Regex(esc.toString() + "\\[[;\\d]*[a-zA-Z]|\\[[0-9;]+m")
    }

    // Parse logcat -v time format:
    // MM-DD HH:MM:SS.mmm L/TAG(PID): MESSAGE
    private val LOGCAT_PATTERN: Regex by lazy {
        Regex(
            "^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+" +
                "([VDIWEF])/(.+?)\\(\\s*(\\d+)\\):\\s(.*)$"
        )
    }

    private val buffer = LogBuffer(CAPACITY)
    private val dropped = AtomicLong(0)
    private val installed = AtomicReference<InstallResult?>(null)

    @Volatile
    private var logcatProcess: Process? = null
    @Volatile
    private var logcatThread: Thread? = null
    @Volatile
    private var stopped = false

    /**
     * Register the sink. Safe to call repeatedly; only the first call takes
     * effect and later calls return ALREADY_ATTACHED with the original
     * outcome.
     */
    @JvmStatic
    fun install(): InstallResult {
        installed.get()?.let { return if (it == InstallResult.REFUSED) it else InstallResult.ALREADY_ATTACHED }

        synchronized(installed) {
            installed.get()?.let { return if (it == InstallResult.REFUSED) it else InstallResult.ALREADY_ATTACHED }

            stopped = false
            val outcome = startLogcatReader()
            installed.set(outcome)
            if (outcome == InstallResult.REFUSED) {
                Log.w("P2PTapLogCollector", "log capture unavailable (logcat refused)")
            }
            return outcome
        }
    }

    /**
     * Stop the logcat reader. Resets state so [install] can be called again.
     */
    @JvmStatic
    fun stop() {
        synchronized(installed) {
            stopped = true
            logcatThread?.interrupt()
            logcatProcess?.destroy()
            logcatThread = null
            logcatProcess = null
            installed.set(null)
        }
    }

    /**
     * Start a logcat subprocess to read logs. This replaces the hidden-API
     * LogWriter approach: `android.util.LogWriter` and `Log.setLogWriter()`
     * are not available in the public SDK, so the previous implementation
     * could not compile against it.
     */
    private fun startLogcatReader(): InstallResult {
        return try {
            val tags = CAPTURED_TAGS.joinToString(" ") { "$it:*" }
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "-s", tags))

            logcatThread = Thread("P2PTap-LogcatReader") {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            processLogLine(line)
                        }
                    }
                } catch (e: Exception) {
                    // Process exited or was destroyed; log capture stops.
                } finally {
                    if (!stopped) {
                        // Process died unexpectedly, not stopped by caller.
                        // Mark as REFUSED so install() can be retried.
                        installed.set(InstallResult.REFUSED)
                    }
                    logcatThread = null
                    logcatProcess = null
                }
            }
            logcatThread!!.start()
            logcatProcess = process

            InstallResult.ATTACHED
        } catch (e: Exception) {
            Log.w("P2PTapLogCollector", "logcat unavailable: ${e.message}")
            InstallResult.REFUSED
        }
    }

    /**
     * Parse a single logcat line in time format and add to buffer.
     * Format: `MM-DD HH:MM:SS.mmm LEVEL/TAG(PID): MESSAGE`
     */
    private fun processLogLine(line: String) {
        val match = LOGCAT_PATTERN.find(line) ?: return

        val level = match.groupValues[2]
        val tag = match.groupValues[3]
        val message = match.groupValues[5]

        val priority = when (level) {
            "V" -> Log.VERBOSE
            "D" -> Log.DEBUG
            "I" -> Log.INFO
            "W" -> Log.WARN
            "E" -> Log.ERROR
            "F" -> Log.ASSERT
            else -> return
        }

        val clean = message.replace(ANSI, "").trimEnd()
        if (clean.isEmpty()) return

        val truncated = if (clean.length > MAX_MESSAGE_CHARS) {
            clean.substring(0, MAX_MESSAGE_CHARS) + "…[+" +
                (clean.length - MAX_MESSAGE_CHARS) + " chars]"
        } else clean

        val entry = LogEntry(currentTimeMillis(), priority, tag, truncated)
        if (buffer.add(entry) != null) {
            dropped.incrementAndGet()
        }
    }

    /** True when the platform accepted the sink and lines are being captured. */
    @JvmStatic
    val isAttached: Boolean
        get() = installed.get() == InstallResult.ATTACHED

    /** The install outcome, or null if [install] has never been called. */
    @JvmStatic
    val state: InstallResult?
        get() = installed.get()

    @JvmStatic
    val lineCount: Int
        get() = buffer.size

    /** True once the tail has wrapped and the oldest lines are being evicted. */
    @JvmStatic
    val isFull: Boolean
        get() = buffer.isFull

    /** Lines evicted for want of space. Non-zero means the tail is current but the head is not. */
    @JvmStatic
    val droppedCount: Long
        get() = dropped.get()

    @JvmStatic
    fun clear() {
        buffer.clear()
    }

    /** Everything held, oldest first. */
    @JvmStatic
    fun snapshot(): List<LogEntry> = buffer.snapshot()

    /** The most recent [maxLines] entries, oldest first. */
    @JvmStatic
    fun tail(maxLines: Int): List<LogEntry> = buffer.tail(maxLines)

    /** Plain text for clipboard and share intents. */
    @JvmStatic
    fun text(): String = LogFormat.formatAll(buffer.snapshot())
}
