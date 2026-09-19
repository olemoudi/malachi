package dev.malachi.filter

import dev.malachi.data.AppRule
import dev.malachi.data.MalachiSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DomainCheckTest {

    private val lists = listOf(
        CompiledList("a", "List A", DomainIndex.of(listOf("ads.example.com", "gstatic.com", "tracker.net"))),
        CompiledList("b", "List B", DomainIndex.of(listOf("ads.example.com")), allow = DomainIndex.of(listOf("ok.tracker.net"))),
    )

    private fun check(raw: String, settings: MalachiSettings = MalachiSettings()) = DomainChecker.check(
        raw,
        FilterEngine(
            userBlock = DomainIndex.of(settings.userBlocked),
            userAllow = DomainIndex.of(settings.userAllowed),
            appRules = settings.appRules.map { it.toDomainRule() },
            lists = lists,
        ),
        settings,
    )

    @Test
    fun `a listed name names every list that carries it`() {
        val result = check("https://eu.ads.example.com/tag?x=1")!!
        assertEquals("eu.ads.example.com", result.domain)
        assertTrue(result.verdict.blocked)
        assertEquals(listOf("List A", "List B"), result.coverage.blocking)
        assertNull(result.userRule)
    }

    @Test
    fun `the user's rule is named as written, parent and all`() {
        val settings = MalachiSettings(userAllowed = setOf("example.com", "cdn.example.com"))
        val result = check("img.cdn.example.com", settings)!!
        assertFalse(result.verdict.blocked)
        assertEquals("cdn.example.com", result.userRule)
    }

    @Test
    fun `an exception another list publishes is visible as one`() {
        val result = check("ok.tracker.net")!!
        assertFalse(result.verdict.blocked)
        assertEquals(RuleSource.LIST, result.verdict.source)
        assertEquals(listOf("List B"), result.coverage.allowing)
        assertEquals(listOf("List A"), result.coverage.blocking)
    }

    @Test
    fun `a connectivity probe is explained as one, not as nothing matching`() {
        val result = check("connectivitycheck.gstatic.com")!!
        assertFalse(result.verdict.blocked)
        assertTrue(result.connectivityCheck)
        // Unless the user blocked it themselves, in which case that is the reason.
        val blocked = check("connectivitycheck.gstatic.com", MalachiSettings(userBlocked = setOf("gstatic.com")))!!
        assertTrue(blocked.verdict.blocked)
        assertFalse(blocked.connectivityCheck)
    }

    @Test
    fun `per-app rules that reach the name are listed with it`() {
        val settings = MalachiSettings(
            appRules = listOf(
                AppRule("example.com", "com.news", block = true),
                AppRule("other.com", "com.news", block = true),
                AppRule("ads.example.com", "com.game", block = false),
            ),
        )
        val result = check("ads.example.com", settings)!!
        assertEquals(listOf("com.game", "com.news"), result.appRules.map { it.packageName })
    }

    @Test
    fun `text that holds no domain has no answer`() {
        assertNull(check("not a domain"))
        assertNull(check("com"))
    }
}
