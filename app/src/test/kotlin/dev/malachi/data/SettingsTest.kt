package dev.malachi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    fun `only what is baked into the tunnel changes its shape`() {
        val base = MalachiSettings(filteringEnabled = true)

        // A rule or a list is read per query, so it must not force the tunnel to be rebuilt.
        assertEquals(base.tunnelShape(), base.copy(userBlocked = setOf("ads.example.com")).tunnelShape())
        assertEquals(base.tunnelShape(), base.copy(listChoices = mapOf("oisd-big" to true)).tunnelShape())
        assertEquals(base.tunnelShape(), base.copy(blockAnswer = BlockAnswerMode.NXDOMAIN).tunnelShape())

        // The app scope and the bypass routes are fixed at establish() time, so they must.
        assertNotEquals(base.tunnelShape(), base.copy(excludedApps = setOf("com.bank")).tunnelShape())
        assertNotEquals(base.tunnelShape(), base.copy(scopeMode = AppScopeMode.ONLY_SELECTED).tunnelShape())
        assertNotEquals(base.tunnelShape(), base.copy(bypassGuard = BypassGuard.OFF).tunnelShape())
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
            upstream = UpstreamDns.QUAD9,
            bypassGuard = BypassGuard.PUBLIC_RESOLVERS,
        )
        val decoded = json.decodeFromString(
            MalachiSettings.serializer(),
            json.encodeToString(MalachiSettings.serializer(), settings),
        )
        assertEquals(settings, decoded)
    }

    @Test
    fun `a persisted app rule becomes the engine's rule unchanged`() {
        val rule = AppRule("ads.example.com", "com.example.game", block = true).toDomainRule()
        assertEquals("ads.example.com", rule.domain)
        assertEquals("com.example.game", rule.packageName)
        assertTrue(rule.block)
    }

    // ---- the apps a VPN breaks ---------------------------------------------------------

    @Test
    fun `android auto is exempted once, and only once`() {
        // Android Auto refuses to start while any VPN is up and says so itself; nothing about
        // the routes changes that, so the only thing that helps is being outside the tunnel.
        val fresh = MalachiSettings()
        val exempted = fresh.withKnownIncompatibleAppsExempted()

        assertTrue(MalachiSettings.ANDROID_AUTO in exempted.excludedApps)
        assertTrue(exempted.incompatibleAppsExempted)
        // Idempotent: running again changes nothing.
        assertEquals(exempted, exempted.withKnownIncompatibleAppsExempted())
    }

    @Test
    fun `somebody who chooses to filter their car is not undone at the next launch`() {
        // The exemption is a one-time nudge, not a policy. Having been applied and then
        // reversed by hand, it must stay reversed.
        val afterTheUserPutItBack = MalachiSettings()
            .withKnownIncompatibleAppsExempted()
            .let { it.copy(excludedApps = it.excludedApps - MalachiSettings.ANDROID_AUTO) }

        assertEquals(afterTheUserPutItBack, afterTheUserPutItBack.withKnownIncompatibleAppsExempted())
        assertTrue(MalachiSettings.ANDROID_AUTO !in afterTheUserPutItBack.excludedApps)
    }

    @Test
    fun `exempting keeps every other exclusion the user made`() {
        val mine = MalachiSettings(excludedApps = setOf("com.bank.app", "com.example.game"))
        val exempted = mine.withKnownIncompatibleAppsExempted()

        assertTrue("com.bank.app" in exempted.excludedApps)
        assertTrue("com.example.game" in exempted.excludedApps)
        assertEquals(3, exempted.excludedApps.size)
    }

    @Test
    fun `exempting an app changes the shape of the tunnel`() {
        // The app scope is baked into the tun at establish(), so this has to be a rebuild or the
        // exemption would not take effect until something else happened to cause one.
        val before = MalachiSettings()
        val after = before.withKnownIncompatibleAppsExempted()
        assertTrue(before.tunnelShape() != after.tunnelShape())
    }

    @Test
    fun `the exemption list stays short on purpose`() {
        // Google Play Services would fix more and quietly stop filtering most of what this app
        // exists to filter. If this ever grows, it should be a decision and not a drift.
        assertEquals(setOf(MalachiSettings.ANDROID_AUTO), MalachiSettings.INCOMPATIBLE_WITH_A_VPN)
    }
}
