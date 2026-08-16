package dev.malachi.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppTraceTest {

    private val game = "com.example.game"
    private val bank = "com.example.bank"

    @BeforeEach
    fun clean() {
        AppTrace.watch(game, nowMs = 0)
        AppTrace.clear(nowMs = 0)
    }

    private fun snapshot(): AppTraceState {
        AppTrace.publish()
        return AppTrace.state.value
    }

    @Test
    fun `nothing is recorded while no app is being watched`() {
        AppTrace.stop()
        AppTrace.blocked("ads.example.com", 1, "a list", RuleSource.LIST, nowMs = 1)
        assertTrue(snapshot().events.isEmpty())
    }

    @Test
    fun `stopping keeps the evidence and only stops the recording`() {
        // The window closing must not take the session with it: somebody who let it lapse while
        // reproducing a bug still has to be able to read what it caught.
        AppTrace.blocked("ads.example.com", 1, "a list", RuleSource.LIST, nowMs = 1)
        AppTrace.stop()
        val state = snapshot()
        assertEquals(1, state.events.size)
        assertEquals(game, state.packageName)
        assertFalse(state.recording)
    }

    @Test
    fun `re-arming the same app continues the session`() {
        AppTrace.blocked("ads.example.com", 1, "a list", RuleSource.LIST, nowMs = 1)
        AppTrace.stop()
        AppTrace.watch(game, nowMs = 5)
        AppTrace.blocked("more.example.com", 1, "a list", RuleSource.LIST, nowMs = 6)
        assertEquals(2, snapshot().events.size)
    }

    @Test
    fun `watching a different app starts from nothing`() {
        // Two apps' lookups in one timeline is a timeline of nothing.
        AppTrace.blocked("ads.example.com", 1, "a list", RuleSource.LIST, nowMs = 1)
        AppTrace.watch(bank, nowMs = 2)
        val state = snapshot()
        assertTrue(state.events.isEmpty())
        assertEquals(bank, state.packageName)
    }

    @Test
    fun `the timeline is newest first`() {
        AppTrace.blocked("one.example.com", 1, "a list", RuleSource.LIST, nowMs = 1)
        AppTrace.blocked("two.example.com", 1, "a list", RuleSource.LIST, nowMs = 2)
        assertEquals(listOf("two.example.com", "one.example.com"), snapshot().events.map { it.domain })
    }

    @Test
    fun `every query counts, because this view is per query and says so`() {
        // Deliberately unlike the query log, where an A and an AAAA are one lookup. Here the two
        // are separate exchanges with separate fates, and "the A answered and the AAAA never
        // did" is exactly the shape this screen exists to show.
        AppTrace.blocked("ads.example.com", 1, "a list", RuleSource.LIST, nowMs = 1)
        AppTrace.blocked("ads.example.com", 28, "a list", RuleSource.LIST, nowMs = 2)
        assertEquals(listOf(2, 1), snapshot().events.map { it.attempt })
    }

    @Test
    fun `the counters survive the buffer's own eviction`() {
        repeat(AppTrace.MAX_EVENTS + 50) { i ->
            AppTrace.blocked("ads.example.com", 1, "a list", RuleSource.LIST, nowMs = i.toLong())
        }
        val state = snapshot()
        assertEquals(AppTrace.MAX_EVENTS, state.events.size, "the buffer is bounded")
        assertEquals(AppTrace.MAX_EVENTS + 50, state.blocked, "the tally is not")
        // And the shortlist reports what was really asked, not what there was room to keep.
        assertEquals(AppTrace.MAX_EVENTS + 50, state.suspects(5).single().queries)
    }

    @Test
    fun `the shortlist ranks by how badly the app wants it`() {
        repeat(3) { AppTrace.blocked("rare.example.com", 1, "a list", RuleSource.LIST, nowMs = it.toLong()) }
        repeat(9) { AppTrace.blocked("hammered.example.com", 1, "a list", RuleSource.LIST, nowMs = it.toLong()) }
        AppTrace.answered("fine.example.com", 1, "1.1.1.1", 12, nowMs = 20)
        val suspects = snapshot().suspects(5)
        assertEquals(listOf("hammered.example.com", "rare.example.com"), suspects.map { it.domain })
        assertEquals(9, suspects.first().queries)
    }

    @Test
    fun `a lookup nobody answered is counted apart from a blocked one`() {
        // The distinction the whole screen turns on: a block is answered at once and hangs
        // nothing, while this is an app left waiting on a socket. Confusing the two sends
        // somebody hunting for a domain to except that does not exist.
        AppTrace.blocked("ads.example.com", 1, "a list", RuleSource.LIST, nowMs = 1)
        AppTrace.unanswered("api.example.com", 1, "192.0.2.1", 5_000, nowMs = 2)
        AppTrace.dropped("api.example.com", 28, TraceReason.NETWORK_CHANGED, nowMs = 3)
        AppTrace.answered("cdn.example.com", 1, "1.1.1.1", 40, nowMs = 4)
        val state = snapshot()
        assertEquals(1, state.blocked)
        assertEquals(1, state.answered)
        assertEquals(2, state.stalled)
        // And neither of the stalled ones is offered as something to except.
        assertEquals(listOf("ads.example.com"), state.suspects(5).map { it.domain })
    }

    @Test
    fun `an edit is written into the timeline as an edit`() {
        AppTrace.blocked("ads.example.com", 1, "a list", RuleSource.LIST, nowMs = 1)
        AppTrace.rule("ads.example.com", TraceOutcome.RULE_ALLOWED, nowMs = 2)
        val event = snapshot().events.first()
        assertEquals(TraceOutcome.RULE_ALLOWED, event.outcome)
        assertFalse(event.outcome.isLookup)
        // An edit is not a query and must not inflate the count of one.
        assertEquals(1, snapshot().suspects(5).single().queries)
    }

    @Test
    fun `an edit still lands after the window has closed`() {
        // Reading the evidence and acting on it is exactly when this is worth having.
        AppTrace.blocked("ads.example.com", 1, "a list", RuleSource.LIST, nowMs = 1)
        AppTrace.stop()
        AppTrace.rule("ads.example.com", TraceOutcome.RULE_ALLOWED, nowMs = 2)
        assertEquals(TraceOutcome.RULE_ALLOWED, snapshot().events.first().outcome)
    }

    @Test
    fun `only the watched app is claimed`() {
        assertTrue(AppTrace.watches(game))
        assertFalse(AppTrace.watches(bank))
        assertFalse(AppTrace.watches(null))
        AppTrace.stop()
        assertFalse(AppTrace.watches(game), "a closed window watches nothing")
        assertTrue(AppTrace.owns(game), "but the buffer still belongs to it")
    }

    @Test
    fun `a name-generating client cannot make the counter grow forever`() {
        repeat(AppTrace.MAX_DOMAINS * 2) { i ->
            AppTrace.blocked("host$i.example.com", 1, "a list", RuleSource.LIST, nowMs = i.toLong())
        }
        // Bounded, and the cost of the bound is one number on a row: past the ceiling a new name
        // simply reports its first sighting.
        assertEquals(1, snapshot().events.first().attempt)
    }

    @Test
    fun `record types are named the way they are named everywhere else`() {
        assertEquals("A", AppTrace.typeLabel(1))
        assertEquals("AAAA", AppTrace.typeLabel(28))
        assertEquals("HTTPS", AppTrace.typeLabel(65))
        assertEquals("", AppTrace.typeLabel(0))
    }

    @Test
    fun `an empty state names nobody`() {
        assertNull(AppTraceState().packageName)
        assertTrue(AppTraceState().suspects(5).isEmpty())
    }
}
