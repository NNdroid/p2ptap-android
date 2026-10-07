package app.fjj.p2ptap.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring buffer is the part that must be right. Every other layer is thin:
 * if the wrap order is wrong, the diagnostics screen shows the tunnel's
 * oldest errors at exactly the moment the newest ones matter.
 */
class LogBufferTest {

    private fun entry(i: Int) = LogEntry(
        timestampMs = 1_000L * i,
        priority = LogFormat.INFO,
        tag = "T$i",
        message = "m$i",
    )

    @Test
    fun `buffer holds lines in arrival order until it is full`() {
        val buffer = LogBuffer(4)
        for (i in 0..3) buffer.add(entry(i))

        assertTrue(buffer.isFull)
        assertEquals(4, buffer.size)
        assertEquals(listOf("0", "1", "2", "3"), buffer.snapshot().map { it.tag.removePrefix("T") })
    }

    @Test
    fun `wrapping keeps the newest lines and drops the oldest first`() {
        val buffer = LogBuffer(3)
        var evicted: LogEntry? = null
        for (i in 0..6) evicted = buffer.add(entry(i)) ?: evicted

        assertEquals(3, buffer.size)
        assertTrue(buffer.isFull)
        // Filling takes 0, 1, 2. Each later add evicts in order: 3 drops 0,
        // 4 drops 1, 5 drops 2, 6 drops 3. So the surviving three are 4, 5, 6
        // and the last line out the door is T3.
        assertEquals(listOf("T4", "T5", "T6"), buffer.snapshot().map { it.tag })
        assertEquals("T3", evicted?.tag)
    }

    @Test
    fun `add returns null until the buffer is full`() {
        val buffer = LogBuffer(2)
        assertNull(buffer.add(entry(0)))
        assertNull(buffer.add(entry(1)))
        assertEquals("T0", buffer.add(entry(2))?.tag)
        assertEquals("T1", buffer.add(entry(3))?.tag)
    }

    @Test
    fun `clear empties the buffer without changing its capacity`() {
        val buffer = LogBuffer(2)
        buffer.add(entry(0)); buffer.add(entry(1)); buffer.add(entry(2))
        assertTrue(buffer.isFull)

        buffer.clear()

        assertEquals(0, buffer.size)
        assertFalse(buffer.isFull)
        assertEquals(emptyList<LogEntry>(), buffer.snapshot())

        // A cleared buffer starts writing from index 0 again.
        buffer.add(entry(9))
        assertEquals(listOf("T9"), buffer.snapshot().map { it.tag })
    }

    @Test
    fun `tail returns the newest entries oldest first`() {
        val buffer = LogBuffer(5)
        for (i in 0..7) buffer.add(entry(i))

        assertEquals(listOf("T6", "T7"), buffer.tail(2).map { it.tag })
        assertEquals(listOf("T5", "T6", "T7"), buffer.tail(3).map { it.tag })
    }

    @Test
    fun `tail with a non positive count returns nothing`() {
        val buffer = LogBuffer(2)
        buffer.add(entry(0))

        assertEquals(emptyList<LogEntry>(), buffer.tail(0))
        assertEquals(emptyList<LogEntry>(), buffer.tail(-1))
    }

    @Test
    fun `snapshot is a copy, not a view of the internal state`() {
        val buffer = LogBuffer(2)
        buffer.add(entry(0))
        val copy = buffer.snapshot()

        buffer.add(entry(1))
        buffer.add(entry(2))

        assertEquals(listOf("T0"), copy.map { it.tag })
        assertEquals(listOf("T1", "T2"), buffer.snapshot().map { it.tag })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `capacity must be positive`() {
        LogBuffer(0)
    }
}

class LogFormatTest {

    @Test
    fun `priority labels map to logcat letters`() {
        assertEquals("V", LogFormat.label(LogFormat.VERBOSE))
        assertEquals("D", LogFormat.label(LogFormat.DEBUG))
        assertEquals("I", LogFormat.label(LogFormat.INFO))
        assertEquals("W", LogFormat.label(LogFormat.WARN))
        assertEquals("E", LogFormat.label(LogFormat.ERROR))
        assertEquals("F", LogFormat.label(LogFormat.ASSERT))
    }

    @Test
    fun `an unknown priority is not rendered as a bare digit`() {
        assertEquals("U", LogFormat.label(0))
        assertEquals("U", LogFormat.label(1))
        assertEquals("U", LogFormat.label(100))
    }

    @Test
    fun `format renders the logcat time shape`() {
        val line = LogFormat.format(LogEntry(1_000_000_000_123L, LogFormat.WARN, "Node", "hello"))

        // The formatter uses the host's zone, so the date and hour shift with
        // it. Only the sub-second field and the tag/level/message shape are
        // zone independent, and those are what the reader actually relies on.
        assertTrue("expected logcat time shape in: $line",
            line.matches(Regex("\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.123 .*")))
        assertEquals("Node [W] hello", line.substringAfter("123 "))
    }

    @Test
    fun `formatAll joins lines with newlines`() {
        val text = LogFormat.formatAll(listOf(
            LogEntry(0L, LogFormat.INFO, "A", "one"),
            LogEntry(1L, LogFormat.ERROR, "B", "two"),
        ))

        assertEquals(2, text.lineSequence().count())
        assertTrue("missing first line in: $text", text.contains("A [I] one"))
        assertTrue("missing second line in: $text", text.contains("B [E] two"))
    }
}
