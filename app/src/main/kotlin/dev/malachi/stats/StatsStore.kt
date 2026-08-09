package dev.malachi.stats

import android.content.Context
import dev.malachi.debug.DebugLog
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Keeps the counters across restarts, and keeps them cheap.
 *
 * Two constraints shape this. It is written from the tunnel's read loop, so recording a lookup
 * must not touch the disk; and the service runs for weeks, so there can be no flush *timer* —
 * a periodic wakeup to save a number nobody is reading is exactly the kind of background cost
 * this app refuses to have.
 *
 * So the flush is event-driven: every [FLUSH_EVERY_LOOKUPS] lookups, when the day rolls over,
 * and when the tunnel stops. The worst case for an abrupt kill is losing the tail of a day's
 * counts, which is the right thing to trade for never waking the phone to write statistics.
 */
class StatsStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "malachi-stats").apply { isDaemon = true }
    }

    /** Everything except today, as last written. */
    private var history: StatsData = StatsData()

    private var currentDay: Long = today().toEpochDay()
    private var todayCounts: Counts = Counts()
    private val todayApps = HashMap<String, Counts>()
    private var sinceLastFlush = 0

    init {
        io.execute {
            val loaded = runCatching {
                if (file.exists()) json.decodeFromString(StatsData.serializer(), file.readText()) else null
            }.getOrElse {
                DebugLog.w(TAG, "unreadable statistics; starting a fresh set", it)
                null
            } ?: StatsData(sinceEpochDay = today().toEpochDay())

            synchronized(lock) {
                // Anything already recorded while this read was in flight belongs to today and
                // is folded in rather than dropped.
                history = loaded.withoutDay(currentDay)
                loaded.days.firstOrNull { it.epochDay == currentDay }?.let { existing ->
                    todayCounts += existing.counts
                    for ((pkg, counts) in existing.apps) {
                        todayApps[pkg] = (todayApps[pkg] ?: Counts()) + counts
                    }
                }
            }
        }
    }

    /**
     * Notes one decided lookup. [packageName] is null when the lookup could not be attributed,
     * in which case it still counts towards the totals but not towards any app's share.
     */
    fun record(packageName: String?, wasBlocked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val day = epochDayOf(nowMs)
        var flushNeeded = false
        synchronized(lock) {
            if (day != currentDay) {
                rollOverLocked(day)
                flushNeeded = true
            }
            todayCounts = todayCounts.record(wasBlocked)
            if (packageName != null) {
                todayApps[packageName] = (todayApps[packageName] ?: Counts()).record(wasBlocked)
            }
            if (++sinceLastFlush >= FLUSH_EVERY_LOOKUPS) flushNeeded = true
        }
        if (flushNeeded) flush()
    }

    /** Everything known right now, today included. Built on demand; nothing publishes per query. */
    fun snapshot(): StatsData = synchronized(lock) { mergedLocked() }

    /** Writes the current state out. Safe to call from anywhere; the write itself is off-thread. */
    fun flush() {
        val snapshot = synchronized(lock) {
            sinceLastFlush = 0
            mergedLocked().pruned(today())
        }
        io.execute {
            runCatching {
                // Written beside and renamed, so a kill mid-write cannot leave a half-file that
                // reads as corrupt and throws the history away.
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json.encodeToString(StatsData.serializer(), snapshot))
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }.onFailure { DebugLog.w(TAG, "could not save statistics", it) }
        }
    }

    /** Forgets every counter, on disk as well as in memory. */
    fun clear() {
        synchronized(lock) {
            history = StatsData(sinceEpochDay = today().toEpochDay())
            currentDay = today().toEpochDay()
            todayCounts = Counts()
            todayApps.clear()
            sinceLastFlush = 0
        }
        io.execute { runCatching { file.delete() } }
    }

    /** Folds the finished day into the history and starts a new one. */
    private fun rollOverLocked(newDay: Long) {
        history = mergedLocked().pruned(LocalDate.ofEpochDay(newDay))
        currentDay = newDay
        todayCounts = Counts()
        todayApps.clear()
    }

    private fun mergedLocked(): StatsData {
        val days = history.days.filterNot { it.epochDay == currentDay } +
            DayStats(currentDay, todayCounts, HashMap(todayApps))
        val allTimeApps = HashMap(history.allTimeApps)
        for ((pkg, counts) in todayApps) {
            allTimeApps[pkg] = (allTimeApps[pkg] ?: Counts()) + counts
        }
        return history.copy(
            days = days.sortedBy { it.epochDay },
            allTime = history.allTime + todayCounts,
            allTimeApps = allTimeApps,
            sinceEpochDay = if (history.sinceEpochDay == 0L) currentDay else history.sinceEpochDay,
        )
    }

    /**
     * The history without [day], because today's numbers are held in memory and merged back on
     * read — keeping both would double-count everything recorded since the last flush.
     */
    private fun StatsData.withoutDay(day: Long): StatsData {
        val todayApps = days.firstOrNull { it.epochDay == day }?.apps.orEmpty()
        val todayCounts = days.firstOrNull { it.epochDay == day }?.counts ?: Counts()
        val remainingAllTimeApps = HashMap(allTimeApps)
        for ((pkg, counts) in todayApps) {
            val left = (remainingAllTimeApps[pkg] ?: Counts())
            remainingAllTimeApps[pkg] = Counts(
                (left.blocked - counts.blocked).coerceAtLeast(0),
                (left.total - counts.total).coerceAtLeast(0),
            )
        }
        return copy(
            days = days.filterNot { it.epochDay == day },
            allTime = Counts(
                (allTime.blocked - todayCounts.blocked).coerceAtLeast(0),
                (allTime.total - todayCounts.total).coerceAtLeast(0),
            ),
            allTimeApps = remainingAllTimeApps.filterValues { it.total > 0 },
        )
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    private fun epochDayOf(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private companion object {
        const val TAG = "MalachiStats"
        const val FILE_NAME = "stats.json"

        /** Roughly a quarter of an hour of ordinary browsing; a few small writes an hour. */
        const val FLUSH_EVERY_LOOKUPS = 500
    }
}
