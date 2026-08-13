package dev.malachi.ui.screens

import dev.malachi.filter.AppDomainRule
import dev.malachi.filter.FilterEngine
import dev.malachi.filter.QueryRecord
import dev.malachi.filter.RuleSource
import dev.malachi.filter.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The app screen shows what the filter *would* do now, not only what it did when the lookup
 * happened — otherwise the line somebody just wrote a rule from goes on reporting the block they
 * came there to remove.
 */
class EffectiveVerdictTest {

    private val app = "com.bbva.bbvacontigo"

    private fun record(domain: String, blocked: Boolean, source: RuleSource, detail: String) =
        QueryRecord(
            domain = domain,
            packageName = app,
            blocked = blocked,
            source = source,
            detail = detail,
            count = 2,
            lastSeenMs = 0,
        )

    @Test
    fun `a rule written against a parent renames the verdict on the child's line`() {
        // The case this exists for: the domain was allowed, the user blocked the whole of
        // bbva.es from that row, and the row has to say so — naming the rule, since the name on
        // the row is not the name in the rule.
        val logged = record("movil.bbva.es", blocked = false, source = RuleSource.NONE, detail = "")
        val engine = FilterEngine(appRules = listOf(AppDomainRule("bbva.es", app, block = true)))

        val verdict = effectiveVerdict(logged, engine.decide(logged.domain, app))

        assertTrue(verdict.blocked)
        assertEquals(RuleSource.APP_RULE, verdict.source)
        assertEquals("bbva.es", verdict.detail)
    }

    @Test
    fun `an exemption written from a blocked row turns that row around`() {
        val logged = record("tags.tiqcdn.com", blocked = true, source = RuleSource.LIST, detail = "AdAway")
        val engine = FilterEngine(appRules = listOf(AppDomainRule("tags.tiqcdn.com", app, block = false)))

        val verdict = effectiveVerdict(logged, engine.decide(logged.domain, app))

        assertFalse(verdict.blocked)
        assertEquals(RuleSource.APP_RULE, verdict.source)
    }

    @Test
    fun `a verdict nobody wrote is left as the history it is`() {
        // With no rule of the user's, the line keeps reporting what actually happened — which
        // list blocked it, and that it was blocked at all. Recomputing that from an engine whose
        // lists have since been switched off would erase the evidence of why an app broke.
        val logged = record("dpm.demdex.net", blocked = true, source = RuleSource.LIST, detail = "AdGuard DNS filter")

        val verdict = effectiveVerdict(logged, FilterEngine().decide(logged.domain, app))

        assertTrue(verdict.blocked)
        assertEquals(RuleSource.LIST, verdict.source)
        assertEquals("AdGuard DNS filter", verdict.detail)
    }

    @Test
    fun `a rule for a sibling name leaves the line alone`() {
        // Blocking gam-movil.bbva.es must not repaint movil.bbva.es: suffix matching is not
        // prefix matching, and a line that changed for the wrong reason is worse than no line.
        val logged = record("movil.bbva.es", blocked = false, source = RuleSource.NONE, detail = "")
        val engine = FilterEngine(appRules = listOf(AppDomainRule("gam-movil.bbva.es", app, block = true)))

        assertFalse(effectiveVerdict(logged, engine.decide(logged.domain, app)).blocked)
    }
}
