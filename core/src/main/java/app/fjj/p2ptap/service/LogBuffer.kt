package app.fjj.p2ptap.service

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One captured log line.
 *
 * `priority` is android.util.Log's numeric code, not a label, so this file
 * has no Android dependency and stays usable from a plain JVM unit test.
 */
data class LogEntry(
    val timestampMs: Long,
    val priority: Int,
    val tag: String,
    val message: String,
)

/**
 * Bounded in-process log ring buffer.
 *
 * Deliberately free of Android imports. The phone app's log viewer shells out
 * to `logcat`, which is exactly the call a locked-down set-top box refuses;
 * the whole reason this class exists is to make the ring buffer a plain data
 * structure that an in-process sink can feed without any process boundary.
 */
class LogBuffer(capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val slots = arrayOfNulls<LogEntry>(capacity)
    private val cap = capacity
    private var count = 0
    private var oldest = 0

    /** Number of lines held, capped at [capacity]. */
    val size: Int
        @Synchronized get() = count

    /** True once the tail has wrapped, i.e. the oldest lines are being evicted. */
    val isFull: Boolean
        @Synchronized get() = count == cap

    /**
     * Add one line, evicting the oldest when full.
     *
     * Returns the line that was dropped, or null while the buffer is not yet
     * full. Callers that want a "truncated" indicator can count these.
     */
    @Synchronized
    fun add(entry: LogEntry): LogEntry? {
        if (count < cap) {
            slots[count] = entry
            count++
            return null
        }
        val evicted = slots[oldest]
        slots[oldest] = entry
        oldest = if (oldest + 1 == cap) 0 else oldest + 1
        return evicted
    }

    @Synchronized
    fun clear() {
        count = 0
        oldest = 0
        slots.fill(null)
    }

    /**
     * A copy of the current contents, oldest first.
     *
     * A copy rather than the internal array: the sink keeps writing into the
     * buffer while a UI thread renders, and a live list would race and could
     * hand out nulls mid-wrap.
     */
    @Synchronized
    fun snapshot(): List<LogEntry> {
        if (count == 0) return emptyList()
        val out = ArrayList<LogEntry>(count)
        var i = oldest
        var n = 0
        while (n < count) {
            val entry = slots[i]
            if (entry != null) out.add(entry)
            i = if (i + 1 == cap) 0 else i + 1
            n++
        }
        return out
    }

    /** The most recent [maxLines] entries, oldest first. */
    fun tail(maxLines: Int): List<LogEntry> {
        if (maxLines <= 0) return emptyList()
        synchronized(this) {
            if (count == 0) return emptyList()
            val n = minOf(maxLines, count)
            // Start at the n-th entry before the tail and walk forward.
            // This avoids the O(capacity) copy that snapshot() performs.
            var i = oldest + ((count - n + cap) % cap)
            val out = ArrayList<LogEntry>(n)
            var k = 0
            while (k < n) {
                val entry = slots[i]
                if (entry != null) out.add(entry)
                i = if (i + 1 == cap) 0 else i + 1
                k++
            }
            return out
        }
    }
}

/**
 * Pure formatting helpers, split out so the priority labels and the line
 * shape can be tested without touching a log sink.
 */
object LogFormat {

    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    // Matches logcat -v time so a reader can diff against a real dump.
    private val TIME_FORMAT = DateTimeFormatter
        .ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())

    /**
     * android.util.Log's codes are sparse, so an unknown value must not be
     * rendered as a bare digit the reader has to look up.
     */
    fun label(priority: Int): String = when (priority) {
        VERBOSE -> "V"
        DEBUG -> "D"
        INFO -> "I"
        WARN -> "W"
        ERROR -> "E"
        ASSERT -> "F"
        else -> "U"
    }

    /** One line in logcat's `-v time` shape: `MM-dd HH:mm:ss.SSS tag [P] msg`. */
    fun format(entry: LogEntry): String =
        "${TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestampMs))} " +
            "${entry.tag} [${label(entry.priority)}] ${entry.message}"

    /** Plain text for clipboard and share intents. */
    fun formatAll(entries: List<LogEntry>): String =
        entries.joinToString("\n") { format(it) }
}
