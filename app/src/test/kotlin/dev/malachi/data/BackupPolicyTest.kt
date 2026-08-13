package dev.malachi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When the backup is offered, and — the harder half — when it shuts up.
 *
 * A fortnight of putting it off is a number here; nothing waits.
 */
class BackupPolicyTest {

    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_700_000_000_000L

    private val withARule = MalachiSettings(userBlocked = setOf("ads.example.com"))

    @Test
    fun `a fresh install is never asked to save nothing`() {
        // No rules, no lists chosen: there is nothing a backup would protect, and asking would
        // teach somebody to dismiss the one reminder that matters later.
        val fresh = MalachiSettings()

        assertFalse(BackupPolicy.isStale(fresh))
        assertFalse(BackupPolicy.reminderDue(fresh, t0))
    }

    @Test
    fun `the first rule of their own is the first offer, and it is immediate`() {
        assertTrue(BackupPolicy.isStale(withARule))
        assertTrue(BackupPolicy.reminderDue(withARule, t0))
    }

    @Test
    fun `turning a list on is also worth saving`() {
        // The other half of what cannot be rebuilt from memory: which lists someone settled on.
        val listOnly = MalachiSettings(listChoices = mapOf("oisd-big" to true))

        assertTrue(BackupPolicy.reminderDue(listOnly, t0))
    }

    @Test
    fun `later means three days, then a fortnight, then every fortnight`() {
        var settings = withARule

        settings = BackupPolicy.laterFrom(settings, t0)
        assertFalse(BackupPolicy.reminderDue(settings, t0 + 3 * day - 1))
        assertTrue(BackupPolicy.reminderDue(settings, t0 + 3 * day))

        settings = BackupPolicy.laterFrom(settings, t0 + 3 * day)
        assertFalse(BackupPolicy.reminderDue(settings, t0 + 3 * day + 15 * day - 1))
        assertTrue(BackupPolicy.reminderDue(settings, t0 + 3 * day + 15 * day))

        // And from there it repeats rather than widening forever, so it never becomes a reminder
        // that arrives once a year.
        var at = t0 + 18 * day
        repeat(5) {
            settings = BackupPolicy.laterFrom(settings, at)
            assertFalse(BackupPolicy.reminderDue(settings, at + 15 * day - 1))
            assertTrue(BackupPolicy.reminderDue(settings, at + 15 * day))
            at += 15 * day
        }
    }

    @Test
    fun `once a copy exists nothing is ever said again, until something changes`() {
        val saved = BackupPolicy.backedUp(withARule)

        assertFalse(BackupPolicy.isStale(saved))
        // A year later, still nothing: the rules have not moved.
        assertFalse(BackupPolicy.reminderDue(saved, t0 + 365 * day))

        // A new rule is a new reason, and it is offered at once rather than after the old gap.
        val changed = saved.copy(userBlocked = saved.userBlocked + "another.example.com")
        assertTrue(BackupPolicy.reminderDue(changed, t0 + 365 * day))
    }

    @Test
    fun `a list switched off after a backup is a change too`() {
        val saved = BackupPolicy.backedUp(withARule.copy(listChoices = mapOf("oisd-big" to true)))

        assertFalse(BackupPolicy.reminderDue(saved, t0))
        assertTrue(BackupPolicy.reminderDue(saved.copy(listChoices = mapOf("oisd-big" to false)), t0))
    }

    @Test
    fun `the same decisions in a different order are not a change`() {
        // Sets and maps do not promise an order, and a fingerprint that noticed one would nag
        // after every restart for no reason at all.
        val saved = BackupPolicy.backedUp(
            withARule.copy(
                userBlocked = setOf("a.example.com", "b.example.com"),
                appRules = listOf(AppRule("x.com", "com.a", true), AppRule("y.com", "com.b", false)),
            ),
        )
        val reordered = saved.copy(
            userBlocked = setOf("b.example.com", "a.example.com"),
            appRules = listOf(AppRule("y.com", "com.b", false), AppRule("x.com", "com.a", true)),
        )

        assertFalse(BackupPolicy.reminderDue(reordered, t0 + day))
    }

    @Test
    fun `silenced stays silenced through any number of changes`() {
        val silent = BackupPolicy.silenced(withARule)

        assertTrue(BackupPolicy.isStale(silent), "the copy is still out of date, it is just not mentioned")
        assertFalse(BackupPolicy.reminderDue(silent, t0 + 365 * day))
        assertFalse(BackupPolicy.reminderDue(silent.copy(userBlocked = setOf("new.example.com")), t0 + 365 * day))
    }

    @Test
    fun `switching reminders back on offers immediately rather than after the old delay`() {
        val silent = BackupPolicy.silenced(BackupPolicy.laterFrom(withARule, t0))

        val back = BackupPolicy.unsilenced(silent)

        assertTrue(BackupPolicy.reminderDue(back, t0))
        assertEquals(0, back.backupRemindStage)
    }

    @Test
    fun `a backup after putting it off resets the schedule for next time`() {
        val putOffTwice = BackupPolicy.laterFrom(BackupPolicy.laterFrom(withARule, t0), t0 + 3 * day)

        val saved = BackupPolicy.backedUp(putOffTwice)
        val changedLater = saved.copy(userBlocked = setOf("new.example.com"))

        // Immediately, and the next "later" is three days again rather than a fortnight.
        assertTrue(BackupPolicy.reminderDue(changedLater, t0 + 30 * day))
        val later = BackupPolicy.laterFrom(changedLater, t0 + 30 * day)
        assertTrue(BackupPolicy.reminderDue(later, t0 + 33 * day))
    }
}
