package dev.malachi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The backup format.
 *
 * Everything here is about the day somebody actually needs it, which is the day their old phone
 * is gone: a file written months earlier, by a version that no longer exists, read by one that
 * did not exist when it was written.
 */
class BackupTest {

    private val decided = MalachiSettings(
        userBlocked = setOf("ads.example.com", "tracker.example.com"),
        userAllowed = setOf("cdn.example.com"),
        appRules = listOf(
            AppRule("t.example.com", "com.example.game", block = true),
            AppRule("bbva.es", "com.bbva", block = false),
        ),
        listChoices = mapOf("oisd-big" to true, "adaway" to false),
        scopeMode = AppScopeMode.ONLY_SELECTED,
        includedApps = setOf("com.example.game"),
        excludedApps = setOf("com.bank"),
        blockAnswer = BlockAnswerMode.NXDOMAIN,
        upstream = UpstreamDns.QUAD9,
        customUpstream = "9.9.9.9",
        bypassGuard = BypassGuard.PUBLIC_RESOLVERS,
        bypassAllowed = false,
        listUpdateHours = 72,
        listUpdateWifiOnly = false,
        queryLogEnabled = false,
        updateWifiOnly = true,
    )

    private fun roundTrip(settings: MalachiSettings): Backup {
        val text = Backup.encode(Backup.of(settings, appVersion = "9.9.9-test", nowMs = 1_700_000_000_000))
        return Backup.decode(text).getOrNull() ?: error("a file this app just wrote would not read back")
    }

    @Test
    fun `a backup written by this version reads back with every decision intact`() {
        val restored = roundTrip(decided).restoredInto(MalachiSettings())

        assertEquals(decided.userBlocked, restored.userBlocked)
        assertEquals(decided.userAllowed, restored.userAllowed)
        assertEquals(decided.appRules, restored.appRules)
        assertEquals(decided.listChoices, restored.listChoices)
        assertEquals(decided.scopeMode, restored.scopeMode)
        assertEquals(decided.includedApps, restored.includedApps)
        assertEquals(decided.excludedApps, restored.excludedApps)
        assertEquals(decided.blockAnswer, restored.blockAnswer)
        assertEquals(decided.upstream, restored.upstream)
        assertEquals(decided.customUpstream, restored.customUpstream)
        assertEquals(decided.bypassGuard, restored.bypassGuard)
        assertEquals(decided.bypassAllowed, restored.bypassAllowed)
        assertEquals(decided.listUpdateHours, restored.listUpdateHours)
        assertEquals(decided.listUpdateWifiOnly, restored.listUpdateWifiOnly)
        assertEquals(decided.queryLogEnabled, restored.queryLogEnabled)
        assertEquals(decided.updateWifiOnly, restored.updateWifiOnly)
    }

    @Test
    fun `restoring does not paste this phone's state onto another one`() {
        // A backup carries decisions, never the state of the device it came from. Restoring a
        // paused filter onto a working phone would stop it for fifteen minutes with nothing on
        // screen to explain why, and restoring "filtering off" would silently disarm it.
        val running = MalachiSettings(
            filteringEnabled = true,
            pausedUntilMs = 0,
            diagnosticsUntilMs = 0,
            alwaysOnTipDismissed = true,
            welcomeSeen = true,
        )
        val fromAPausedPhone = Backup.of(
            decided.copy(filteringEnabled = false, pausedUntilMs = 9_999_999),
            appVersion = "x",
            nowMs = 0,
        )

        val restored = fromAPausedPhone.restoredInto(running)

        assertTrue(restored.filteringEnabled)
        assertEquals(0, restored.pausedUntilMs)
        assertTrue(restored.alwaysOnTipDismissed)
        // Nor the introduction: it is about this install, and showing it again to somebody
        // restoring onto a phone they have been using for a year would be nonsense.
        assertTrue(restored.welcomeSeen)
        assertEquals(decided.userBlocked, restored.userBlocked)
    }

    @Test
    fun `a backup never carries an app somebody was watching`() {
        // Which app is under a microscope right now is an observation being made on one phone,
        // not a decision — and the name of an app somebody was debugging is exactly the kind of
        // thing that has no business in a file handed to a cloud drive.
        val diagnosing = MalachiSettings(
            userBlocked = setOf("ads.example.com"),
            diagnoseApp = "com.example.underinvestigation",
            diagnoseUntilMs = 9_999_999,
        )
        val encoded = Backup.encode(Backup.of(diagnosing, appVersion = "x", nowMs = 0))
        assertFalse(encoded.contains("com.example.underinvestigation"))

        val restored = Backup.of(diagnosing, appVersion = "x", nowMs = 0).restoredInto(MalachiSettings())
        assertEquals("", restored.diagnoseApp)
        assertEquals(0, restored.diagnoseUntilMs)
    }

