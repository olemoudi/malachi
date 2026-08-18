package dev.malachi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json

class SettingsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a fresh install does not filter anything`() {
        // The safe direction: a user who has not decided yet gets an app that plainly isn't
        // running, not one silently blocking their bank.
        assertFalse(MalachiSettings().filteringEnabled)
        assertFalse(MalachiSettings().isFiltering(nowMs = 0))
    }

    @Test
    fun `pausing suspends filtering without forgetting the intent`() {
        val settings = MalachiSettings(filteringEnabled = true, pausedUntilMs = 1_000)
        assertTrue(settings.isPaused(nowMs = 500))
        assertFalse(settings.isFiltering(nowMs = 500))
        // The intent survives the pause; only the moment in time has to pass.
        assertTrue(settings.filteringEnabled)
        assertTrue(settings.isFiltering(nowMs = 1_500))
    }

    @Test
    fun `all-except covers everything but the exclusions`() {
        val settings = MalachiSettings(
            scopeMode = AppScopeMode.ALL_EXCEPT,
            excludedApps = setOf("com.bank"),
        )
        assertTrue(settings.covers("com.example.game"))
        assertFalse(settings.covers("com.bank"))
    }

    @Test
    fun `only-selected covers nothing but the inclusions`() {
        val settings = MalachiSettings(
            scopeMode = AppScopeMode.ONLY_SELECTED,
            includedApps = setOf("com.example.game"),
        )
        assertTrue(settings.covers("com.example.game"))
        assertFalse(settings.covers("com.bank"))
        // The two directions are opposites, not variations: an app absent from the settings is
        // covered in one mode and not in the other.
        assertNotEquals(
            settings.covers("com.unknown"),
            MalachiSettings(scopeMode = AppScopeMode.ALL_EXCEPT).covers("com.unknown"),
        )
    }

    @Test
    fun `the chosen apps are whichever set the mode names one by one`() {
        // The shortlist the Apps screen offers, and it points at opposite things in the two
        // modes: the exceptions in "all except", the whole scope in "only these". Getting it
        // backwards would offer somebody a chip labelled "2 excluded" that listed the hundred and
        // eighty apps they had never touched.
        val settings = MalachiSettings(
            excludedApps = setOf("com.bank.app", "com.car.app"),
            includedApps = setOf("com.game.app"),
        )

        assertEquals(
            setOf("com.bank.app", "com.car.app"),
            settings.copy(scopeMode = AppScopeMode.ALL_EXCEPT).chosenApps(),
        )
        assertEquals(
            setOf("com.game.app"),
            settings.copy(scopeMode = AppScopeMode.ONLY_SELECTED).chosenApps(),
        )
    }

    @Test
    fun `the chosen apps are exactly the ones that differ from what the mode does by default`() {
        // The property the chip leans on: everything in the shortlist is a decision somebody
        // made, and everything outside it is the mode's own default. If these ever came apart,
        // the shortlist would either hide a decision or invent one.
        val all = listOf("com.bank.app", "com.game.app", "com.untouched.app")

        val allExcept = MalachiSettings(
            scopeMode = AppScopeMode.ALL_EXCEPT,
            excludedApps = setOf("com.bank.app"),
        )
        assertEquals(all.filterNot(allExcept::covers).toSet(), allExcept.chosenApps())

        val onlySelected = MalachiSettings(
            scopeMode = AppScopeMode.ONLY_SELECTED,
            includedApps = setOf("com.game.app"),
        )
        assertEquals(all.filter(onlySelected::covers).toSet(), onlySelected.chosenApps())
    }

    @Test
    fun `nothing chosen is nothing to shortlist`() {
        // What makes the chip disappear rather than filter to an empty list.
        assertTrue(MalachiSettings(scopeMode = AppScopeMode.ALL_EXCEPT).chosenApps().isEmpty())
        assertTrue(MalachiSettings(scopeMode = AppScopeMode.ONLY_SELECTED).chosenApps().isEmpty())
    }

    @Test
    fun `only what is baked into the tunnel changes its shape`() {
        val base = MalachiSettings(filteringEnabled = true)

        // A rule or a list is read per query, so it must not force the tunnel to be rebuilt.
        assertEquals(base.tunnelShape(), base.copy(userBlocked = setOf("ads.example.com")).tunnelShape())
        assertEquals(base.tunnelShape(), base.copy(listChoices = mapOf("oisd-big" to true)).tunnelShape())
        assertEquals(base.tunnelShape(), base.copy(blockAnswer = BlockAnswerMode.NXDOMAIN).tunnelShape())

        // Watching one app is read per lookup like any other rule, so it must not either — a
        // rebuild is a blink of unfiltered DNS, and this is a diagnostic.
        assertEquals(
            base.tunnelShape(),
            base.copy(diagnoseApp = "com.example.game", diagnoseUntilMs = 1_000).tunnelShape(),
        )

        // The app scope and the bypass routes are fixed at establish() time, so they must.
        assertNotEquals(base.tunnelShape(), base.copy(excludedApps = setOf("com.bank")).tunnelShape())
        assertNotEquals(base.tunnelShape(), base.copy(scopeMode = AppScopeMode.ONLY_SELECTED).tunnelShape())
        assertNotEquals(base.tunnelShape(), base.copy(bypassGuard = BypassGuard.OFF).tunnelShape())
    }

    @Test
    fun `watching one app is a deadline, not a switch`() {
        // A switch left on is left on for months, and this one names domains while it runs. The
        // deadline is what makes the worst case half an hour rather than the life of the install.
        val watching = MalachiSettings(diagnoseApp = "com.example.game", diagnoseUntilMs = 1_000)
        assertEquals("com.example.game", watching.diagnosing(nowMs = 500))
        assertNull(watching.diagnosing(nowMs = 1_000), "the deadline is exclusive, like every other one here")
        assertNull(watching.diagnosing(nowMs = 5_000))
        // And a deadline with nobody named is nobody being watched, not everybody.
        assertNull(MalachiSettings(diagnoseUntilMs = 9_999).diagnosing(nowMs = 0))
        assertNull(MalachiSettings().diagnosing(nowMs = 0))
    }

    @Test
    fun `tunnel shape does not depend on the order a set was built in`() {
        val one = MalachiSettings(excludedApps = setOf("b.app", "a.app"))
        val two = MalachiSettings(excludedApps = setOf("a.app", "b.app"))
        assertEquals(one.tunnelShape(), two.tunnelShape())
    }

    @Test
    fun `per-app rules are read back for the app that owns them`() {
        val settings = MalachiSettings(
            appRules = listOf(
                AppRule("ads.example.com", "com.example.game", block = true),
                AppRule("cdn.example.com", "com.other", block = false),
            ),
        )
        assertEquals(1, settings.appRulesFor("com.example.game").size)
        assertEquals("ads.example.com", settings.appRulesFor("com.example.game").single().domain)
        assertTrue(settings.appRulesFor("com.nothing").isEmpty())
    }

    @Test
    fun `settings written by a newer version still decode`() {
        // Forward compatibility is what lets a release add a setting without a migration, and
        // what keeps an install that skipped versions from losing every rule it had.
        val fromTheFuture = """{"filteringEnabled":true,"somethingAddedLater":42}"""
        val decoded = json.decodeFromString(MalachiSettings.serializer(), fromTheFuture)
        assertTrue(decoded.filteringEnabled)
        assertEquals(BlockAnswerMode.NULL_ADDRESS, decoded.blockAnswer)
    }

    @Test
    fun `a round trip through json preserves every field`() {
        val settings = MalachiSettings(
            filteringEnabled = true,
            blockAnswer = BlockAnswerMode.REFUSED,
            scopeMode = AppScopeMode.ONLY_SELECTED,
            includedApps = setOf("com.example.game"),
            userBlocked = setOf("ads.example.com"),
            appRules = listOf(AppRule("t.example.com", "com.example.game", block = true)),
            listChoices = mapOf("adaway" to false),
            listEnabledAtMs = mapOf("oisd-big" to 1_700_000_000_000),
            upstream = UpstreamDns.QUAD9,
            bypassGuard = BypassGuard.PUBLIC_RESOLVERS,
        )
        val decoded = json.decodeFromString(
            MalachiSettings.serializer(),
            json.encodeToString(MalachiSettings.serializer(), settings),
        )
        assertEquals(settings, decoded)
    }

    // ---- when a list was switched on ----------------------------------------------------

    @Test
    fun `switching a list on records when, and switching it off forgets`() {
        // The whole point: an app broke, and the answer is nearly always the last list enabled.
        val on = MalachiSettings().withListEnabled("oisd-big", enabled = true, nowMs = 1_000)
        assertEquals(mapOf("oisd-big" to true), on.listChoices)
        assertEquals(1_000L, on.listEnabledAtMs["oisd-big"])

        // Off is not "enabled at an older time", it is not enabled — the row must not keep
        // offering a date for something that isn't on.
        val off = on.withListEnabled("oisd-big", enabled = false, nowMs = 2_000)
        assertEquals(false, off.listChoices["oisd-big"])
        assertNull(off.listEnabledAtMs["oisd-big"])

        // And re-enabling dates it from now, not from the first time it was ever on.
        val again = off.withListEnabled("oisd-big", enabled = true, nowMs = 3_000)
        assertEquals(3_000L, again.listEnabledAtMs["oisd-big"])
    }

    @Test
    fun `a list that was never switched on has no date`() {
        // The two that ship on were not switched on by anybody, and an install that predates
        // this has no honest date to show. Both are left undated rather than dated by guesswork.
        assertNull(MalachiSettings().listEnabledAtMs["adguard-dns"])
        assertTrue(MalachiSettings(listChoices = mapOf("oisd-big" to true)).listEnabledAtMs.isEmpty())
    }

    @Test
    fun `when a list was enabled does not change the tunnel`() {
        // A rebuild tears down the tunnel; a list being switched on must never cause one.
        val base = MalachiSettings(filteringEnabled = true)
        assertEquals(base.tunnelShape(), base.withListEnabled("oisd-big", true, nowMs = 1).tunnelShape())
    }

    @Test
    fun `a persisted app rule becomes the engine's rule unchanged`() {
        val rule = AppRule("ads.example.com", "com.example.game", block = true).toDomainRule()
        assertEquals("ads.example.com", rule.domain)
        assertEquals("com.example.game", rule.packageName)
        assertTrue(rule.block)
    }

    // ---- migrations ---------------------------------------------------------------------

    @Test
    fun `the android auto exemption is withdrawn from an install that has it`() {
        // A past version put Android Auto outside the tunnel, believing no VPN could coexist
        // with it. The real cause was this tunnel not allowing an app to reach a network it
        // binds to; with that allowed, Android Auto works filtered — confirmed on the car that
        // reported it. Left alone the exclusion would sit there unexplained forever.
        val asShipped = MalachiSettings(
            excludedApps = setOf(MalachiSettings.ANDROID_AUTO, "com.bank.app"),
            settingsVersion = 0,
        )
        val migrated = asShipped.migrated()

        assertTrue(MalachiSettings.ANDROID_AUTO !in migrated.excludedApps)
        assertTrue("com.bank.app" in migrated.excludedApps)
        assertEquals(MalachiSettings.CURRENT_VERSION, migrated.settingsVersion)
    }

    @Test
    fun `a migration runs once and then leaves the settings alone`() {
        // Somebody who decides to exclude Android Auto for their own reasons must not have it
        // undone at every launch.
        val migrated = MalachiSettings(excludedApps = setOf(MalachiSettings.ANDROID_AUTO)).migrated()
        val theirOwnChoice = migrated.copy(excludedApps = setOf(MalachiSettings.ANDROID_AUTO))

        assertEquals(theirOwnChoice, theirOwnChoice.migrated())
        assertTrue(MalachiSettings.ANDROID_AUTO in theirOwnChoice.migrated().excludedApps)
    }

    @Test
    fun `a fresh install is migrated without being changed`() {
        val fresh = MalachiSettings()
        val migrated = fresh.migrated()
        assertEquals(fresh.copy(settingsVersion = MalachiSettings.CURRENT_VERSION), migrated)
        assertTrue(migrated.excludedApps.isEmpty())
    }

    @Test
    fun `migrating is idempotent`() {
        val once = MalachiSettings(excludedApps = setOf(MalachiSettings.ANDROID_AUTO)).migrated()
        assertEquals(once, once.migrated().migrated())
    }

    @Test
    fun `withdrawing the exemption rebuilds the tunnel`() {
        // The app scope is baked into the tun at establish(), so Android Auto stays unfiltered
        // until something causes a rebuild — and this has to be that something.
        val before = MalachiSettings(excludedApps = setOf(MalachiSettings.ANDROID_AUTO))
        assertTrue(before.tunnelShape() != before.migrated().tunnelShape())
    }

    // ---- letting an app out of the tunnel ----------------------------------------------

    @Test
    fun `apps may reach a network they ask for, by default`() {
        // Off, Android refuses it to every app while a VPN is up, whatever the VPN routes —
        // which is what stopped Android Auto reaching the head unit it was plugged into.
        assertTrue(MalachiSettings().bypassAllowed)
    }

    @Test
    fun `changing it rebuilds the tunnel`() {
        // It is passed to establish() and cannot be changed on a live tun, so it has to be part
        // of the shape or the setting would appear to do nothing until something else caused a
        // rebuild.
        val on = MalachiSettings()
        val off = on.copy(bypassAllowed = false)
        assertTrue(on.tunnelShape() != off.tunnelShape())
    }

    @Test
    fun `it is not part of what a rule change touches`() {
        // Rules are read per query. Editing one must not rebuild the tunnel, bypass or no.
        val before = MalachiSettings()
        val after = before.copy(userBlocked = setOf("ads.example.com"))
        assertEquals(before.tunnelShape(), after.tunnelShape())
    }
}
