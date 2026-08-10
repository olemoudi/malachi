package dev.malachi.stats

import android.content.Context
import dev.malachi.debug.DebugLog
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
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
class StatsStore(private val directory: File) {

    /** The real one. The [File] constructor is what lets a test exercise any of this. */
    constructor(context: Context) : this(context.filesDir)

    private val file = File(directory, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "malachi-stats").apply { isDaemon = true }
    }

    /** Everything except today, as last written. */
    private var history: StatsData = StatsData()

    private var currentDay: Long = 0
    private var todayCounts: Counts = Counts()
    private val todayApps = HashMap<String, Counts>()
    private var sinceLastFlush = 0

    /**
     * Bounds of [currentDay] in wall-clock millis. Cached because the alternative is building an
     * Instant, a ZonedDateTime and a LocalDate — and consulting the timezone rules — once per DNS
     * lookup, on the read loop, to answer a question whose answer changes at midnight.
     */
    private var dayStartMs = 0L
    private var dayEndMs = 0L

    /**
     * Whether the stored file has been read yet.
     *
     * Until it has, what is in memory is not the history — it is whatever has been recorded
     * since the process started. Writing that out would replace months of counters with a few
     * seconds of them, so a flush that arrives first is skipped rather than served.
     */
    @Volatile private var loaded = false

    init {
        synchronized(lock) { setDayLocked(today().toEpochDay()) }
        io.execute {
            val stored = runCatching {
                if (file.exists()) json.decodeFromString(StatsData.serializer(), file.readText()) else null
            }.getOrElse {
                DebugLog.w(TAG, "unreadable statistics; starting a fresh set", it)
                null
            } ?: StatsData(sinceEpochDay = today().toEpochDay())

            runCatching {
                synchronized(lock) {
                    // Anything already recorded while this read was in flight belongs to today
                    // and is folded in rather than dropped.
                    history = stored.withoutDay(currentDay)
                    stored.days.firstOrNull { it.epochDay == currentDay }?.let { existing ->
                        todayCounts += existing.counts
                        for ((pkg, counts) in existing.apps) {
                            todayApps[pkg] = (todayApps[pkg] ?: Counts()) + counts
                        }
                    }
                }
            }.onFailure { DebugLog.w(TAG, "could not adopt the stored statistics", it) }
            loaded = true
        }
    }

    /**
     * Notes one decided lookup. [packageName] is null when the lookup could not be attributed,
     * in which case it still counts towards the totals but not towards any app's share.
     */
    fun record(packageName: String?, wasBlocked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        var flushNeeded = false
        synchronized(lock) {
            // The cheap check first: inside the cached day, which it is for every lookup but the
            // first of the day, this is two comparisons and no allocation at all.
            if (nowMs < dayStartMs || nowMs >= dayEndMs) {
                val day = epochDayOf(nowMs)
                if (day != currentDay) {
                    rollOverLocked(day)
                    flushNeeded = true
                } else {
                    // Same day, different bounds: the timezone moved under us.
                    setDayLocked(day)
                }
            }
            todayCounts = todayCounts.record(wasBlocked)
            if (packageName != null) {
                todayApps[packageName] = (todayApps[packageName] ?: Counts()).record(wasBlocked)
            }
            if (++sinceLastFlush >= FLUSH_EVERY_LOOKUPS) {
                sinceLastFlush = 0
                flushNeeded = true
            }
        }
        if (flushNeeded) flush()
    }

    /** Everything known right now, today included. Built on demand; nothing publishes per query. */
    fun snapshot(): StatsData = synchronized(lock) { mergedLocked() }

    /**
     * Writes the current state out. Safe to call from anywhere; the snapshot and the write both
     * happen on the store's own thread, so the read loop is never the one sorting ninety days of
     * counters or waiting on a disk.
     */
    fun flush() {
        io.execute {
            val snapshot = synchronized(lock) {
                // Nothing to save yet, and saving anyway would overwrite the file we are about
                // to read with the handful of lookups seen since the process started.
                if (!loaded) return@execute
                mergedLocked().pruned(today())
            }
            runCatching {
                // Written beside and renamed, so a kill mid-write cannot leave a half-file that
                // reads as corrupt and throws the history away. The rename is only atomic with
                // respect to *this* process, though — the bytes have to reach the disk first, or
                // a power cut can leave the new name pointing at nothing.
                val tmp = File(directory, "$FILE_NAME.tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(json.encodeToString(StatsData.serializer(), snapshot).toByteArray())
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }.onFailure { DebugLog.w(TAG, "could not save statistics", it) }
        }
    }

    /**
     * Waits for the store's queued work to drain. Exists for the tests: every write here is
     * asynchronous by design, and a test that asserted on the file without this would be
     * asserting on a race.
     */
    internal fun awaitIdle(timeoutMs: Long = 5_000) {
        val done = java.util.concurrent.CountDownLatch(1)
        io.execute { done.countDown() }
        done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Forgets the counters in [window], on disk as well as in memory.
     *
     * Scoped rather than all-or-nothing because the button that reaches this is one tap on a
     * screen people scroll through, and "forget today" is almost always what they meant. The
     * all-time totals are reduced to match, since they are carried separately from the per-day
     * detail and would otherwise keep counting lookups the app now says it never saw.
     */
    fun clear(window: StatsWindow = StatsWindow.ALL) {
        synchronized(lock) {
            val today = today()
            if (window == StatsWindow.ALL) {
                history = StatsData(sinceEpochDay = today.toEpochDay())
                setDayLocked(today.toEpochDay())
                todayCounts = Counts()
                todayApps.clear()
            } else {
                val remaining = mergedLocked().withoutWindow(window, today)
                history = remaining.withoutDay(currentDay)
                val stillToday = remaining.days.firstOrNull { it.epochDay == currentDay }
                todayCounts = stillToday?.counts ?: Counts()
                todayApps.clear()
                stillToday?.apps?.forEach { (pkg, counts) -> todayApps[pkg] = counts }
            }
            sinceLastFlush = 0
        }
        if (window == StatsWindow.ALL) {
            io.execute { runCatching { file.delete() } }
        } else {
            flush()
        }
    }

    /**
     * Folds the finished day into the history and starts a new one.
     *
     * The new day is *resumed* rather than started from zero, because the day number can move
     * backwards: a timezone change on a flight, and daylight saving twice a year, both do it.
     * Starting fresh would have the next flush write an empty record over a day that already
     * had counts, quietly losing them — which is the sort of bug that only shows up in
     * October, on one phone, once.
     */
    private fun rollOverLocked(newDay: Long) {
        history = mergedLocked().pruned(LocalDate.ofEpochDay(newDay))
        setDayLocked(newDay)
        val existing = history.days.firstOrNull { it.epochDay == newDay }
        todayCounts = existing?.counts ?: Counts()
        todayApps.clear()
        existing?.apps?.forEach { (pkg, counts) -> todayApps[pkg] = counts }
        // Held once, in memory, and merged back on read; leaving the copy in history too would
        // double every number for this day. Dropping it from `days` alone was not enough —
        // the all-time totals carry it as well, and a day resumed after the clock moved
        // backwards was counted twice in them for the life of the install.
        history = history.withoutDay(newDay)
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

    /** Moves to [day] and recomputes the millisecond window the hot path compares against. */
    private fun setDayLocked(day: Long) {
        currentDay = day
        val zone = ZoneId.systemDefault()
        dayStartMs = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        dayEndMs = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli()
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
