package dev.malachi.stats

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * The persistence half of the statistics. [StatsTest] covers the arithmetic; this covers the
 * part that has to survive a process that is killed without warning, months into an install.
 */
class StatsStoreTest {

    @TempDir
    lateinit var directory: File

    private val zone: ZoneId = ZoneId.systemDefault()

    private val opened = mutableListOf<StatsStore>()

    /**
     * Every store here writes on a thread of its own, and a flush can be triggered by a plain
     * `record` — so without draining them the temp directory gets deleted out from under a save
     * still in flight, and the test fails on the cleanup rather than on anything it asserted.
     */
    @AfterEach
    fun drain() = opened.forEach { it.awaitIdle() }

    private fun noonOn(date: LocalDate): Long =
        date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** A store that has not read its file yet, as it is for the first moments of a process. */
    private fun newStore(): StatsStore = StatsStore(directory).also { opened += it }

    /** A store with its stored file already read, which is the state most assertions assume. */
    private fun openStore(): StatsStore = newStore().also { it.awaitIdle() }

    @Test
    fun `counters survive being written and read back`() {
        val store = openStore()
        repeat(3) { store.record("com.example.app", wasBlocked = true) }
        store.record("com.example.app", wasBlocked = false)
        store.record(null, wasBlocked = false)
        store.flush()
        store.awaitIdle()

        val snapshot = openStore().snapshot()
        assertEquals(5, snapshot.allTime.total)
        assertEquals(3, snapshot.allTime.blocked)
        // The unattributed lookup counts towards the totals but towards nobody's share.
        assertEquals(4, snapshot.allTimeApps["com.example.app"]?.total)
    }

    @Test
    fun `a flush issued before the stored file has been read keeps the history`() {
        val seed = openStore()
        repeat(5) { seed.record("com.example.app", wasBlocked = true) }
        seed.flush()
        seed.awaitIdle()

        // Not openStore(): this one records and saves while its own load is still outstanding,
        // which is what happens when the tunnel comes up at process start. Taking the snapshot
        // on the caller's thread wrote those months of counters back out as a single lookup.
        val reopened = newStore()
        reopened.record("com.example.app", wasBlocked = false)
        reopened.flush()
        reopened.awaitIdle()

        assertEquals(6, openStore().snapshot().allTime.total)
    }

    @Test
    fun `a day boundary rolls over without losing the day that ended`() {
        val store = openStore()
        val today = LocalDate.now(zone)
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today.minusDays(1)))
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today))

        val snapshot = store.snapshot()
        assertEquals(2, snapshot.allTime.total)
        assertEquals(2, snapshot.days.size)
        assertEquals(1, snapshot.window(StatsWindow.TODAY, today).counts.total)
    }

    @Test
    fun `two lookups on the same day stay on the same day`() {
        // The hot path compares against a cached millisecond window rather than rebuilding a
        // calendar date per lookup; the window has to actually cover the whole day.
        val store = openStore()
        val today = LocalDate.now(zone)
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today))
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today) + 11 * 3_600_000L)

        assertEquals(1, store.snapshot().days.size)
        assertEquals(2, store.snapshot().allTime.total)
    }

    @Test
    fun `a day that moves backwards resumes rather than restarting`() {
        // A flight west, or the end of daylight saving. Starting the earlier day from zero would
        // have the next flush write an empty record over counters that already existed.
        val store = openStore()
        val today = LocalDate.now(zone)
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today))
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today.minusDays(1)))
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today))

        val snapshot = store.snapshot()
        assertEquals(3, snapshot.allTime.total)
        assertEquals(2, snapshot.window(StatsWindow.TODAY, today).counts.total)
    }

    @Test
    fun `unreadable statistics start a fresh set instead of failing`() {
        File(directory, "stats.json").writeText("{ this is not json")
        val store = openStore()
        store.record("com.example.app", wasBlocked = true)
        assertEquals(1, store.snapshot().allTime.total)
    }

    @Test
    fun `forgetting today keeps the days before it`() {
        val store = openStore()
        val today = LocalDate.now(zone)
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today.minusDays(2)))
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today))
        store.record("com.example.app", wasBlocked = false, nowMs = noonOn(today))
        store.flush()
        store.awaitIdle()

        store.clear(StatsWindow.TODAY)
        store.awaitIdle()

        val snapshot = store.snapshot()
        assertEquals(1, snapshot.allTime.total)
        assertEquals(0, snapshot.window(StatsWindow.TODAY, today).counts.total)
        // And it survived the trip through the disk, rather than only the in-memory copy.
        assertEquals(1, openStore().snapshot().allTime.total)
    }

    @Test
    fun `forgetting this week leaves last month alone`() {
        val store = openStore()
        val today = LocalDate.now(zone)
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today.minusDays(40)))
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today))
        store.flush()
        store.awaitIdle()

        store.clear(StatsWindow.WEEK)
        store.awaitIdle()

        assertEquals(1, store.snapshot().allTime.total)
        assertEquals(0, store.snapshot().window(StatsWindow.TODAY, today).counts.total)
    }

    @Test
    fun `recording after forgetting today starts from zero rather than from a negative`() {
        val store = openStore()
        val today = LocalDate.now(zone)
        repeat(4) { store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today)) }
        store.clear(StatsWindow.TODAY)
        store.record("com.example.app", wasBlocked = true, nowMs = noonOn(today))
        store.awaitIdle()

        assertEquals(1, store.snapshot().window(StatsWindow.TODAY, today).counts.total)
        assertEquals(1, store.snapshot().allTime.total)
    }

    @Test
    fun `clearing forgets the file as well as the memory`() {
        val store = openStore()
        repeat(3) { store.record("com.example.app", wasBlocked = true) }
        store.flush()
        store.awaitIdle()

        store.clear(StatsWindow.ALL)
        store.awaitIdle()

        assertEquals(0, store.snapshot().allTime.total)
        assertEquals(0, openStore().snapshot().allTime.total)
    }
}
