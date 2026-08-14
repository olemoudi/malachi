package dev.malachi.filter

import dev.malachi.filter.dns.DnsMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class QueryLogTest {

    @BeforeEach
    fun clean() {
        QueryLog.recording = true
        QueryLog.reset(nowMs = 0)
    }

    private fun blocked(detail: String = "a list") =
        Verdict(blocked = true, source = RuleSource.LIST, detail = detail)

    private val allowed = Verdict.ALLOWED

    /** The log publishes nothing unless somebody is watching; a test is that somebody. */
    private fun snapshot(): QueryLogState {
        QueryLog.publish()
        return QueryLog.state.value
    }

    @Test
    fun `the same pair is counted, not repeated`() {
        // An app resolving one tracker forty times is one fact; forty lines of it would push
        // the domain you were looking for off the end of the list.
        repeat(3) { i -> QueryLog.record("ads.example.com", "com.example.game", blocked(), nowMs = i.toLong()) }
        val records = snapshot().records
        assertEquals(1, records.size)
        assertEquals(3, records.single().count)
        assertEquals(2L, records.single().lastSeenMs)
    }

    @Test
    fun `one resolution is one sighting, however many queries it took`() {
        // Reported from a phone: nearly every row said "seen 2 times" the moment it appeared.
        // One name resolved once is an A and an AAAA, and both used to be counted.
        QueryLog.record("ads.example.com", "com.example.game", blocked(), DnsMessage.TYPE_A, nowMs = 0)
        QueryLog.record("ads.example.com", "com.example.game", blocked(), DnsMessage.TYPE_AAAA, nowMs = 4)
        val record = snapshot().records.single()
        assertEquals(1, record.count)
        assertEquals(4L, record.lastSeenMs, "the companion still says when the name was last seen")
    }

    @Test
    fun `a retry is counted even inside the burst window`() {
        QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_A, nowMs = 0)
        QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_AAAA, nowMs = 1)
        QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_A, nowMs = 300)
        assertEquals(2, snapshot().records.single().count)
    }

    @Test
    fun `the statistics are told once per lookup, not once per query`() {
        // The return value is what the tunnel gates StatsStore.record on, so the persisted
        // counters and the live log cannot disagree about the same traffic by a factor of two.
        assertTrue(QueryLog.record("a.example.com", "com.a", allowed, DnsMessage.TYPE_A, nowMs = 0))
        assertFalse(QueryLog.record("a.example.com", "com.a", allowed, DnsMessage.TYPE_AAAA, nowMs = 1))
        assertEquals(1, snapshot().total)
    }

    @Test
    fun `counters collapse the burst with recording switched off too`() {
        // With no records to compare against, the burst window is the only thing that knows.
        QueryLog.recording = false
        QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_A, nowMs = 0)
        QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_AAAA, nowMs = 2)
        assertEquals(1, snapshot().total)
        assertEquals(1, snapshot().blocked)
    }

    @Test
    fun `a first sighting is one lookup even mid-burst`() {
        // Switching the log on between the two halves of a burst leaves a record with no history
        // and a burst that says "already counted". The row must still say it was seen once.
        QueryLog.recording = false
        QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_A, nowMs = 0)
        QueryLog.recording = true
        QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_AAAA, nowMs = 1)
        assertEquals(1, snapshot().records.single().count)
    }

    // ---- how hard a domain is being asked for ------------------------------------------

    @Test
    fun `a record remembers when it was first seen`() {
        QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_A, nowMs = 1_000)
        QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_A, nowMs = 5_000)
        val record = snapshot().records.single()
        assertEquals(1_000L, record.firstSeenMs)
        assertEquals(4_000L, record.spanMs)
    }

    @Test
    fun `a burst of lookups in a short span reads as retrying`() {
        repeat(QueryRecord.RETRY_COUNT) { i ->
            QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_A, nowMs = i * 1_000L)
        }
        assertTrue(snapshot().records.single().retrying)
    }

    @Test
    fun `the same count spread over hours does not`() {
        // The whole point of keeping the span: twelve lookups over an afternoon is an app doing
        // its job, and the screen used to draw it identically to twelve in half a minute.
        repeat(QueryRecord.RETRY_COUNT) { i ->
            QueryLog.record("cdn.example.com", "com.a", allowed, DnsMessage.TYPE_A, nowMs = i * 600_000L)
        }
        assertFalse(snapshot().records.single().retrying)
    }

    @Test
    fun `one sighting is never a retry`() {
        QueryLog.record("ads.example.com", "com.a", blocked(), DnsMessage.TYPE_A, nowMs = 1_000)
        val record = snapshot().records.single()
        assertEquals(0L, record.spanMs)
        assertFalse(record.retrying)
    }

    @Test
    fun `the blocked ranking sums a domain across the apps asking for it`() {
        // The one ranking the persisted statistics can never produce: they hold counts per app
        // and no domain at all, on purpose.
        repeat(3) { i -> QueryLog.record("tracker.example.com", "com.a", blocked(), DnsMessage.TYPE_A, nowMs = i * 1_000L) }
        repeat(2) { i -> QueryLog.record("tracker.example.com", "com.b", blocked(), DnsMessage.TYPE_A, nowMs = i * 1_000L) }
        repeat(4) { i -> QueryLog.record("other.example.com", "com.a", blocked(), DnsMessage.TYPE_A, nowMs = i * 1_000L) }
        QueryLog.record("fine.example.com", "com.a", allowed, DnsMessage.TYPE_A, nowMs = 0)

        val top = snapshot().topBlockedDomains(5)
        assertEquals(listOf("tracker.example.com" to 5, "other.example.com" to 4), top)
    }

    @Test
    fun `the blocked ranking ignores what was allowed`() {
        repeat(9) { i -> QueryLog.record("fine.example.com", "com.a", allowed, DnsMessage.TYPE_A, nowMs = i * 1_000L) }
        assertTrue(snapshot().topBlockedDomains(5).isEmpty())
    }

    @Test
    fun `the same domain in two apps stays two rows`() {
        QueryLog.record("ads.example.com", "com.a", blocked())
        QueryLog.record("ads.example.com", "com.b", blocked())
        assertEquals(2, snapshot().records.size)
    }

    @Test
    fun `an unattributed lookup is its own row`() {
        QueryLog.record("ads.example.com", null, blocked())
        QueryLog.record("ads.example.com", "com.a", blocked())
        assertEquals(2, snapshot().records.size)
        assertTrue(snapshot().byApp().any { it.first == null })
    }

    @Test
    fun `the newest verdict replaces the old one`() {
        // A domain the user has just allowed must stop reading as blocked, while the history of
        // how often it was seen is kept.
        QueryLog.record("ads.example.com", "com.a", blocked())
        QueryLog.record("ads.example.com", "com.a", allowed)
        val record = snapshot().records.single()
        assertFalse(record.blocked)
        assertEquals(2, record.count)
    }

    @Test
    fun `a repeat sighting moves back to the front`() {
        QueryLog.record("old.example.com", "com.a", allowed, nowMs = 1)
        QueryLog.record("new.example.com", "com.a", allowed, nowMs = 2)
        QueryLog.record("old.example.com", "com.a", allowed, nowMs = 3)
        assertEquals("old.example.com", snapshot().records.first().domain)
    }

    @Test
    fun `the total cap evicts the least recently seen, across every app`() {
        // Each app has a quota of its own now, so reaching the overall ceiling takes several of
        // them — one app on its own runs out of its own room first, which is the point.
        val apps = QueryLog.MAX_RECORDS / QueryLog.MAX_PER_APP + 10
        repeat(apps) { app ->
            repeat(QueryLog.MAX_PER_APP) { i -> QueryLog.record("d$i.example.com", "com.app$app", allowed) }
        }

        val records = snapshot().records
        assertEquals(QueryLog.MAX_RECORDS, records.size)
        assertTrue(records.none { it.packageName == "com.app0" }, "the oldest app should have gone first")
        assertEquals("com.app${apps - 1}", records.first().packageName)
    }

    @Test
    fun `counters keep running with recording switched off`() {
        // Somebody who doesn't want a list of their own DNS traffic may still want to know that
        // today cost them four hundred ad lookups.
        QueryLog.recording = false
        QueryLog.record("ads.example.com", "com.a", blocked())
        QueryLog.record("cdn.example.com", "com.a", allowed)
        val state = snapshot()
        assertTrue(state.records.isEmpty())
        assertEquals(2, state.total)
        assertEquals(1, state.blocked)
        assertEquals(50, state.blockedPercent)
    }

    @Test
    fun `counters are readable without building a snapshot`() {
        // The ongoing notification reads these on a timer and must not allocate to show a number.
        QueryLog.record("ads.example.com", "com.a", blocked())
        assertEquals(1, QueryLog.total)
        assertEquals(1, QueryLog.blocked)
    }

    @Test
    fun `clearing forgets the sightings but keeps counting`() {
        QueryLog.record("ads.example.com", "com.a", blocked())
        QueryLog.clearRecords()
        val state = snapshot()
        assertTrue(state.records.isEmpty())
        assertEquals(1, state.total)
    }

    @Test
    fun `resetting forgets everything`() {
        QueryLog.record("ads.example.com", "com.a", blocked())
        QueryLog.reset(nowMs = 99)
        val state = snapshot()
        assertTrue(state.records.isEmpty())
        assertEquals(0, state.total)
        assertEquals(0, state.blocked)
        assertEquals(99, state.sinceMs)
    }

    @Test
    fun `percentages are of what was actually seen`() {
        assertEquals(0, QueryLogState().blockedPercent)
        assertEquals(25, QueryLogState(blocked = 1, total = 4).blockedPercent)
        assertEquals(3, QueryLogState(blocked = 1, total = 4).allowed)
    }

    @Test
    fun `grouping by app puts the most recent app first`() {
        QueryLog.record("a.example.com", "com.old", allowed, nowMs = 10)
        QueryLog.record("c.example.com", "com.old", allowed, nowMs = 20)
        QueryLog.record("b.example.com", "com.new", allowed, nowMs = 90)
        val grouped = snapshot().byApp()
        assertEquals("com.new", grouped.first().first)
        val old = grouped.first { it.first == "com.old" }.second
        assertEquals(2, old.size)
        assertEquals("c.example.com", old.first().domain)
    }

    // ---- one app must not crowd out the others -----------------------------------------

    @Test
    fun `a chatty app runs out of its own room, not everybody else's`() {
        // With a single global limit this is what made the per-app screen empty: whatever talks
        // most evicts every other app's history, and the only way to see anything for a quiet
        // app was to go and use it.
        QueryLog.record("quiet.example.com", "com.quiet.app", Verdict(blocked = false))

        repeat(QueryLog.MAX_RECORDS * 2) { i ->
            QueryLog.record("ad$i.example.com", "com.noisy.app", Verdict(blocked = true))
        }

        val records = snapshot().records
        val quiet = records.filter { it.packageName == "com.quiet.app" }
        assertEquals(1, quiet.size, "the quiet app's only domain was evicted")
        assertEquals("quiet.example.com", quiet.single().domain)
        assertTrue(records.size <= QueryLog.MAX_RECORDS)
    }

    @Test
    fun `an app holds no more than its quota`() {
        repeat(QueryLog.MAX_PER_APP * 3) { i ->
            QueryLog.record("d$i.example.com", "com.noisy.app", Verdict(blocked = true))
        }
        val held = snapshot().records.count { it.packageName == "com.noisy.app" }
        assertEquals(QueryLog.MAX_PER_APP, held)
    }

    @Test
    fun `the quota evicts the least recently seen of that app`() {
        repeat(QueryLog.MAX_PER_APP) { i ->
            QueryLog.record("d$i.example.com", "com.app", Verdict(blocked = true))
        }
        // Touch the oldest so it is no longer the oldest, then overflow by one.
        QueryLog.record("d0.example.com", "com.app", Verdict(blocked = true))
        QueryLog.record("new.example.com", "com.app", Verdict(blocked = true))
        val domains = snapshot().records.filter { it.packageName == "com.app" }.map { it.domain }
        assertTrue("d0.example.com" in domains, "the touched domain was evicted anyway")
        assertTrue("new.example.com" in domains)
        assertTrue("d1.example.com" !in domains, "the least recently seen should have gone")
    }

    @Test
    fun `unattributed lookups have a quota of their own`() {
        // A lookup nobody could be attributed to is its own bucket rather than sharing one with
        // whatever app happens to be null-adjacent.
        repeat(QueryLog.MAX_PER_APP + 10) { i ->
            QueryLog.record("sys$i.example.com", null, Verdict(blocked = false))
        }
        QueryLog.record("mine.example.com", "com.app", Verdict(blocked = true))
        val records = snapshot().records
        assertEquals(QueryLog.MAX_PER_APP, records.count { it.packageName == null })
        assertEquals(1, records.count { it.packageName == "com.app" })
    }

    @Test
    fun `clearing forgets the per-app bookkeeping too`() {
        repeat(QueryLog.MAX_PER_APP) { i -> QueryLog.record("d$i.example.com", "com.app", Verdict(true)) }
        QueryLog.clearRecords()
        repeat(5) { i -> QueryLog.record("e$i.example.com", "com.app", Verdict(true)) }
        assertEquals(5, snapshot().records.count { it.packageName == "com.app" })
    }
}
