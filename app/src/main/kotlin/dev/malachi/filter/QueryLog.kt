package dev.malachi.filter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One (app, domain) pair the tunnel has seen, with the verdict it got most recently. */
data class QueryRecord(
    val domain: String,
    val packageName: String?,
    val blocked: Boolean,
    val source: RuleSource,
    val detail: String,
    val count: Int,
    val lastSeenMs: Long,
)

/**
 * What the tunnel has seen, as a value. Separated from the singleton so the merge, the cap and
 * the grouping can be tested without a device.
 */
data class QueryLogState(
    val records: List<QueryRecord> = emptyList(),
    /** Lookups refused since the counters were last reset. */
    val blocked: Long = 0,
    val total: Long = 0,
    /** When counting started, so the UI can say what the numbers are *of*. */
    val sinceMs: Long = 0,
) {
    val allowed: Long get() = total - blocked

    /** Percentage of lookups refused, rounded, or 0 before anything has been seen. */
    val blockedPercent: Int get() = if (total == 0L) 0 else ((blocked * 100) / total).toInt()

    /** Records grouped by app, each group and the groups themselves most-recent first. */
    fun byApp(): List<Pair<String?, List<QueryRecord>>> =
        records.groupBy { it.packageName }.entries
            .sortedByDescending { entry -> entry.value.maxOf { it.lastSeenMs } }
            .map { it.key to it.value.sortedByDescending { r -> r.lastSeenMs } }

    companion object {
        /**
         * Merges one sighting into [records], newest first, capped at [max].
         *
         * A pair is kept once with a count rather than appended per lookup: an app resolving the
         * same tracker forty times in a minute is one fact, and forty lines of it would push the
         * one domain you were looking for off the end of the list.
         */
        fun merge(records: List<QueryRecord>, record: QueryRecord, max: Int): List<QueryRecord> {
            val existing = records.firstOrNull {
                it.packageName == record.packageName && it.domain == record.domain
            }
            val merged = if (existing == null) {
                record
            } else {
                // The newest verdict wins: a domain the user has just allowed should stop
                // reading as blocked, while its history of sightings is kept.
                record.copy(count = existing.count + record.count)
            }
            val rest = if (existing == null) records else records - existing
            return (listOf(merged) + rest).take(max)
        }
    }
}

/**
 * The query log: which app asked for which domain, and what Malachi did about it.
 *
 * This is the feature that makes the rest usable. A blocklist that breaks an app leaves a user
 * with no way to find out *which* lookup broke it, and a tracker a list has missed is invisible
 * until something names it. The tunnel already parses the question and attributes the socket
 * before deciding, so this is a window onto work that was happening anyway.
 *
 * What keeps it safe to have is what it refuses to be: it lives in this process and nowhere
 * else — no file, no database, nothing that survives a restart — and it holds at most
 * [MAX_RECORDS] pairs, so a chatty app can't grow it without bound. The counters are separate
 * from the records: they keep working when the log itself is switched off, because a user who
 * doesn't want a list of their own DNS traffic may still want to know that today's browsing
 * cost them four hundred ad lookups.
 */
object QueryLog {

    const val MAX_RECORDS = 500

    private val _state = MutableStateFlow(QueryLogState(sinceMs = System.currentTimeMillis()))
    val state: StateFlow<QueryLogState> = _state.asStateFlow()

    private val lock = Any()

    /** When false only the counters move; nothing is written down about individual lookups. */
    @Volatile var recording: Boolean = true

    /**
     * Notes one decided lookup. Called from the tunnel's worker threads, so it stays cheap and
     * never throws: a failure to log must not become a failure to resolve.
     */
    fun record(
        domain: String,
        packageName: String?,
        verdict: Verdict,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val current = _state.value
            val records = if (!recording) {
                current.records
            } else {
                QueryLogState.merge(
                    current.records,
                    QueryRecord(
                        domain = domain,
                        packageName = packageName,
                        blocked = verdict.blocked,
                        source = verdict.source,
                        detail = verdict.detail,
                        count = 1,
                        lastSeenMs = nowMs,
                    ),
                    MAX_RECORDS,
                )
            }
            _state.value = current.copy(
                records = records,
                blocked = current.blocked + if (verdict.blocked) 1 else 0,
                total = current.total + 1,
            )
        }
    }

    /** Forgets the sightings but keeps counting; the "clear" button on the log screen. */
    fun clearRecords() {
        synchronized(lock) { _state.value = _state.value.copy(records = emptyList()) }
    }

    /** Forgets everything, counters included. Used when the filter starts a new session. */
    fun reset(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) { _state.value = QueryLogState(sinceMs = nowMs) }
    }
}
