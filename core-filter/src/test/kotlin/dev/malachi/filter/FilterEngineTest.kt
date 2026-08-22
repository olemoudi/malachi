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

    // ---- the phone's own connectivity checks -------------------------------------------

    @Test
    fun `no downloaded list can take away the phone's connectivity check`() {
        // The failure this prevents is silent and expensive: Android decides a Wi-Fi is dead by
        // fetching a 204 over it, so a list that refuses the probe's name makes a working Wi-Fi
        // read as "no internet" — and every phone with an "adaptive connectivity" feature then
        // leaves it for the mobile network. Reported as "the Wi-Fi doesn't work with the filter
        // on", which is exactly what it looks like from the outside.
        val hostile = CompiledList(
            id = "hostile",
            title = "Blocks everything",
            block = DomainIndex.of(
                listOf("gstatic.com", "google.com", "miui.com", "hicloud.com", "vivo.com.cn", "android.com"),
            ),
        )
        val filter = engine(lists = listOf(hostile))
        FilterEngine.CONNECTIVITY_CHECKS.forEach { probe ->
            assertFalse(filter.decide(probe, null).blocked, probe)
        }
        // And nothing else about those domains is rescued: the exemption is the probe, not the
        // company that happens to serve it.
        assertTrue(filter.decide("ads.gstatic.com", null).blocked)
        assertTrue(filter.decide("analytics.google.com", null).blocked)
        assertTrue(filter.decide("tracking.miui.com", null).blocked)
    }

    @Test
    fun `a rule the user wrote themselves still outranks the connectivity check`() {
        // Authorship comes first everywhere in this engine, and somebody who deliberately blocks
        // a probe has said what they want. What is refused is a list doing it on their behalf.
        val filter = engine(userBlock = listOf("connectivitycheck.gstatic.com"))
        assertTrue(filter.decide("connectivitycheck.gstatic.com", null).blocked)
    }

    @Test
    fun `a per-app rule can still refuse a probe for one app`() {
        val rules = listOf(AppDomainRule("connectivitycheck.gstatic.com", "com.some.app", block = true))
        val filter = engine(appRules = rules)
        assertTrue(filter.decide("connectivitycheck.gstatic.com", "com.some.app").blocked)
        assertFalse(filter.decide("connectivitycheck.gstatic.com", "com.other.app").blocked)
    }

    @Test
    fun `the protected set stays small and is only ever probe endpoints`() {
        // A guard against this becoming a general-purpose exception list, which is what it would
        // silently turn into: every entry has to be a name whose loss makes the phone declare a
        // working network dead, and there are not many of those.
        assertTrue(FilterEngine.CONNECTIVITY_CHECKS.size <= 12, "the protected set is growing")
        assertTrue(FilterEngine.CONNECTIVITY_CHECKS.all { DomainIndex.normalizeHost(it) != null })
        // Never a bare registrable domain: allowing `google.com` would exempt every tracker
        // under it, which is the opposite of what this app is for.
        assertTrue(
            FilterEngine.CONNECTIVITY_CHECKS.all { it.count { c -> c == '.' } >= 2 },
            "a protected name has to be a host, not a whole domain",
        )
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
