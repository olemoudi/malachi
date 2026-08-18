package dev.malachi.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * What one settings write costs the process, which is not a storage question but a battery one.
 *
 * [SettingsStore.settings] is a cold flow and half the app collects it for the entire life of the
 * process — the tunnel, the filter's rule assembly, the list scheduler, the work scheduler, the
 * view model — and each collector ends in its own `map { decode(...) }`. So a single boolean being
 * written used to parse the whole settings document once per collector, and that document is not
 * small on a phone that has been used: a few hundred per-app exceptions accumulated one broken app
 * at a time is a real amount of JSON to walk six times because somebody tapped pause.
 */
class SettingsDecodeTest {

    @Test
    fun `one write is decoded once, however many collectors read it`() = runTest {
        val store = SettingsStore(FakePreferencesStore())
        store.update { it.copy(userBlocked = setOf("ads.example.com")) }

        // Two reads of the same stored value. Same object, so exactly one parse happened —
        // which is the property every additional collector rides on.
        val first = store.current()
        val second = store.current()
        assertSame(first, second, "the settings blob was parsed twice for one write")
        assertEquals(setOf("ads.example.com"), first.userBlocked)
    }

    @Test
    fun `a new write is a new parse, so nobody reads a stale value`() = runTest {
        val store = SettingsStore(FakePreferencesStore())
        store.update { it.copy(userBlocked = setOf("ads.example.com")) }
        val before = store.current()

        store.update { it.copy(pausedUntilMs = 1_700_000_000_000) }
        val after = store.current()

        assertNotSame(before, after)
        assertEquals(1_700_000_000_000, after.pausedUntilMs)
        // And the new value is the one that is remembered from here on.
        assertSame(after, store.current())
    }

    @Test
    fun `unreadable settings still fall back to the defaults, cache or no cache`() = runTest {
        val store = SettingsStore(FakePreferencesStore())
        store.update { it.copy(filteringEnabled = true) }
        assertEquals(true, store.current().filteringEnabled)

        // The defaults leave filtering off: visibly not running, rather than silently not working.
        val damaged = SettingsStore(FakePreferencesStore())
        assertEquals(false, damaged.current().filteringEnabled)
    }
}
