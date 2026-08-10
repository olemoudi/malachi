package dev.malachi

import dev.malachi.debug.DebugLog
import dev.malachi.stats.StatsData
import dev.malachi.stats.StatsStore
import dev.malachi.stats.StatsWindow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a year of being left alone does to the files.
 *
 * This app is meant to run for months without being opened, and the failure mode of that is not
 * a crash — it is a phone with a gigabyte less free space, or an all-time counter that has
 * quietly drifted. Neither shows up in a test that covers an afternoon.
 *
 * Time is simulated rather than waited for. Every clock the storage layer reads is a parameter,
 * so a year passes in a loop: a day is one iteration, and the whole file below runs in about a
 * second. Nothing here sleeps.
 */
class SoakTest {

    @TempDir
    lateinit var directory: File

    /**
     * The debug log is a process-wide singleton that writes on a thread of its own, and it keeps
     * pointing at whatever file it was last given. A directory JUnit manages would be deleted
     * out from under a write still in flight — and the failure then lands on the harness, as
     * "failed to close extension context", rather than on anything the test asserted.
     */
    private val logDirectory: File = Files.createTempDirectory("malachi-log").toFile()

    @AfterEach
    fun releaseTheLog() {
        DebugLog.clear()
        DebugLog.awaitIdle()
        logDirectory.deleteRecursively()
    }

    private val zone: ZoneId = ZoneId.systemDefault()
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    /** Noon on the day [offset] days from today, as the wall clock would report it. */
    private fun noon(offset: Long): Long =
        LocalDate.now(zone).plusDays(offset).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a year of lookups leaves a bounded file and exact all-time totals`() {
        val store = StatsStore(directory).also { it.awaitIdle() }
        val apps = List(60) { "com.example.app$it" }
        val days = 365L
        val perDay = 40
        var expectedTotal = 0L
        var expectedBlocked = 0L

        for (offset in -days until 0) {
            repeat(perDay) { i ->
                val blocked = i % 3 == 0
                store.record(apps[i % apps.size], blocked, nowMs = noon(offset) + i * 60_000L)
                expectedTotal++
                if (blocked) expectedBlocked++
            }
            // Roughly what a real day of use does: a save now and then, not one per lookup.
            if (offset % 7 == 0L) store.flush()
        }
        store.flush()
        store.awaitIdle()

        val snapshot = StatsStore(directory).also { it.awaitIdle() }.snapshot()

        // The all-time counters are carried separately from the per-day detail precisely so
        // they survive the pruning. A year of it must not have drifted by one.
        assertEquals(expectedTotal, snapshot.allTime.total)
        assertEquals(expectedBlocked, snapshot.allTime.blocked)

        // The per-day detail is pruned to its retention window, and the app tables are capped.
        assertTrue(snapshot.days.size <= StatsData.RETAINED_DAYS + 1, "kept ${snapshot.days.size} days")
        assertTrue(snapshot.allTimeApps.size <= StatsData.MAX_ALL_TIME_APPS)
        assertTrue(snapshot.days.all { it.apps.size <= StatsData.MAX_APPS_PER_DAY })

        // And the file itself has a ceiling, which is the whole point.
        val bytes = File(directory, "stats.json").length()
        assertTrue(bytes < 512 * 1024, "a year of statistics came to $bytes bytes")
    }

    @Test
    fun `a month of lookups keeps this month's window honest`() {
        val store = StatsStore(directory).also { it.awaitIdle() }
        val today = LocalDate.now(zone)
        var thisMonth = 0L

        for (offset in -45L until 0L) {
            val at = today.plusDays(offset)
            store.record("com.example.app", wasBlocked = true, nowMs = noon(offset))
            if (!at.isBefore(today.withDayOfMonth(1))) thisMonth++
        }
        store.record("com.example.app", wasBlocked = false, nowMs = noon(0))
        thisMonth++

        assertEquals(thisMonth, store.snapshot().window(StatsWindow.MONTH, today).counts.total)
        store.awaitIdle()
    }

    @Test
    fun `a day that repeats itself is resumed, not restarted`() {
        // Daylight saving ends and an hour happens twice; a flight west and the day number goes
        // backwards. Both used to be able to write an empty record over a day that had counts.
        val store = StatsStore(directory).also { it.awaitIdle() }
        var expected = 0L

        for (round in 0 until 50) {
            val offset = if (round % 2 == 0) 0L else -1L
            store.record("com.example.app", wasBlocked = true, nowMs = noon(offset))
            expected++
            if (round % 10 == 0) store.flush()
        }
        store.flush()
        store.awaitIdle()

        val snapshot = StatsStore(directory).also { it.awaitIdle() }.snapshot()
        assertEquals(expected, snapshot.allTime.total)
        assertEquals(2, snapshot.days.size)
        assertEquals(expected, snapshot.days.sumOf { it.counts.total })
    }

    @Test
    fun `months of log lines stay inside the file's cap`() {
        val log = File(logDirectory, "debug-log.txt")
        DebugLog.clear()
        DebugLog.init(log)
        DebugLog.awaitIdle()

        // Simulated: a few lines a day for a year, plus the occasional stack trace, written as
        // fast as the executor will take them.
        repeat(365) { day ->
            DebugLog.i("Soak", "day $day: tunnel up; upstream=1.1.1.1 scope=ALL_EXCEPT")
            DebugLog.w("Soak", "day $day: dropped 3 packet(s) the tunnel can't carry")
            if (day % 30 == 0) DebugLog.e("Soak", "day $day: something failed", IllegalStateException("x".repeat(2_000)))
        }
        DebugLog.awaitIdle()

        assertTrue(log.length() <= 128 * 1024, "the debug log grew to ${log.length()} bytes")
        // Still readable, and still holding the most recent lines rather than the first ones.
        val tail = log.readLines()
        assertTrue(tail.isNotEmpty())
        assertTrue(tail.last().contains("day 364"), "the newest line was trimmed away")
    }

    @Test
    fun `one enormous entry cannot become the whole log`() {
        val log = File(logDirectory, "debug-log.txt")
        DebugLog.clear()
        DebugLog.init(log)
        DebugLog.awaitIdle()

        DebugLog.i("Soak", "the line before")
        DebugLog.e("Soak", "a pathological trace", IllegalStateException("y".repeat(200_000)))
        DebugLog.i("Soak", "the line after")
        DebugLog.awaitIdle()

        assertTrue(log.length() <= 128 * 1024, "one entry took the log to ${log.length()} bytes")
        assertTrue(DebugLog.format().contains("the line after"))
    }
}
