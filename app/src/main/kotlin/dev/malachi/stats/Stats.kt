package dev.malachi.stats

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Lookups seen and lookups refused. The only thing this app ever writes down about traffic. */
@Serializable
data class Counts(val blocked: Long = 0, val total: Long = 0) {
    val allowed: Long get() = total - blocked

    /** Rounded, and 0 before anything has been seen rather than a division by zero. */
    val blockedPercent: Int get() = if (total == 0L) 0 else ((blocked * 100) / total).toInt()

    operator fun plus(other: Counts) = Counts(blocked + other.blocked, total + other.total)

    fun record(wasBlocked: Boolean) = Counts(blocked + if (wasBlocked) 1 else 0, total + 1)
}

/** The four spans the activity screen offers. */
enum class StatsWindow { TODAY, WEEK, MONTH, ALL }

/** One day's totals, and the per-app breakdown for that day. */
@Serializable
data class DayStats(
    val epochDay: Long,
    val counts: Counts = Counts(),
    val apps: Map<String, Counts> = emptyMap(),
)

/** One app's share of a window. */
data class AppStat(val packageName: String, val counts: Counts)

/** What one window came to, already ranked. */
data class WindowStats(
    val counts: Counts,
    val apps: List<AppStat>,
) {
    /** The apps responsible for the most refusals. The headline "who is worst" ranking. */
    fun topByBlocked(limit: Int): List<AppStat> =
        apps.filter { it.counts.blocked > 0 }
            .sortedByDescending { it.counts.blocked }
            .take(limit)

    /**
     * The apps with the highest *proportion* of refused lookups — which is a different and often
     * more interesting question than the raw count, because it finds the app that does nothing
     * but phone home rather than the one that simply talks a lot.
     *
     * [minimumLookups] is what keeps that list honest: without it, an app seen twice and blocked
     * twice reports 100% and outranks everything real. A ratio computed from a handful of
     * samples is noise wearing a percentage sign.
     */
    fun topByRate(limit: Int, minimumLookups: Long = MINIMUM_LOOKUPS_FOR_RATE): List<AppStat> =
        apps.filter { it.counts.total >= minimumLookups && it.counts.blocked > 0 }
            .sortedWith(compareByDescending<AppStat> { it.counts.blockedPercent }.thenByDescending { it.counts.blocked })
            .take(limit)

    companion object {
        const val MINIMUM_LOOKUPS_FOR_RATE = 20L
    }
}

/**
 * Everything Malachi remembers about what it has done, and deliberately nothing else.
 *
 * There are no domains here and there never will be: the query log — the one place a domain
 * appears — lives in memory and dies with the process. What is kept is arithmetic. A count of
 * lookups per app per day is enough to answer "what has this been doing all month" and is not a
 * browsing history; you cannot reconstruct a single visited site from it.
 *
 * [allTime] is carried separately from [days] rather than summed from it, because the per-day
 * detail is pruned and an all-time number that quietly reset every few months would be worse
 * than not offering one.
 */
