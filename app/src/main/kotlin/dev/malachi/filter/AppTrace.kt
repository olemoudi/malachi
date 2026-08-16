package dev.malachi.filter

import dev.malachi.filter.dns.DnsMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What became of one DNS query, or what the user did while watching. */
enum class TraceOutcome {
    /** Refused by a rule or a list. The app got its empty answer immediately. */
    BLOCKED,

    /** Forwarded, and a DNS server answered. */
    ANSWERED,

    /** Forwarded, and nothing came back before the budget ran out. */
    UNANSWERED,

    /** Never left the tunnel at all. See [TraceReason]. */
    DROPPED,

    /** A rule the user wrote while watching, so the timeline reads as an experiment. */
    RULE_ALLOWED,
    RULE_BLOCKED,
    RULE_REMOVED;

    /** True for the four that describe a lookup rather than an edit. */
    val isLookup: Boolean
        get() = this == BLOCKED || this == ANSWERED || this == UNANSWERED || this == DROPPED

    /**
     * True when the app was left waiting.
     *
     * The distinction this screen exists for: a *blocked* lookup is answered at once and never
     * hangs anything, while one of these is a client sitting on a socket until its own timeout.
     * An app that freezes for thirty seconds is far more often this than a block.
     */
    val stalled: Boolean get() = this == UNANSWERED || this == DROPPED
}

/** Why a query never left. Kept as a value so the screen writes the sentence in the user's language. */
enum class TraceReason { NONE, NETWORK_CHANGED, BUSY, MALFORMED }

/**
 * One line of the timeline.
 *
 * Per *query*, not per lookup — which is the whole difference from [QueryLog], where one
 * resolution is deliberately collapsed into one row. Here the `A` and the `AAAA` are two
 * separate exchanges with two separate fates, and "the `A` came back in 40 ms and the `AAAA`
 * never did" is precisely the kind of thing that hangs an app and that no aggregated view can
 * say. So [attempt] counts queries, [type] names which one, and neither pretends otherwise.
 */
data class TraceEvent(
    val atMs: Long,
    /** The domain, or — for the rule events — the domain the rule was written against. */
    val domain: String,
    val outcome: TraceOutcome,
    /** Data, never prose: the list that matched, or the DNS server that answered. */
    val detail: String = "",
    val source: RuleSource = RuleSource.NONE,
    val reason: TraceReason = TraceReason.NONE,
    /** The DNS record type, or 0 when it was never parsed. See [AppTrace.typeLabel]. */
    val type: Int = 0,
    /** How many queries for this name have been seen since watching started; 1 the first. */
    val attempt: Int = 1,
    /** How long the exchange took, or -1 when it never happened. */
    val elapsedMs: Long = -1,
)

/** A blocked domain, and how badly this app seems to want it. */
data class TraceSuspect(
    val domain: String,
    /** Queries since watching started, surviving the buffer's own eviction. */
    val queries: Int,
    val detail: String,
    val source: RuleSource,
    val lastAtMs: Long,
)

/**
 * What one app's DNS has been doing, as an immutable value for the UI.
 *
 * [packageName] is whose events these are, which is not the same question as whether anything is
 * still being [recording]ed: the window closes by itself and what it caught stays readable.
 */
data class AppTraceState(
    val packageName: String? = null,
    val recording: Boolean = false,
    val startedAtMs: Long = 0,
    /** Newest first, like every other list in this app. */
    val events: List<TraceEvent> = emptyList(),
    val blocked: Int = 0,
    val answered: Int = 0,
    val stalled: Int = 0,
) {
    /**
     * The blocked domains, most-asked-for first — the shortlist of things to try exempting.
     *
     * Ranked by count rather than by recency because a count is the one signal that separates a
     * tracker the app shrugs off from the name it is stuck retrying, and the second is what
     * breaks an app. The row still carries when it was last seen, for the other case.
     */
    fun suspects(limit: Int): List<TraceSuspect> =
        events.asSequence()
            .filter { it.outcome == TraceOutcome.BLOCKED }
            .groupBy { it.domain }
            .map { (domain, rows) ->
                TraceSuspect(
                    domain = domain,
                    // The newest event's attempt number, not the number of rows kept: the buffer
                    // evicts, and a domain asked for two hundred times must not report itself as
                    // having been asked for forty because that is all there was room for.
                    queries = rows.maxOf { it.attempt },
                    detail = rows.first().detail,
                    source = rows.first().source,
                    lastAtMs = rows.maxOf { it.atMs },
                )
            }
            .sortedWith(compareByDescending<TraceSuspect> { it.queries }.thenByDescending { it.lastAtMs })
            .take(limit)
            .toList()
}

