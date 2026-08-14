package dev.malachi.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every list with an opinion about a domain, not just the one that answered.
 *
 * `decide` names a single cause, correctly — a verdict has one — but somebody deciding whether
 * to write an exception is asking a different question. Four maintainers independently calling a
 * name a tracker is a fact about the name; one list flagging it is a judgement, and possibly a
 * mistake.
 */
class ListCoverageTest {

    private val ads = CompiledList("ads", "Ad list", DomainIndex.of(listOf("tracker.example.com")))
    private val privacy = CompiledList("privacy", "Privacy list", DomainIndex.of(listOf("tracker.example.com")))
    private val oisd = CompiledList(
        id = "oisd",
        title = "OISD",
        block = DomainIndex.of(listOf("cdn.example.com")),
        allow = DomainIndex.of(listOf("tracker.example.com")),
    )

    private fun engine() = FilterEngine(lists = listOf(ads, privacy, oisd))

    @Test
    fun `every list carrying the domain is named`() {
        val coverage = engine().listsCovering("tracker.example.com")
        assertEquals(listOf("Ad list", "Privacy list"), coverage.blocking)
    }

    @Test
    fun `an exception is reported separately from a block`() {
        val coverage = engine().listsCovering("tracker.example.com")
        assertEquals(listOf("OISD"), coverage.allowing)
    }

    @Test
    fun `a subdomain is covered by its parent's listing`() {
        // Matching is by suffix everywhere else in this engine and must be here too, or the
        // dialog would report "on no list" for a name it had just blocked.
        assertEquals(listOf("Ad list", "Privacy list"), engine().listsCovering("eu.tracker.example.com").blocking)
    }

    @Test
    fun `a domain no list carries is covered by nothing`() {
        val coverage = engine().listsCovering("example.org")
        assertTrue(coverage.blocking.isEmpty())
        assertTrue(coverage.allowing.isEmpty())
    }

    @Test
    fun `something that is not a domain at all is covered by nothing`() {
        // A single label can never be a rule, so it can never be on a list either.
        assertTrue(engine().listsCovering("localhost").blocking.isEmpty())
        assertTrue(engine().listsCovering("").blocking.isEmpty())
    }
}