    @Test
    fun `a file from an older version is missing fields, and that is not an error`() {
        // Written by hand as an old version would have: only the fields that existed then. Every
        // field has a default, so what is absent comes back as the default rather than as a
        // failure to read the file at all.
        val old = """
            {
              "format": 1,
              "userBlocked": ["ads.example.com"],
              "listChoices": {"oisd-big": true}
            }
        """.trimIndent()

        val backup = Backup.decode(old).getOrNull()

        assertNotNull(backup)
        assertEquals(setOf("ads.example.com"), backup!!.userBlocked)
        assertEquals(mapOf("oisd-big" to true), backup.listChoices)
        assertTrue(backup.userAllowed.isEmpty())
        assertEquals(UpstreamDns.SYSTEM, backup.upstream)
        assertEquals(AppScopeMode.ALL_EXCEPT, backup.scopeMode)
    }

    @Test
    fun `a file from a newer version is read rather than refused`() {
        // Somebody restoring after a downgrade, or from a phone that updated first. Refusing
        // would tell them their own backup is unreadable; instead the keys this version knows
        // are restored and the rest are dropped.
        val newer = """
            {
              "format": 99,
              "userBlocked": ["ads.example.com"],
              "somethingWeHaveNotInventedYet": {"a": 1},
              "anotherNewField": ["x", "y"]
            }
        """.trimIndent()

        val backup = Backup.decode(newer).getOrNull()

        assertNotNull(backup)
        assertEquals(setOf("ads.example.com"), backup!!.userBlocked)
        assertEquals(99, backup.format)
    }

    @Test
    fun `anything that is not one of our files is refused`() {
        // The realistic wrong pick from a file manager: a photo, a text note, an empty file, or
        // a truncated copy. All of them must fail loudly rather than restore an empty rule set
        // over a year of work.
        assertNull(Backup.decode("").getOrNull())
        assertNull(Backup.decode("not json at all").getOrNull())
        assertNull(Backup.decode("{\"format\": 1, \"userBlocked\": [\"a.com\"").getOrNull(), "truncated JSON was accepted")
        assertNull(Backup.decode("<html><body>hello</body></html>").getOrNull())
    }

    @Test
    fun `an empty object is a valid but empty backup, and the counts say so`() {
        // "{}" is legal JSON and every field has a default, so it reads — which is right, and is
        // also why the screen shows the counts: restoring this replaces everything with nothing,
        // and the only defence is that the user is told "0 rules and 0 lists".
        val backup = Backup.decode("{}").getOrNull()

        assertNotNull(backup)
        assertEquals(0, backup!!.ruleCount)
        assertEquals(0, backup.listCount)
    }

    @Test
    fun `the counts are what the screen shows after a restore`() {
        val backup = Backup.of(decided, appVersion = "x", nowMs = 0)

        // Two blocked, one allowed, two per-app.
        assertEquals(5, backup.ruleCount)
        // Only the lists that are on: `adaway` is explicitly off in these settings.
        assertEquals(1, backup.listCount)
    }

    @Test
    fun `a restored backup counts as saved, so nobody is asked to export what they just imported`() {
        val restored = roundTrip(decided).restoredInto(MalachiSettings())

        assertFalse(BackupPolicy.isStale(restored))
        assertEquals(restored.decisionsFingerprint(), restored.backupFingerprint)
    }

    @Test
    fun `the suggested file name is dated and ends in json`() {
        val name = Backup.suggestedFileName(1_700_000_000_000)

        assertTrue(name.startsWith("malachi-backup-"), name)
        assertTrue(name.endsWith(".json"), name)
        assertTrue(name.contains("2023-11"), name)
    }

    @Test
    fun `the update channel is not carried by a backup`() {
        // It looks like updateWifiOnly, which is in the file, and it is deliberately not:
        // restoring somebody's settings onto a fresh phone must not quietly enrol that phone in
        // builds nobody else has run. Reversible in one line if that ever reads wrong — this
        // test is here so it would be a decision rather than a drift.
        val onTesting = MalachiSettings(updateChannel = UpdateChannel.TESTING, userBlocked = setOf("ads.example.com"))
        val restored = Backup.of(onTesting, "1.1.0-alpha", nowMs = 0)
            .restoredInto(MalachiSettings(updateChannel = UpdateChannel.STABLE))

        assertEquals(UpdateChannel.STABLE, restored.updateChannel)
        assertEquals(setOf("ads.example.com"), restored.userBlocked)
    }
}
