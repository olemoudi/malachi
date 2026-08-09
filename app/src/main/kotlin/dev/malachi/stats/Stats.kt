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

        /** Calendar-based, because "this week" means the week you are in, not the last 7 days. */
        fun startOf(window: StatsWindow, today: LocalDate): LocalDate = when (window) {
            StatsWindow.TODAY -> today
            StatsWindow.WEEK -> today.minusDays((today.dayOfWeek.value - 1).toLong())
            StatsWindow.MONTH -> today.withDayOfMonth(1)
            StatsWindow.ALL -> LocalDate.MIN
        }
    }
}
