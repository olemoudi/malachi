package dev.malachi.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The arithmetic behind the statistics screen's newer half: the day-by-day chart, the "than
 * last week" line, and forgetting one window without corrupting the rest.
 */
class StatsWindowsTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 18) // a Wednesday

    private fun day(offset: Long, blocked: Long, total: Long, app: String = "com.example.app") = DayStats(
        epochDay = today.plusDays(offset).toEpochDay(),
        counts = Counts(blocked, total),
        apps = mapOf(app to Counts(blocked, total)),
    )

    private fun data(vararg days: DayStats): StatsData {
        val allTime = days.fold(Counts()) { acc, d -> acc + d.counts }
        val apps = HashMap<String, Counts>()
        days.forEach { d -> d.apps.forEach { (k, v) -> apps[k] = (apps[k] ?: Counts()) + v } }
        return StatsData(
            days = days.sortedBy { it.epochDay },
            allTime = allTime,
            allTimeApps = apps,
            sinceEpochDay = days.minOfOrNull { it.epochDay } ?: 0,
        )
    }

    // ---- the chart ------------------------------------------------------------------------

    @Test
    fun `a day with nothing recorded is a gap, not a missing column`() {
        // A chart drawn only from the days that have entries closes up the days the filter was
        // off, which is exactly the shape somebody is looking for when they ask why a day looks
        // strange.
        val stats = data(day(-4, 10, 20), day(-1, 5, 10))
        val series = stats.dailySeries(today.minusDays(4), today)

        assertEquals(5, series.size)
        assertEquals(listOf(10L, 0L, 0L, 5L, 0L), series.map { it.counts.blocked })
        assertEquals(today.toEpochDay(), series.last().epochDay)
    }

    @Test
    fun `a single day is a series of one`() {
        val series = data(day(0, 3, 4)).dailySeries(today, today)
        assertEquals(1, series.size)
        assertEquals(3, series.single().counts.blocked)
    }

    @Test
    fun `the chart spans more days the wider the window`() {
        assertEquals(7, StatsData.chartDays(StatsWindow.TODAY))
        assertEquals(7, StatsData.chartDays(StatsWindow.WEEK))
        assertEquals(30, StatsData.chartDays(StatsWindow.MONTH))
        // Never more than what is kept, or the chart would draw days that were pruned as zero.
        assertEquals(StatsData.RETAINED_DAYS, StatsData.chartDays(StatsWindow.ALL))
    }

    // ---- the comparison --------------------------------------------------------------------

    @Test
    fun `yesterday is what today is compared against`() {
        val stats = data(day(-1, 4, 40), day(0, 5, 50))
        assertEquals(40, stats.previousWindow(StatsWindow.TODAY, today)?.counts?.total)
        assertEquals(50, stats.window(StatsWindow.TODAY, today).counts.total)
    }

    @Test
    fun `last week is the seven days before this week began, and does not overlap it`() {
        // Today is a Wednesday, so this week began on Monday: the previous window has to end on
        // the Sunday and not swallow a day of the current one.
        val monday = today.minusDays(2)
        val stats = data(
            day(-9, 1, 10), // the Monday before
            day(-3, 1, 10), // the Sunday before
            day(-2, 1, 10), // this Monday
            day(0, 1, 10),
        )
        val previous = stats.previousWindow(StatsWindow.WEEK, today)!!
        val current = stats.window(StatsWindow.WEEK, today)

        assertEquals(20, previous.counts.total)
        assertEquals(20, current.counts.total)
        assertEquals(monday.dayOfWeek.value, 1)
    }

    @Test
    fun `all time has nothing before it to compare against`() {
        // Its total includes days whose detail was pruned, so any comparison would be
        // arithmetic on two different kinds of number.
        assertNull(data(day(0, 1, 2)).previousWindow(StatsWindow.ALL, today))
    }

    // ---- forgetting one window ---------------------------------------------------------------

    @Test
    fun `forgetting today leaves every other day alone`() {
        val stats = data(day(-2, 7, 10), day(-1, 3, 10), day(0, 5, 10))
        val after = stats.withoutWindow(StatsWindow.TODAY, today)

        assertEquals(2, after.days.size)
        assertTrue(after.days.none { it.epochDay == today.toEpochDay() })
        // The all-time totals are carried separately, so they have to be reduced to match or the
        // headline percentage is computed from lookups the app now says it never saw.
        assertEquals(20, after.allTime.total)
        assertEquals(10, after.allTime.blocked)
        assertEquals(20, after.allTimeApps["com.example.app"]?.total)
    }

    @Test
    fun `forgetting this week takes the whole week and nothing earlier`() {
        val stats = data(day(-9, 1, 10), day(-2, 2, 20), day(0, 3, 30))
        val after = stats.withoutWindow(StatsWindow.WEEK, today)

        assertEquals(listOf(today.minusDays(9).toEpochDay()), after.days.map { it.epochDay })
        assertEquals(10, after.allTime.total)
    }

    @Test
    fun `forgetting everything leaves nothing behind`() {
        val after = data(day(-2, 7, 10), day(0, 5, 10)).withoutWindow(StatsWindow.ALL, today)
        assertEquals(0, after.allTime.total)
        assertTrue(after.days.isEmpty())
        assertTrue(after.allTimeApps.isEmpty())
        assertEquals(today.toEpochDay(), after.sinceEpochDay)
    }

    @Test
    fun `forgetting a window with nothing in it changes nothing`() {
        val stats = data(day(-40, 1, 10))
        assertEquals(stats, stats.withoutWindow(StatsWindow.WEEK, today))
    }

    @Test
    fun `an all-time total that predates the retained days never goes negative`() {
        // The days are pruned to ninety; the all-time counters are not. Subtracting a window
        // must not be able to drive them below zero when the two disagree.
        val stats = StatsData(
            days = listOf(day(0, 5, 10)),
            allTime = Counts(2, 4),
            allTimeApps = mapOf("com.example.app" to Counts(1, 2)),
        )
        val after = stats.withoutWindow(StatsWindow.TODAY, today)
        assertEquals(0, after.allTime.total)
        assertEquals(0, after.allTime.blocked)
        assertTrue(after.allTimeApps.values.all { it.total >= 0 })
    }
}
