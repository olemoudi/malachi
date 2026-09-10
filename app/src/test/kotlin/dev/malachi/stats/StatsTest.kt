package dev.malachi.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StatsTest {

    // A Wednesday, so "this week" has days before and after it to get wrong.
    private val today: LocalDate = LocalDate.of(2026, 8, 12)

    private fun day(date: LocalDate, blocked: Long, total: Long, apps: Map<String, Counts> = emptyMap()) =
        DayStats(date.toEpochDay(), Counts(blocked, total), apps)

    @Test
    fun `counts do the arithmetic nobody should repeat`() {
        val counts = Counts(blocked = 3, total = 12)
        assertEquals(9, counts.allowed)
        assertEquals(25, counts.blockedPercent)
        assertEquals(Counts(4, 13), counts.record(wasBlocked = true))
        assertEquals(Counts(3, 13), counts.record(wasBlocked = false))
        assertEquals(Counts(5, 20), counts + Counts(2, 8))
    }

    @Test
    fun `a percentage of nothing is zero, not a crash`() {
        assertEquals(0, Counts().blockedPercent)
    }

    @Test
    fun `today is only today`() {
        val data = StatsData(
            days = listOf(
                day(today, blocked = 5, total = 10),
                day(today.minusDays(1), blocked = 100, total = 200),
            ),
        )
        assertEquals(Counts(5, 10), data.window(StatsWindow.TODAY, today).counts)
    }

    @Test
    fun `the week runs from monday, not seven days back`() {
        // Monday of this week is the 10th. The 9th is last week and must not be counted.
        val data = StatsData(
            days = listOf(
                day(today, 1, 10),
                day(today.minusDays(2), 2, 10), // Monday the 10th
                day(today.minusDays(3), 99, 99), // Sunday the 9th — previous week
            ),
        )
        assertEquals(Counts(3, 20), data.window(StatsWindow.WEEK, today).counts)
    }

    @Test
    fun `the month runs from the first`() {
        val data = StatsData(
            days = listOf(
                day(today, 1, 10),
                day(LocalDate.of(2026, 8, 1), 2, 10),
                day(LocalDate.of(2026, 7, 31), 99, 99),
            ),
        )
        assertEquals(Counts(3, 20), data.window(StatsWindow.MONTH, today).counts)
    }

    @Test
    fun `all time comes from its own counter, not from the retained days`() {
        // The point of keeping it separately: the per-day detail is pruned, and an all-time
        // figure that quietly reset every quarter would be worse than not offering one.
        val data = StatsData(
            days = listOf(day(today, 1, 10)),
            allTime = Counts(5_000, 20_000),
            allTimeApps = mapOf("com.a" to Counts(4_000, 10_000)),
        )
        assertEquals(Counts(5_000, 20_000), data.window(StatsWindow.ALL, today).counts)
        assertEquals(1, data.window(StatsWindow.ALL, today).apps.size)
    }

    @Test
    fun `per-app counts are summed across the days of a window`() {
        val data = StatsData(
            days = listOf(
                day(today, 3, 10, mapOf("com.a" to Counts(3, 5), "com.b" to Counts(0, 5))),
                day(today.minusDays(1), 4, 10, mapOf("com.a" to Counts(4, 6))),
            ),
        )
        val month = data.window(StatsWindow.MONTH, today)
        assertEquals(Counts(7, 11), month.apps.first { it.packageName == "com.a" }.counts)
        assertEquals(Counts(0, 5), month.apps.first { it.packageName == "com.b" }.counts)
    }

    @Test
    fun `most blocked ranks by count and skips apps with none`() {
        val window = WindowStats(
            counts = Counts(30, 100),
            apps = listOf(
                AppStat("com.quiet", Counts(0, 50)),
                AppStat("com.noisy", Counts(25, 400)),
                AppStat("com.middling", Counts(5, 20)),
            ),
        )
        assertEquals(listOf("com.noisy", "com.middling"), window.topByBlocked(5).map { it.packageName })
    }

    @Test
    fun `block rate ignores apps with too few lookups to mean anything`() {
        // Two lookups, both blocked, is not a 100% offender — it is two lookups.
        val window = WindowStats(
            counts = Counts(52, 300),
            apps = listOf(
                AppStat("com.tiny", Counts(2, 2)),
                AppStat("com.tracker", Counts(45, 50)),
                AppStat("com.normal", Counts(5, 200)),
            ),
        )
        val ranked = window.topByRate(5)
        assertEquals(listOf("com.tracker", "com.normal"), ranked.map { it.packageName })
        assertNull(ranked.firstOrNull { it.packageName == "com.tiny" })
    }

    @Test
    fun `the two rankings genuinely disagree`() {
        // Which is the whole reason both are offered: the app with the most refusals and the app
        // that does almost nothing else are usually not the same app.
        val window = WindowStats(
            counts = Counts(140, 1_040),
            apps = listOf(
                AppStat("com.chatty.game", Counts(100, 1_000)),
                AppStat("com.pure.tracker", Counts(40, 40)),
            ),
        )
        assertEquals("com.chatty.game", window.topByBlocked(1).single().packageName)
        assertEquals("com.pure.tracker", window.topByRate(1).single().packageName)
    }

    @Test
    fun `the full list by count keeps the apps that had nothing blocked, last`() {
        // Opened to see every app; one missing would read as an app that was never seen at all.
        val window = WindowStats(
            counts = Counts(30, 470),
            apps = listOf(
                AppStat("com.quiet", Counts(0, 50)),
                AppStat("com.noisy", Counts(25, 400)),
                AppStat("com.middling", Counts(5, 20)),
            ),
        )
        assertEquals(
            listOf("com.noisy", "com.middling", "com.quiet"),
            window.rankedByBlocked().map { it.packageName },
        )
    }

    @Test
    fun `the full list by rate sets aside the apps with too few lookups instead of dropping them`() {
        val window = WindowStats(
            counts = Counts(52, 352),
            apps = listOf(
                AppStat("com.tiny", Counts(2, 2)),
                AppStat("com.clean", Counts(0, 100)),
                AppStat("com.tracker", Counts(45, 50)),
                AppStat("com.normal", Counts(5, 200)),
            ),
        )
        assertEquals(
            listOf("com.tracker", "com.normal", "com.clean"),
            window.rankedByRate().map { it.packageName },
        )
        assertEquals(listOf("com.tiny"), window.tooFewForRate().map { it.packageName })
    }

    @Test
    fun `the full lists open with exactly the rows the short rankings showed, ties included`() {
        // Ties on every sort key the rankings use, so only the final tie-break decides the order.
        // Two windows over the same apps in opposite orders stand in for the panel and the full
        // screen, which each build their own window from a HashMap.
        val apps = listOf(
            AppStat("com.b", Counts(10, 100)),
            AppStat("com.a", Counts(10, 100)),
            AppStat("com.d", Counts(10, 40)),
            AppStat("com.c", Counts(50, 100)),
            AppStat("com.e", Counts(0, 100)),
        )
        val panel = WindowStats(Counts(80, 440), apps)
        val screen = WindowStats(Counts(80, 440), apps.reversed())

        assertEquals(panel.topByBlocked(3), screen.rankedByBlocked().take(3))
        assertEquals(panel.topByRate(3), screen.rankedByRate().take(3))
        assertEquals(panel.rankedByBlocked(), screen.rankedByBlocked())
        assertEquals(panel.rankedByRate(), screen.rankedByRate())
    }

    @Test
    fun `pruning bounds the file whatever happens`() {
        val manyApps = (1..500).associate { "com.app$it" to Counts(it.toLong(), it.toLong() * 2) }
        val data = StatsData(
            days = (0..400L).map { DayStats(today.toEpochDay() - it, Counts(1, 2), manyApps) },
            allTimeApps = manyApps,
        )
        val pruned = data.pruned(today)
        assertEquals(StatsData.RETAINED_DAYS.toInt(), pruned.days.size)
        assertTrue(pruned.days.all { it.apps.size <= StatsData.MAX_APPS_PER_DAY })
        assertEquals(StatsData.MAX_ALL_TIME_APPS, pruned.allTimeApps.size)
    }

    @Test
    fun `pruning keeps the busiest apps, not an arbitrary slice`() {
        val apps = (1..100).associate { "com.app$it" to Counts(it.toLong(), 1_000) }
        val pruned = StatsData(allTimeApps = apps).pruned(today)
        // com.app100 has the most blocks, so it must survive; com.app1 has the fewest.
        assertTrue(pruned.allTimeApps.containsKey("com.app100"))
    }

    @Test
    fun `pruning keeps the days in order and drops only the old ones`() {
        val data = StatsData(days = (0..120L).map { DayStats(today.toEpochDay() - it, Counts(1, 1)) })
        val pruned = data.pruned(today)
        assertEquals(pruned.days.map { it.epochDay }.sorted(), pruned.days.map { it.epochDay })
        assertTrue(pruned.days.none { it.epochDay <= today.toEpochDay() - StatsData.RETAINED_DAYS })
    }

    @Test
    fun `a ranking is said to be missing apps only where the bound could have cut some`() {
        // The bound keeps the apps with the most refusals, so a day or a table *at* the bound is
        // the only trace left that a quieter app was dropped; one below it cannot have been.
        fun apps(count: Int) = (1..count).associate { "com.app$it" to Counts(it.toLong(), 100) }
        val atBound = StatsData(days = listOf(DayStats(today.toEpochDay(), apps = apps(StatsData.MAX_APPS_PER_DAY))))
        val underBound = StatsData(
            days = listOf(DayStats(today.toEpochDay(), apps = apps(StatsData.MAX_APPS_PER_DAY - 1))),
        )
        assertTrue(atBound.mayBeMissingApps(StatsWindow.TODAY, today))
        assertFalse(underBound.mayBeMissingApps(StatsWindow.TODAY, today))

        // A full day outside the window says nothing about the window.
        val fullLastMonth = StatsData(
            days = listOf(
                DayStats(LocalDate.of(2026, 7, 31).toEpochDay(), apps = apps(StatsData.MAX_APPS_PER_DAY)),
                DayStats(today.toEpochDay(), apps = apps(3)),
            ),
        )
        assertFalse(fullLastMonth.mayBeMissingApps(StatsWindow.MONTH, today))
        assertTrue(fullLastMonth.mayBeMissingApps(StatsWindow.MONTH, LocalDate.of(2026, 7, 31)))

        // All time keeps its own table, with its own bound.
        assertTrue(StatsData(allTimeApps = apps(StatsData.MAX_ALL_TIME_APPS)).mayBeMissingApps(StatsWindow.ALL, today))
        assertFalse(
            StatsData(allTimeApps = apps(StatsData.MAX_ALL_TIME_APPS - 1)).mayBeMissingApps(StatsWindow.ALL, today),
        )
    }
}
