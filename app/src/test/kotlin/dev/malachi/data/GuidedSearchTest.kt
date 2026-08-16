package dev.malachi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json

/**
 * The guided search: allow everything, then put the refusals back one at a time until it breaks.
 *
 * Every case here is a conversation with somebody who is not looking at a domain — "did it work?",
 * yes or no — which is why the whole method is a pure function over stored state. None of it needs
 * a device, an app to break, or a person to ask.
 */
class GuidedSearchTest {

    private val app = "com.example.game"
    private val three = listOf("ads.example.com", "sdk.example.com", "cdn.example.com")

    private fun captured(refused: List<String> = three) =
        GuidedSearch(packageName = app).captured(refused, limit = GuidedSearch.MAX_CANDIDATES)

    @Test
    fun `a fresh search asks for a failure before anything else`() {
        val guide = GuidedSearch(packageName = app)
        assertEquals(GuideStep.CAPTURE, guide.step)
        // And exempts nothing: the capture has to see the app exactly as it normally is, or the
        // list of refusals it produces is a list of what happens when nothing is refused.
        assertTrue(guide.exemptions().isEmpty())
    }

    @Test
    fun `a capture that refused nothing says so rather than searching an empty list`() {
        val guide = captured(refused = emptyList())
        assertEquals(GuideStep.NOTHING_REFUSED, guide.step)
    }

    @Test
    fun `the baseline allows everything the capture found`() {
        val guide = captured()
        assertEquals(GuideStep.BASELINE, guide.step)
        assertEquals(three.toSet(), guide.exemptions())
    }

    @Test
    fun `an app that still fails with nothing refused rules Malachi out`() {
        // The cheapest round there is, and the one that saves somebody nine restarts chasing a
        // cause that was never here.
        val guide = captured().answered(worked = false)
        assertEquals(GuideStep.RULED_OUT, guide.step)
        assertTrue(guide.exemptions().isEmpty(), "and it puts nothing of its own in the way")
    }

    @Test
    fun `each round refuses exactly one name and allows the rest`() {
        val guide = captured().answered(worked = true)
        assertEquals(GuideStep.TESTING, guide.step)
        assertEquals("ads.example.com", guide.testing)
        assertEquals(setOf("sdk.example.com", "cdn.example.com"), guide.exemptions())
        assertEquals(1, guide.round)
    }

    @Test
    fun `a round that works clears that name and moves to the next`() {
        val guide = captured().answered(true).answered(true)
        assertEquals("sdk.example.com", guide.testing)
        assertEquals(setOf("ads.example.com", "cdn.example.com"), guide.exemptions())
        assertEquals(2, guide.round)
    }

    @Test
    fun `a round that fails names the one thing that was refused`() {
        // The whole method in one assertion: it broke, and only this was being refused.
        val guide = captured().answered(true).answered(true).answered(worked = false)
        assertEquals(GuideStep.CULPRIT, guide.step)
        assertEquals("sdk.example.com", guide.culprit)
    }

    @Test
    fun `the last name is tested too, and clearing it ends the search`() {
        var guide = captured().answered(true)
        repeat(three.size) { guide = guide.answered(worked = true) }
        assertEquals(GuideStep.EXHAUSTED, guide.step)
        // An app that needs two of them at once lands here rather than being handed a confident
        // wrong answer, which is the whole reason this tests one at a time instead of by halves.
        assertTrue(guide.culprit.isEmpty())
    }

    @Test
    fun `a single candidate is a search of one round`() {
        val guide = captured(refused = listOf("only.example.com")).answered(true)
        assertEquals(GuideStep.TESTING, guide.step)
        assertEquals("only.example.com", guide.testing)
        assertTrue(guide.exemptions().isEmpty(), "with nothing else to allow")
        assertEquals("only.example.com", guide.answered(worked = false).culprit)
    }

    @Test
    fun `starting again keeps the shortlist and forgets the answers`() {
        // For the one thing this method cannot control: Android holding an address in memory, so
        // that a round says "it worked" about a name that is not innocent at all.
        val guide = captured().answered(true).answered(false).restarted()
        assertEquals(GuideStep.BASELINE, guide.step)
        assertTrue(guide.culprit.isEmpty())
        assertEquals(three, guide.candidates)
    }

    @Test
    fun `the cap is never silent`() {
        val many = (1..25).map { "host$it.example.com" }
        val guide = GuidedSearch(packageName = app).captured(many, limit = 10)
        assertEquals(10, guide.candidates.size)
        assertEquals(25, guide.found)
        assertTrue(guide.truncated)
        assertFalse(captured().truncated, "and it does not cry wolf when it tested everything")
    }

    @Test
    fun `a name seen twice is one candidate, not two rounds`() {
        val guide = GuidedSearch(packageName = app)
            .captured(listOf("a.example.com", "a.example.com", "b.example.com"), limit = 10)
        assertEquals(listOf("a.example.com", "b.example.com"), guide.candidates)
        assertEquals(2, guide.found)
    }

    // ---- the rules it writes ---------------------------------------------------------------

    @Test
    fun `a step's rules are exactly its exemptions, with none of the last step's left behind`() {
        val guide = captured().answered(true)
        val rules = guide.applied(emptyList())
        assertEquals(setOf("sdk.example.com", "cdn.example.com"), rules.map { it.domain }.toSet())
        assertTrue(rules.all { it.packageName == app && !it.block })

        // Moving on replaces them rather than accumulating: the previous round's exemption for
        // the name now under test would make the round meaningless.
        val next = guide.answered(true).applied(rules)
        assertEquals(setOf("ads.example.com", "cdn.example.com"), next.map { it.domain }.toSet())
    }

    @Test
    fun `leaving puts back exactly what was there`() {
        // Rules belonging to other apps, and to other domains, are untouched — the search only
        // ever manages the names it captured, in the app it captured them from.
        val mine = AppRule("ads.example.com", app, block = false)
        val someoneElses = listOf(
            AppRule("bank.example.com", app, block = true),
            AppRule("ads.example.com", "com.other.app", block = false),
        )
        val guide = captured().answered(true)
        assertEquals(someoneElses, guide.cleared(someoneElses + mine))
    }

    @Test
    fun `it survives being written down and read back`() {
        // It has to: the search asks the user to leave Malachi and force-stop another app, and on
        // a phone short of memory this process is not guaranteed to be the one that comes back.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val guide = captured().answered(true).answered(true)
        val settings = MalachiSettings(guide = guide)
        val text = json.encodeToString(MalachiSettings.serializer(), settings)
        val back = json.decodeFromString(MalachiSettings.serializer(), text)
        assertEquals(guide, back.guide)
        assertEquals(GuideStep.TESTING, back.guide?.step)
    }

    @Test
    fun `an install that has never run one reads a file that has no search in it`() {
        val old = """{"filteringEnabled":true,"userBlocked":["ads.example.com"]}"""
        val settings = Json { ignoreUnknownKeys = true }
            .decodeFromString(MalachiSettings.serializer(), old)
        assertEquals(null, settings.guide)
    }

    @Test
    fun `a search in progress is not part of the tunnel's shape`() {
        // Every round writes rules, and a rebuild per round would be a blink of unfiltered DNS
        // nine times over — while the user is in the middle of deciding whether the app works.
        val base = MalachiSettings(filteringEnabled = true)
        assertEquals(base.tunnelShape(), base.copy(guide = captured()).tunnelShape())
    }
}
