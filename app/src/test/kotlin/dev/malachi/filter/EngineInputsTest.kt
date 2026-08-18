package dev.malachi.filter

import dev.malachi.data.AppRule
import dev.malachi.data.BlockAnswerMode
import dev.malachi.data.BypassGuard
import dev.malachi.data.GuidedSearch
import dev.malachi.data.MalachiSettings
import dev.malachi.data.UpstreamDns
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * What has to change before the filter is assembled again.
 *
 * Two failures live here and they point in opposite directions. Rebuilding too *often* is a
 * battery cost with no symptom: the settings blob emits on every write, and most writes have
 * nothing to do with filtering — a pause, a diagnostics deadline pushed back, a step of the
 * guided search, a dismissed tip, a backup reminder — so the user's rules were being sorted into
 * two fresh indexes because somebody tapped pause, and a nine-step guided search did it nine
 * times. Rebuilding too *rarely* is far worse and has no symptom either: a rule field this does
 * not name is a rule the user writes, sees listed on the screen, and that the tunnel never
 * consults.
 *
 * So both directions are pinned. A field added to [MalachiSettings] that the engine reads has to
 * appear here, and this test is what says so.
 */
class EngineInputsTest {

    private val base = MalachiSettings(
        userBlocked = setOf("ads.example.com"),
        userAllowed = setOf("cdn.example.com"),
        appRules = listOf(AppRule("tracker.example.com", "com.example.app", block = true)),
    )

    @Test
    fun `everything that is not a rule leaves the filter alone`() {
        val busy = base.copy(
            filteringEnabled = true,
            pausedUntilMs = 1_700_000_000_000,
            diagnosticsUntilMs = 1_700_000_900_000,
            diagnoseApp = "com.example.app",
            diagnoseUntilMs = 1_700_000_900_000,
            guide = GuidedSearch(packageName = "com.example.app"),
            blockAnswer = BlockAnswerMode.NXDOMAIN,
            upstream = UpstreamDns.QUAD9,
            bypassGuard = BypassGuard.PUBLIC_RESOLVERS,
            bypassAllowed = false,
            queryLogEnabled = false,
            listUpdateHours = 6,
            alwaysOnTipDismissed = true,
            welcomeSeen = true,
            backupRemindAtMs = 1_700_000_000_000,
            backupFingerprint = "whatever",
            excludedApps = setOf("com.bank.app"),
        )

        assertEquals(base.engineInputs(), busy.engineInputs())
    }

    @Test
    fun `every rule the engine is made of does rebuild it`() {
        assertNotEquals(base.engineInputs(), base.copy(userBlocked = base.userBlocked + "more.example.com").engineInputs())
        assertNotEquals(base.engineInputs(), base.copy(userAllowed = emptySet()).engineInputs())
        assertNotEquals(
            base.engineInputs(),
            base.copy(appRules = base.appRules + AppRule("other.example.com", "com.example.app", block = false)).engineInputs(),
        )
        // Including the shape of a rule rather than only its presence: flipping block to allow is
        // the whole of what an exemption is.
        assertNotEquals(
            base.engineInputs(),
            base.copy(appRules = listOf(AppRule("tracker.example.com", "com.example.app", block = false))).engineInputs(),
        )
    }
}
