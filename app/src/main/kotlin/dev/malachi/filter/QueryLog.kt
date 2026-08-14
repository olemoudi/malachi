package dev.malachi.filter

import dev.malachi.filter.dns.DnsMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One (app, domain) pair the tunnel has seen, with the verdict it got most recently.
 *
 * [count] is **lookups**, not DNS queries: one resolution is an `A` and an `AAAA` and sometimes
 * an `HTTPS`, and counting those separately made every row claim to have been seen twice the
 * first time anybody looked at it. See [LookupBursts].
 */
data class QueryRecord(
    val domain: String,
    val packageName: String?,
    val blocked: Boolean,
    val source: RuleSource,
    val detail: String,
    val count: Int,
    val lastSeenMs: Long,
    /** When this pair was first seen, which is what turns [count] into a rate. */
    val firstSeenMs: Long = lastSeenMs,
) {
    /** How long the sightings span. Zero when there has only been one. */
    val spanMs: Long get() = (lastSeenMs - firstSeenMs).coerceAtLeast(0)

    /**
     * True when this reads as a client retrying rather than an app going about its business.
     *
     * A count on its own cannot tell the two apart — twelve lookups over six hours and twelve
     * over thirty seconds were displayed identically, and only one of them is worth waking up
     * for. This is the second number the screen never had, and it is the whole answer to "is
     * this app hammering something".
     *
     * Deliberately a plain threshold rather than a rate: a rate computed over a span of
     * milliseconds is a very large number that means nothing, and the question being asked is
     * only ever "is this normal or is it a loop".
     */
    val retrying: Boolean get() = count >= RETRY_COUNT && spanMs in 1..RETRY_WINDOW_MS

    companion object {
        /** Sightings within [RETRY_WINDOW_MS] that make a domain worth marking. */
        const val RETRY_COUNT = 10
        const val RETRY_WINDOW_MS = 60_000L
    }
}

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

    /**
     * The domains refused most often, summed across every app that asked for one.
     *
     * The statistics on disk can never answer this — they hold counts per app and no domain at
     * all, by design — so the only place the question "*what* is being blocked, not who by" can
     * be asked is here, in memory, for as long as the filter has been running. Which is also why
     * it is worth asking: it is the one ranking that names the tracker rather than its host.
     */
    fun topBlockedDomains(limit: Int): List<Pair<String, Int>> =
        records.asSequence()
            .filter { it.blocked }
            .groupingBy { it.domain }
            .fold(0) { total, record -> total + record.count }
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
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

    /**
     * The ceiling on what is held, in total. Every record is a domain and a few fields, so this
     * is the memory this app spends on being able to answer "what has that app been resolving".
     */
    const val MAX_RECORDS = 1200

    /**
     * And the ceiling per app, which is the half that matters.
     *
     * With one global limit, the app that talks most evicts every other app's history: open the
     * detail screen for anything quiet and it is empty, and the only way to fill it is to go and
     * use that app. A per-app quota means the noisy one runs out of its own room instead of
     * everybody else's.
     */
    const val MAX_PER_APP = 60

    /** How often the snapshot may be rebuilt while a screen is watching. */
    private const val MIN_PUBLISH_INTERVAL_NANOS = 500_000_000L

    @Volatile private var lastPublishedNanos = 0L

    private val lock = Any()

    /**
     * What turns two or three DNS queries back into the one lookup a person made. Lives outside
     * [recording] because the counters and the statistics need it just as much as the rows do.
     */
    private val bursts = LookupBursts()

    /**
     * Access-ordered, so a repeat sighting moves to the front and the eldest entry is evicted
     * automatically. Keyed by package and domain together: the same tracker in two apps is two
     * facts, and per-app rules are written against exactly that pair.
     */
    /** How many records each app currently holds, so the quota above can be enforced in O(1). */
    private val heldPerApp = HashMap<String, Int>()

    private val records = object : LinkedHashMap<String, QueryRecord>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QueryRecord>): Boolean {
            if (size <= MAX_RECORDS) return false
            forget(eldest.value)
            return true
        }
    }

    private fun forget(record: QueryRecord) {
        val owner = record.packageName.orEmpty()
        val left = (heldPerApp[owner] ?: 1) - 1
        if (left <= 0) heldPerApp.remove(owner) else heldPerApp[owner] = left
    }

    /**
     * Drops this app's least recently seen domain to make room for a new one.
     *
     * The scan is over the map in access order, so the first match is the right victim. It only
     * runs when an app is at its quota *and* has produced a domain it has never asked for
     * before, which on the hot path is rare: almost every lookup is a repeat, and a repeat is a
     * map put with no eviction at all.
     */
    private fun evictOldestFor(packageName: String?) {
        val owner = packageName.orEmpty()
        val victim = records.entries.firstOrNull { it.value.packageName.orEmpty() == owner } ?: return
        records.remove(victim.key)
        forget(victim.value)
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
     * Notes one decided query. Called from the tunnel's read loop, so it stays cheap and never
     * throws: a failure to log must not become a failure to resolve.
     *
     * Returns **true when this query began a new lookup** rather than continuing the one already
     * counted — the `AAAA` that follows an `A` is the same lookup and must not be counted twice,
     * here or in the statistics the caller keeps. See [LookupBursts].
     */
    fun record(
        domain: String,
        packageName: String?,
        verdict: Verdict,
        type: Int = DnsMessage.TYPE_A,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        var fresh: Boolean
        synchronized(lock) {
            fresh = bursts.beginsLookup(burstKey(packageName, domain), type, nowMs)
            if (fresh) {
                total++
                if (verdict.blocked) blocked++
            }
            if (recording) {
                val key = key(packageName, domain)
                val existing = records[key]
                if (existing == null) {
                    val owner = packageName.orEmpty()
                    if ((heldPerApp[owner] ?: 0) >= MAX_PER_APP) evictOldestFor(packageName)
                    heldPerApp[owner] = (heldPerApp[owner] ?: 0) + 1
                }
                records[key] = QueryRecord(
                    domain = domain,
                    packageName = packageName,
                    // The newest verdict wins: a domain just allowed must stop reading as
                    // blocked, while its history of sightings is kept.
                    blocked = verdict.blocked,
                    source = verdict.source,
                    detail = verdict.detail,
                    // A first sighting is one lookup whatever the burst says: the record can be
                    // absent while a burst is in flight, because the log was switched on between
                    // the two halves of it.
                    count = if (existing == null) 1 else existing.count + if (fresh) 1 else 0,
                    lastSeenMs = nowMs,
                    firstSeenMs = existing?.firstSeenMs ?: nowMs,
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
        if (_state.subscriptionCount.value == 0) return fresh
        // A monotonic clock, not the wall clock: an NTP correction that steps the wall clock
        // backwards would otherwise stop the screen updating until real time caught up.
        val now = System.nanoTime()
        if (now - lastPublishedNanos < MIN_PUBLISH_INTERVAL_NANOS) return fresh
        lastPublishedNanos = now
        publish()
        return fresh
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
        synchronized(lock) {
            records.clear()
            heldPerApp.clear()
        }
        publish()
    }

    /** Forgets everything, counters included. Used when the filter starts a new session. */
    fun reset(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            records.clear()
            heldPerApp.clear()
            bursts.clear()
            blocked = 0
            total = 0
            sinceMs = nowMs
        }
        publish()
    }

    private fun key(packageName: String?, domain: String) = "${packageName.orEmpty()}|$domain"

    /**
     * The same identity as [key], as an int and without building a string.
     *
     * [record] is reached once per query whether or not anything is being written down, and the
     * burst window needs the identity in both cases — so the path with the query log switched
     * off must not be made to allocate a key it has no other use for.
     */
    private fun burstKey(packageName: String?, domain: String): Int =
        31 * (packageName?.hashCode() ?: 0) + domain.hashCode()
}
