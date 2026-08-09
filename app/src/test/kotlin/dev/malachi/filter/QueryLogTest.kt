package dev.malachi.filter

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
    fun `the cap evicts the least recently seen`() {
        repeat(QueryLog.MAX_RECORDS + 20) { i -> QueryLog.record("d$i.example.com", "com.a", allowed) }
        val records = snapshot().records
        assertEquals(QueryLog.MAX_RECORDS, records.size)
        assertTrue(records.none { it.domain == "d0.example.com" })
        assertEquals("d${QueryLog.MAX_RECORDS + 19}.example.com", records.first().domain)
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
}