/**
 * A per-query record of one app's DNS, for the case the aggregated log cannot solve: an app that
 * hangs, and nobody can tell which name it is hanging on.
 *
 * [QueryLog] answers "what has this app been resolving" and answers it well, but it answers it as
 * a set: one row per domain, most recent verdict, a count. Three things it cannot say, and all
 * three are what somebody debugging a frozen app needs —
 *
 * - **the order**, at the resolution the eye works at. "These four names were refused in the two
 *   seconds after I tapped Log in" is a different fact from four rows sorted by last-seen.
 * - **what happened to the lookups that were allowed.** Failing open means an allowed lookup can
 *   still leave the app waiting five seconds for a resolver that is not answering, and until this
 *   existed the app recorded that nowhere at all. It reads identically from the outside, and the
 *   fix is the opposite one.
 * - **what the user has already tried.** Exempting domains is trial and error by nature; a
 *   timeline with the edits written into it is an experiment log, and without them it is a wall.
 *
 * Everything here is subordinate to the same two rules the rest of the tunnel obeys. **Nothing
 * reaches disk** — this is memory that dies with the process, like the query log and for the same
 * reason. And **nothing is paid for when it is off**: the read loop's whole cost while no app is
 * being watched is one volatile read, and while one is, one string comparison more.
 */
object AppTrace {

    /**
     * How many events are kept. One app's DNS over a debugging session, at a few dozen queries a
     * minute — enough to hold the reproduction and the two attempts before it.
     */
    const val MAX_EVENTS = 400

    /**
     * How many distinct names the attempt counter will track. A client generating a fresh
     * hostname per request would otherwise turn a fifteen-minute diagnostic into a map that only
     * grows; past this the counter simply stops learning new names, which costs one number on a
     * row nobody is reading.
     */
    const val MAX_DOMAINS = 256

    /** How often the snapshot may be rebuilt while a screen is watching. */
    private const val MIN_PUBLISH_INTERVAL_NANOS = 400_000_000L

    private val lock = Any()

    /** Oldest first; the snapshot reverses it. */
    private val events = ArrayDeque<TraceEvent>(64)
    private val counts = HashMap<String, Int>()

    /** The app being recorded, or null. Read once per lookup, so it stays a volatile reference. */
    @Volatile private var watched: String? = null

    /** Whose events are in the buffer, which outlives [watched] by design. */
    @Volatile private var owner: String? = null

    @Volatile private var startedAtMs = 0L
    @Volatile private var blocked = 0
    @Volatile private var answered = 0
    @Volatile private var stalled = 0
    @Volatile private var lastPublishedNanos = 0L

    private val _state = MutableStateFlow(AppTraceState())
    val state: StateFlow<AppTraceState> = _state.asStateFlow()

    /**
     * True while [packageName] is the app being watched.
     *
     * This is the hot-path question and it is asked once per lookup for every app on the phone.
     * With nothing being watched it is a single volatile read.
     */
    fun watches(packageName: String?): Boolean {
        val target = watched ?: return false
        return packageName != null && packageName == target
    }

    /** True when the buffer belongs to [packageName], whether or not it is still recording. */
    fun owns(packageName: String?): Boolean = packageName != null && packageName == owner