@Serializable
data class StatsData(
    val days: List<DayStats> = emptyList(),
    val allTime: Counts = Counts(),
    val allTimeApps: Map<String, Counts> = emptyMap(),
    /** When counting began, so "all time" can say how long that is. */
    val sinceEpochDay: Long = 0,
) {

    fun window(window: StatsWindow, today: LocalDate): WindowStats {
        if (window == StatsWindow.ALL) {
            return WindowStats(
                counts = allTime,
                apps = allTimeApps.map { AppStat(it.key, it.value) },
            )
        }
        val from = startOf(window, today).toEpochDay()
        val included = days.filter { it.epochDay >= from }
        val apps = HashMap<String, Counts>()
        var counts = Counts()
        for (day in included) {
            counts += day.counts
            for ((pkg, appCounts) in day.apps) {
                apps[pkg] = (apps[pkg] ?: Counts()) + appCounts
            }
        }
        return WindowStats(counts, apps.map { AppStat(it.key, it.value) })
    }

    /**
     * The window immediately before [window], the same length, for a "…than last week" line.
     *
     * Only meaningful for the calendar windows: [StatsWindow.ALL] has nothing before it, and its
     * total includes days whose detail has been pruned, so a comparison would be arithmetic on
     * numbers of different kinds. Null says so rather than inventing one.
     */
    fun previousWindow(window: StatsWindow, today: LocalDate): WindowStats? {
        if (window == StatsWindow.ALL) return null
        val from = startOf(window, today)
        val previousFrom = when (window) {
            StatsWindow.TODAY -> from.minusDays(1)
            StatsWindow.WEEK -> from.minusWeeks(1)
            StatsWindow.MONTH -> from.minusMonths(1)
            StatsWindow.ALL -> return null
        }
        // The day before this window began is the last day of the one before it, so the two
        // never overlap even when a month is shorter than the one before it.
        return between(previousFrom, from.minusDays(1))
    }

    /**
     * Every day from [from] to [to], with the days nothing was recorded present and zero.
     *
     * The gaps matter: a chart drawn from only the days that have entries silently closes up the
     * days the filter was off, which is exactly the shape somebody is looking for when they ask
     * why yesterday looks strange.
     */
    fun dailySeries(from: LocalDate, to: LocalDate): List<DayStats> {
        val byDay = days.associateBy { it.epochDay }
        return generateSequence(from) { if (it < to) it.plusDays(1) else null }
            .map { date -> byDay[date.toEpochDay()] ?: DayStats(date.toEpochDay()) }
            .toList()
    }

    /** The totals over a closed range of days, both ends included. */
    fun between(from: LocalDate, to: LocalDate): WindowStats {
        val included = days.filter { it.epochDay >= from.toEpochDay() && it.epochDay <= to.toEpochDay() }
        val apps = HashMap<String, Counts>()
        var counts = Counts()
        for (day in included) {
            counts += day.counts
            for ((pkg, appCounts) in day.apps) apps[pkg] = (apps[pkg] ?: Counts()) + appCounts
        }
        return WindowStats(counts, apps.map { AppStat(it.key, it.value) })
    }

    /**
     * Everything except the days in [window], with the all-time totals reduced to match.
     *
     * This is "reset today" and its siblings. The all-time counters are carried separately from
     * the per-day detail rather than summed from it, so forgetting a day means subtracting it
     * from them explicitly — otherwise clearing this week would leave a percentage computed from
     * lookups the app now says it never saw.
     */
    fun withoutWindow(window: StatsWindow, today: LocalDate): StatsData {
        if (window == StatsWindow.ALL) return StatsData(sinceEpochDay = today.toEpochDay())
        val from = startOf(window, today).toEpochDay()
        val dropped = days.filter { it.epochDay >= from }
        if (dropped.isEmpty()) return this

        var removed = Counts()
        val remainingApps = HashMap(allTimeApps)
        for (day in dropped) {
            removed += day.counts
            for ((pkg, counts) in day.apps) {
                val left = remainingApps[pkg] ?: Counts()
                remainingApps[pkg] = Counts(
                    (left.blocked - counts.blocked).coerceAtLeast(0),
                    (left.total - counts.total).coerceAtLeast(0),
                )
            }
        }
        return copy(
            days = days.filterNot { it.epochDay >= from },
            allTime = Counts(
                (allTime.blocked - removed.blocked).coerceAtLeast(0),
                (allTime.total - removed.total).coerceAtLeast(0),
            ),
            allTimeApps = remainingApps.filterValues { it.total > 0 },
        )
    }

    /**
     * Drops detail that has aged out and trims the per-app tables.
     *
     * This is the whole answer to "does it still fit on the phone in a year": the file cannot
     * grow past a bounded number of days times a bounded number of apps, whatever happens.
     */
    fun pruned(today: LocalDate): StatsData {
        val oldest = today.toEpochDay() - RETAINED_DAYS
        return copy(
            days = days.asSequence()
                .filter { it.epochDay > oldest }
                .sortedBy { it.epochDay }
                .map { it.copy(apps = it.apps.topBlocked(MAX_APPS_PER_DAY)) }
                .toList(),
            allTimeApps = allTimeApps.topBlocked(MAX_ALL_TIME_APPS),
        )
    }

    private fun Map<String, Counts>.topBlocked(limit: Int): Map<String, Counts> =
        if (size <= limit) {
            this
        } else {
            entries.sortedByDescending { it.value.blocked }
                .take(limit)
                .associate { it.key to it.value }
        }

    companion object {
        /** Enough for "this month" many times over, and small enough to never matter on disk. */
        const val RETAINED_DAYS = 90L
        const val MAX_APPS_PER_DAY = 40
        const val MAX_ALL_TIME_APPS = 200

        /** How many days of history the chart shows for each window. */
        fun chartDays(window: StatsWindow): Long = when (window) {
            StatsWindow.TODAY -> 7
            StatsWindow.WEEK -> 7
            StatsWindow.MONTH -> 30
            StatsWindow.ALL -> RETAINED_DAYS
        }

        /** Calendar-based, because "this week" means the week you are in, not the last 7 days. */
        fun startOf(window: StatsWindow, today: LocalDate): LocalDate = when (window) {
            StatsWindow.TODAY -> today
            StatsWindow.WEEK -> today.minusDays((today.dayOfWeek.value - 1).toLong())
            StatsWindow.MONTH -> today.withDayOfMonth(1)
            StatsWindow.ALL -> LocalDate.MIN
        }
    }
}
