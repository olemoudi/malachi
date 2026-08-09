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
 * What the tunnel has seen, as an immutable value handed to the UI. Building one costs a copy of
 * the whole buffer, which is why it is only built when somebody is actually looking (see
 * [QueryLog]).
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
}

/**
 * The query log: which app asked for which domain, and what Malachi did about it.
 *
 * This is the feature that makes the rest usable — a list that breaks an app is untraceable
 * without it — but it also sits on the hot path, called once per DNS lookup for the entire life
 * of the process. So the shape of it is dictated by what it must *not* cost:
 *
 * - **Nothing is published while nobody is watching.** The records live in a mutable LRU map,
 *   and the immutable snapshot the UI reads is only built when [state] has a subscriber. With
 *   the app closed — which is essentially always — recording a lookup is a map put and two
 *   increments, with no list copy and no flow emission to wake a collector.
 * - **The counters are plain longs**, readable without building anything, so a caller that wants
 *   a number does not have to allocate a snapshot of five hundred records to get one.
 * - **Merging is O(1).** A LinkedHashMap in access order is both the index and the recency
 *   order, so the oldest entry falls off the end by itself instead of being searched for.
 *
 * Nothing here is ever written to disk. The whole record dies with the process, which is what
 * makes it acceptable for an app to keep a list of the domains its owner's phone has visited.
 */
object QueryLog {

    const val MAX_RECORDS = 500

    /** How often the snapshot may be rebuilt while a screen is watching. */
    private const val MIN_PUBLISH_INTERVAL_MS = 500L

    @Volatile private var lastPublishedMs = 0L

    private val lock = Any()

    /**
     * Access-ordered, so a repeat sighting moves to the front and the eldest entry is evicted
     * automatically. Keyed by package and domain together: the same tracker in two apps is two
     * facts, and per-app rules are written against exactly that pair.
     */
    private val records = object : LinkedHashMap<String, QueryRecord>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QueryRecord>): Boolean =
            size > MAX_RECORDS
    }

    @Volatile var blocked: Long = 0
        private set

    @Volatile var total: Long = 0
        private set

    @Volatile var sinceMs: Long = System.currentTimeMillis()
        private set

    private val _state = MutableStateFlow(QueryLogState(sinceMs = sinceMs))
    val state: StateFlow<QueryLogState> = _state.asStateFlow()

    /** When false only the counters move; nothing is written down about individual lookups. */
    @Volatile var recording: Boolean = true

    /**
     * Notes one decided lookup. Called from the tunnel's read loop, so it stays cheap and never
     * throws: a failure to log must not become a failure to resolve.
     */
    fun record(
        domain: String,
        packageName: String?,
        verdict: Verdict,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            total++
            if (verdict.blocked) blocked++
            if (recording) {
                val key = key(packageName, domain)
                val existing = records[key]
                records[key] = QueryRecord(
                    domain = domain,
                    packageName = packageName,
                    // The newest verdict wins: a domain just allowed must stop reading as
                    // blocked, while its history of sightings is kept.
                    blocked = verdict.blocked,
                    source = verdict.source,
                    detail = verdict.detail,
                    count = (existing?.count ?: 0) + 1,
                    lastSeenMs = nowMs,
                )
            }
        }
        // The one branch that matters for battery: with no screen open there is no subscriber,
        // so a lookup never builds a snapshot and never wakes a collector.
        //
        // While somebody *is* watching, the floor matters just as much for a different reason:
        // publishing per lookup recomposes the whole screen per lookup, and a burst of DNS —
        // which is exactly what happens when an app launches — turns that into hundreds of
        // recompositions a second on the main thread. The screen does not need to be more
        // current than the eye.
        if (_state.subscriptionCount.value == 0) return
        val now = nowMs
        if (now - lastPublishedMs < MIN_PUBLISH_INTERVAL_MS) return
        lastPublishedMs = now
        publish()
    }

    /** Rebuilds the immutable snapshot the UI reads. Call on subscribe; otherwise it is skipped. */
    fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot(): QueryLogState = synchronized(lock) {
        QueryLogState(
            // Access order is oldest-first, and the UI wants newest-first.
            records = records.values.reversed(),
            blocked = blocked,
            total = total,
            sinceMs = sinceMs,
        )
    }

    /** Forgets the sightings but keeps counting; the "clear" button on the log screen. */
    fun clearRecords() {
        synchronized(lock) { records.clear() }
        publish()
    }

    /** Forgets everything, counters included. Used when the filter starts a new session. */
    fun reset(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            records.clear()
            blocked = 0
            total = 0
            sinceMs = nowMs
        }
        publish()
    }

    private fun key(packageName: String?, domain: String) = "${packageName.orEmpty()}|$domain"
}