    /**
     * Starts recording [packageName].
     *
     * Idempotent: the settings flow re-emits for every unrelated edit, and re-arming the same app
     * must not throw away the session that is being read. Choosing a *different* app does clear
     * it — two apps' lookups in one timeline is not a timeline of anything.
     */
    fun watch(packageName: String, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (watched == packageName) return
            watched = packageName
            if (owner != packageName) {
                owner = packageName
                startedAtMs = nowMs
                reset()
            }
        }
        publish()
    }

    /**
     * Stops recording and keeps what was recorded.
     *
     * The window closing is not a reason to lose the evidence: somebody who let it lapse while
     * reproducing a bug still needs to read what it caught, and re-arming continues the session.
     */
    fun stop() {
        synchronized(lock) {
            if (watched == null) return
            watched = null
        }
        publish()
    }

    /** Forgets the events, keeps watching. The screen's clear button — "try that again". */
    fun clear(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            startedAtMs = nowMs
            reset()
        }
        publish()
    }

    private fun reset() {
        events.clear()
        counts.clear()
        blocked = 0
        answered = 0
        stalled = 0
    }

    fun blocked(domain: String, type: Int, detail: String, source: RuleSource, nowMs: Long = System.currentTimeMillis()) {
        add(domain, TraceOutcome.BLOCKED, detail, source, TraceReason.NONE, type, -1, nowMs)
    }

    fun answered(domain: String, type: Int, resolver: String, elapsedMs: Long, nowMs: Long = System.currentTimeMillis()) {
        add(domain, TraceOutcome.ANSWERED, resolver, RuleSource.NONE, TraceReason.NONE, type, elapsedMs, nowMs)
    }

    fun unanswered(domain: String, type: Int, resolvers: String, elapsedMs: Long, nowMs: Long = System.currentTimeMillis()) {
        add(domain, TraceOutcome.UNANSWERED, resolvers, RuleSource.NONE, TraceReason.NONE, type, elapsedMs, nowMs)
    }

    fun dropped(domain: String, type: Int, reason: TraceReason, nowMs: Long = System.currentTimeMillis()) {
        add(domain, TraceOutcome.DROPPED, "", RuleSource.NONE, reason, type, -1, nowMs)
    }

    /**
     * Notes a rule the user just wrote or removed for the app being watched.
     *
     * Recorded whenever the buffer belongs to that app, including after the window has closed —
     * somebody reading the evidence and acting on it is exactly the moment this is worth having,
     * and it costs a row.
     */
    fun rule(domain: String, outcome: TraceOutcome, nowMs: Long = System.currentTimeMillis()) {
        if (owner == null) return
        synchronized(lock) {
            append(TraceEvent(atMs = nowMs, domain = domain, outcome = outcome))
        }
        publish()
    }

    private fun add(
        domain: String,
        outcome: TraceOutcome,
        detail: String,
        source: RuleSource,
        reason: TraceReason,
        type: Int,
        elapsedMs: Long,
        nowMs: Long,
    ) {
        synchronized(lock) {
            // Re-checked under the lock: the outcome of a forwarded lookup arrives on another
            // thread, long after the decision to record it was taken, and by then the user may
            // have moved on to a different app.
            if (watched == null) return
            append(
                TraceEvent(
                    atMs = nowMs,
                    domain = domain,
                    outcome = outcome,
                    detail = detail,
                    source = source,
                    reason = reason,
                    type = type,
                    attempt = bump(domain),
                    elapsedMs = elapsedMs,
                ),
            )
            when {
                outcome == TraceOutcome.BLOCKED -> blocked++
                outcome == TraceOutcome.ANSWERED -> answered++
                outcome.stalled -> stalled++
            }
        }
        // Same bargain as the query log: with no screen open a lookup never builds a snapshot,
        // and with one open the screen is not made more current than the eye.
        if (_state.subscriptionCount.value == 0) return
        val now = System.nanoTime()
        if (now - lastPublishedNanos < MIN_PUBLISH_INTERVAL_NANOS) return
        lastPublishedNanos = now
        publish()
    }

    private fun append(event: TraceEvent) {
        events.addLast(event)
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    private fun bump(domain: String): Int {
        val next = (counts[domain] ?: 0) + 1
        if (next > 1 || counts.size < MAX_DOMAINS) counts[domain] = next
        return next
    }

    /** Rebuilds the immutable snapshot. Called on subscribe, since nothing is published idly. */
    fun publish() {
        _state.value = synchronized(lock) {
            AppTraceState(
                packageName = owner,
                recording = watched != null,
                startedAtMs = startedAtMs,
                events = events.toList().asReversed(),
                blocked = blocked,
                answered = answered,
                stalled = stalled,
            )
        }
    }

    /**
     * The record type as a person reads it.
     *
     * Not localised, and deliberately: `AAAA` is the name of the thing in every language, and an
     * app hanging on an `AAAA` that never comes back while its `A` answered instantly is a real
     * and common shape that this label is the only way to see.
     */
    fun typeLabel(type: Int): String = when (type) {
        DnsMessage.TYPE_A -> "A"
        DnsMessage.TYPE_AAAA -> "AAAA"
        DnsMessage.TYPE_HTTPS -> "HTTPS"
        DnsMessage.TYPE_SVCB -> "SVCB"
        0 -> ""
        else -> "type $type"
    }
}
