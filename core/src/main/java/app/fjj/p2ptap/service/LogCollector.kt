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

    private val buffer = LogBuffer(CAPACITY)
    private val dropped = AtomicLong(0)
    private val installed = AtomicReference<InstallResult?>(null)

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

            val rc = Log.println(Log.VERBOSE, "P2PTapLogCollector", "sink request")
            val outcome = if (rc == 0) InstallResult.ATTACHED else InstallResult.REFUSED
            installed.set(outcome)
            if (outcome == InstallResult.REFUSED) {
                // Through the public API, not the sink, so a human on a phone
                // can see why the TV screen reported capture as unavailable.
                Log.w("P2PTapLogCollector", "log sink refused by the platform (rc=$rc)")
            }
            return outcome
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

    /**
     * The registered sink. It receives every line this process writes, tagged
     * or untagged, so the tag filter is mandatory — without it this buffer
     * would be filled by the platform's own chatter rather than the tunnel's.
     *
     * Not called directly: the platform invokes it from whatever thread wrote
     * the line. That is why every field touched here is either an Atomic*
     * type or confined to LogBuffer's own monitor.
     */
    internal fun sink(priority: Int, tag: String?, message: String?): Boolean {
        if (installed.get() != InstallResult.ATTACHED) return false
        val t = tag ?: return false
        if (t !in CAPTURED_TAGS) return false
        val body = message ?: return false
        val clean = body.replace(ANSI, "").trimEnd()
        if (clean.isEmpty()) return false

        val truncated = if (clean.length > MAX_MESSAGE_CHARS) {
            clean.substring(0, MAX_MESSAGE_CHARS) + "…[+" +
                (clean.length - MAX_MESSAGE_CHARS) + " chars]"
        } else clean

        val entry = LogEntry(currentTimeMillis(), priority, t, truncated)
        if (buffer.add(entry) != null) {
            dropped.incrementAndGet()
        }
        return true
    }
}
