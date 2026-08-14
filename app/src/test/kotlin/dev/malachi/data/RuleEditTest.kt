package dev.malachi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Writing a rule and taking it back.
 *
 * The undo is offered from a snackbar over a list whose rows move as new lookups arrive, so the
 * tap that produces it is frequently a tap on the wrong row — which makes "put it back exactly"
 * the whole point, and makes "delete what was added" a wrong answer that looks right in every
 * case except the one that matters.
 */
class RuleEditTest {

    private val domain = "ads.example.com"
    private val app = "com.example.game"

    @Test
    fun `a rule is written into one list and out of the other`() {
        val after = MalachiSettings().withUserRule(domain, block = true)
        assertTrue(domain in after.userBlocked)
        assertFalse(domain in after.userAllowed)
    }

    @Test
    fun `undoing a rule leaves nothing behind`() {
        val before = MalachiSettings()
        val after = before.withUserRule(domain, block = true)
        assertEquals(before, after.withUserRuleFrom(before, domain))
    }

    @Test
    fun `undoing a rule that replaced one puts the old one back`() {
        // The case a delete gets wrong: the domain was allowed, blocking it removed that, and an
        // undo that merely unblocked would leave it in neither list — a second silent edit.
        val before = MalachiSettings(userAllowed = setOf(domain))
        val after = before.withUserRule(domain, block = true)
        assertFalse(domain in after.userAllowed)

        val undone = after.withUserRuleFrom(before, domain)
        assertTrue(domain in undone.userAllowed)
        assertFalse(domain in undone.userBlocked)
    }

    @Test
    fun `undoing touches only the domain it is about`() {
        val before = MalachiSettings(userBlocked = setOf("other.example.com"))
        val after = before.withUserRule(domain, block = true)
        val undone = after.withUserRuleFrom(before, domain)
        assertEquals(setOf("other.example.com"), undone.userBlocked)
    }

    @Test
    fun `a per-app rule replaces the app's own rule for that domain and no other`() {
        val before = MalachiSettings(
            appRules = listOf(
                AppRule(domain, app, block = false),
                AppRule(domain, "com.other.app", block = false),
            ),
        )
        val after = before.withAppRule(domain, app, block = true)
        assertEquals(2, after.appRules.size)
        assertTrue(after.appRules.any { it.packageName == app && it.block })
        assertTrue(after.appRules.any { it.packageName == "com.other.app" && !it.block })
    }

    @Test
    fun `undoing a per-app rule restores the one it replaced`() {
        val before = MalachiSettings(appRules = listOf(AppRule(domain, app, block = false)))
        val after = before.withAppRule(domain, app, block = true)
        val undone = after.withAppRuleFrom(before, domain, app)
        assertEquals(before.appRules, undone.appRules)
    }

    @Test
    fun `undoing a per-app rule that replaced nothing removes it`() {
        val before = MalachiSettings()
        val after = before.withAppRule(domain, app, block = true)
        assertEquals(emptyList<AppRule>(), after.withAppRuleFrom(before, domain, app).appRules)
    }

    @Test
    fun `writing a rule never changes the shape of the tunnel`() {
        // Rules are read per query; a rebuild for one would be a blink of unfiltered DNS every
        // time somebody tapped a row.
        val before = MalachiSettings()
        assertEquals(before.tunnelShape(), before.withUserRule(domain, block = true).tunnelShape())
        assertEquals(before.tunnelShape(), before.withAppRule(domain, app, block = true).tunnelShape())
    }
}
