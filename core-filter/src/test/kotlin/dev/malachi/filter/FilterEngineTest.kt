package dev.malachi.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilterEngineTest {

    private val adsList = CompiledList(
        id = "ads",
        title = "Ad list",
        block = DomainIndex.of(listOf("ads.example.com", "tracker.net")),
    )

    private fun engine(
        userBlock: List<String> = emptyList(),
        userAllow: List<String> = emptyList(),
        appRules: List<AppDomainRule> = emptyList(),
        lists: List<CompiledList> = listOf(adsList),
    ) = FilterEngine(
        userBlock = DomainIndex.of(userBlock),
        userAllow = DomainIndex.of(userAllow),
        appRules = appRules,
        lists = lists,
    )

    @Test
    fun `a listed domain is blocked, and the list is named`() {
        val verdict = engine().decide("eu.ads.example.com", null)
        assertTrue(verdict.blocked)
        assertEquals(RuleSource.LIST, verdict.source)
        assertEquals("Ad list", verdict.detail)
    }

    @Test
    fun `an unlisted domain is allowed and cites nothing`() {
        val verdict = engine().decide("example.com", null)
        assertFalse(verdict.blocked)
        assertEquals(RuleSource.NONE, verdict.source)
    }

    @Test
    fun `a user allow rule overrides a list`() {
        val verdict = engine(userAllow = listOf("ads.example.com")).decide("ads.example.com", null)
        assertFalse(verdict.blocked)
        assertEquals(RuleSource.USER_RULE, verdict.source)
    }

    @Test
    fun `a user block rule blocks what no list mentions`() {
        val verdict = engine(userBlock = listOf("annoying.example")).decide("cdn.annoying.example", null)
        assertTrue(verdict.blocked)
        assertEquals(RuleSource.USER_RULE, verdict.source)
    }

    @Test
    fun `between two user rules the more specific domain wins, in either order of entry`() {
        val allowUnderBlock = engine(userBlock = listOf("example.com"), userAllow = listOf("cdn.example.com"))
        assertFalse(allowUnderBlock.decide("cdn.example.com", null).blocked)
        assertTrue(allowUnderBlock.decide("ads.example.com", null).blocked)

        val blockUnderAllow = engine(userBlock = listOf("ads.example.com"), userAllow = listOf("example.com"))
        assertTrue(blockUnderAllow.decide("ads.example.com", null).blocked)
        assertFalse(blockUnderAllow.decide("www.example.com", null).blocked)
    }

    @Test
    fun `an exact tie between a user block and a user allow resolves to allow`() {
        // A broken app is a worse failure than an ad that got through.
        val verdict = engine(userBlock = listOf("example.com"), userAllow = listOf("example.com"))
            .decide("example.com", null)
        assertFalse(verdict.blocked)
    }

    @Test
    fun `a list exception rescues a domain another list blocks`() {
        val exceptions = CompiledList(
            id = "oisd",
            title = "OISD",
            block = DomainIndex.EMPTY,
            allow = DomainIndex.of(listOf("ads.example.com")),
        )
        // The blocking list is scanned first: the exception must still win.
        val verdict = engine(lists = listOf(adsList, exceptions)).decide("ads.example.com", null)
        assertFalse(verdict.blocked)
        assertEquals(RuleSource.LIST, verdict.source)
    }

    @Test
    fun `a user block beats a list exception`() {
        val exceptions = CompiledList("oisd", "OISD", DomainIndex.EMPTY, DomainIndex.of(listOf("ads.example.com")))
        val verdict = engine(userBlock = listOf("ads.example.com"), lists = listOf(adsList, exceptions))
            .decide("ads.example.com", null)
        assertTrue(verdict.blocked)
        assertEquals(RuleSource.USER_RULE, verdict.source)
    }

    // --- per-app rules ---

    @Test
    fun `a per-app block applies only to that app`() {
        val rules = listOf(AppDomainRule("example.com", "com.some.app", block = true))
        val filter = engine(appRules = rules)
        assertTrue(filter.decide("www.example.com", "com.some.app").blocked)
        assertFalse(filter.decide("www.example.com", "com.other.app").blocked)
    }

    @Test
    fun `a per-app exemption lets one app reach a domain the lists block`() {
        val rules = listOf(AppDomainRule("ads.example.com", "com.some.app", block = false))
        val filter = engine(appRules = rules)
        val allowed = filter.decide("ads.example.com", "com.some.app")
        assertFalse(allowed.blocked)
        assertEquals(RuleSource.APP_RULE, allowed.source)
        assertTrue(filter.decide("ads.example.com", "com.other.app").blocked)
    }

    @Test
    fun `a per-app rule outranks the user's own global rules`() {
        val rules = listOf(AppDomainRule("example.com", "com.some.app", block = false))
        val filter = engine(userBlock = listOf("example.com"), appRules = rules)
        assertFalse(filter.decide("example.com", "com.some.app").blocked)
        assertTrue(filter.decide("example.com", "com.other.app").blocked)
    }

    @Test
    fun `an unattributed lookup cannot match a per-app rule`() {
        // Guessing which app asked would silently apply someone else's rule.
        val rules = listOf(AppDomainRule("example.com", "com.some.app", block = true))
        assertFalse(engine(appRules = rules).decide("example.com", null).blocked)
    }

    @Test
    fun `between two per-app rules the more specific one wins`() {
        val rules = listOf(
            AppDomainRule("example.com", "com.some.app", block = true),
            AppDomainRule("cdn.example.com", "com.some.app", block = false),
        )
        val filter = engine(appRules = rules)
        assertFalse(filter.decide("cdn.example.com", "com.some.app").blocked)
        assertTrue(filter.decide("ads.example.com", "com.some.app").blocked)
    }

    @Test
    fun `a name that is not a hostname is never blocked`() {
        assertFalse(engine(userBlock = listOf("example.com")).decide("", null).blocked)
    }

    @Test
    fun `match depth is by label`() {
        assertEquals(0, FilterEngine.matchDepth("example.com", "example.com"))
        assertEquals(1, FilterEngine.matchDepth("ads.example.com", "example.com"))
        assertEquals(2, FilterEngine.matchDepth("a.b.example.com", "example.com"))
        assertEquals(-1, FilterEngine.matchDepth("notexample.com", "example.com"))
        assertEquals(-1, FilterEngine.matchDepth("example.com", "ads.example.com"))
    }
}
