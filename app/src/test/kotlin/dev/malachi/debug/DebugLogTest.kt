package dev.malachi.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The buffer a support report is copied out of.
 *
 * Its one hard requirement is that turning the diagnostics window on must not throw away the
 * history that explains what led to whatever is being diagnosed.
 */
class DebugLogTest {

    private fun entry(level: Char, message: String, at: Long = 0) =
        LogEntry(epochMillis = at, level = level, tag = "T", message = message)

    private fun fill(entries: List<LogEntry>, max: Int, maxTraces: Int) =
        entries.fold(emptyList<LogEntry>()) { acc, e -> LogFormat.cap(acc, e, max, maxTraces) }

    @Test
    fun `a storm of traces cannot evict the events that explain it`() {
        // The reported shape: a quarter of an hour of diagnostics is thousands of per-lookup
        // lines, and every network adoption and resolver change was pushed out of the copied
        // text by them — so the advice became "don't turn it on", which is not what a
        // diagnostic is for.
        val events = (1..20).map { entry('I', "network wlan$it: dns=[…]") }
        val traces = (1..5_000).map { entry('T', "example$it.com: answered") }

        val buffer = fill(events + traces, max = 500, maxTraces = 300)

        assertEquals(20, buffer.count { it.level == 'I' }, "the events were evicted by the traces")
        assertEquals(300, buffer.count { it.level == LogFormat.TRACE })
        // And the traces kept are the most recent ones, which are the ones being reproduced.
        assertTrue(buffer.last().message.endsWith("example5000.com: answered"))
    }

    @Test
    fun `events still evict events once the buffer is full`() {
        // The quota reserves room for events; it does not make the buffer unbounded.
        val buffer = fill((1..900).map { entry('I', "line $it") }, max = 500, maxTraces = 300)

        assertEquals(500, buffer.size)
        assertEquals("line 401", buffer.first().message)
        assertEquals("line 900", buffer.last().message)
    }

    @Test
    fun `traces alone still fill the buffer to their quota and no further`() {
        val buffer = fill((1..1_000).map { entry('T', "lookup $it") }, max = 500, maxTraces = 300)

        assertEquals(300, buffer.size)
        assertEquals("lookup 701", buffer.first().message)
    }

    @Test
    fun `without a quota the old behaviour is unchanged`() {
        // The default keeps `cap` usable as a plain ring buffer, which is what the file half of
        // the log — which never sees a trace — still wants.
        val buffer = fill((1..10).map { entry('I', "line $it") }, max = 5, maxTraces = 5)

        assertEquals(5, buffer.size)
        assertEquals("line 6", buffer.first().message)
    }
}
