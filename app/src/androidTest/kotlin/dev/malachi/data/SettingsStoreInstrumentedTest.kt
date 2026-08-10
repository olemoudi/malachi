package dev.malachi.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The settings against a real DataStore, including the state this app is most likely to reach
 * after months of running: a file that got damaged and never repaired itself.
 *
 * The store is built through [settingsCorruptionHandler] — the same expression the production
 * one is configured with — so what is exercised is the app's own recovery and not a
 * reconstruction of it. Each store gets its own scope and is shut down before the next one
 * opens, because DataStore refuses two live instances over one file and is right to.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreInstrumentedTest {

    private lateinit var directory: File
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        directory = File(context.cacheDir, "settings-test-${System.nanoTime()}").apply { mkdirs() }
        file = File(directory, "settings.preferences_pb")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** Opens a store over the test's file, runs [block], and shuts it down again. */
    private fun <T> withStore(block: suspend (SettingsStore) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = settingsCorruptionHandler(),
                scope = scope,
                produceFile = { file },
            )
            block(SettingsStore(store))
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    @Test
    fun settingsSurviveAWriteAndAFreshRead() {
        withStore { store ->
            store.update {
                it.copy(
                    filteringEnabled = true,
                    userBlocked = setOf("ads.example.com"),
                    excludedApps = setOf("com.bank.app"),
                    bypassGuard = BypassGuard.PUBLIC_RESOLVERS,
                )
            }
        }

        // A second store over the same file: the process restart, in miniature.
        val reopened = withStore { it.current() }
        assertTrue(reopened.filteringEnabled)
        assertEquals(setOf("ads.example.com"), reopened.userBlocked)
        assertEquals(setOf("com.bank.app"), reopened.excludedApps)
        assertEquals(BypassGuard.PUBLIC_RESOLVERS, reopened.bypassGuard)
    }

    @Test
    fun aDamagedFileIsReplacedRatherThanFailingForever() {
        withStore { it.update { s -> s.copy(filteringEnabled = true, userBlocked = setOf("ads.example.com")) } }

        // What an interrupted write leaves behind, months into an install.
        file.writeBytes(ByteArray(64) { 0x7A })

        // Reading recovers to the defaults rather than throwing...
        assertFalse(withStore { it.current() }.filteringEnabled)

        // ...and, the half that used to be missing, writing works again afterwards. Without the
        // corruption handler this is where the app became permanently unable to save anything:
        // the read path fell back to defaults and every write threw, forever.
        withStore { it.update { s -> s.copy(filteringEnabled = true, userBlocked = setOf("tracker.example.com")) } }

        val afterwards = withStore { it.current() }
        assertTrue(afterwards.filteringEnabled)
        assertEquals(setOf("tracker.example.com"), afterwards.userBlocked)
    }

    @Test
    fun aSettingThisVersionDoesNotKnowAboutIsIgnoredRatherThanFatal() {
        // The promise that lets a new field ship without a migration, and that lets an install
        // which skipped versions still read its own settings.
        withStore { it.update { s -> s.copy(filteringEnabled = true, userBlocked = setOf("ads.example.com")) } }

        val key = stringPreferencesKey("settings_json")
        withStore { _ -> }
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val raw = PreferenceDataStoreFactory.create(
                    corruptionHandler = settingsCorruptionHandler(),
                    scope = scope,
                    produceFile = { file },
                )
                raw.updateData { prefs ->
                    val stored = prefs[key].orEmpty()
                    prefs.toMutablePreferences().apply {
                        set(key, stored.dropLast(1) + ""","aFieldFromTheFuture":{"nested":1}}""")
                    }
                }
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
            }
        }

        val read = withStore { it.current() }
        assertTrue(read.filteringEnabled)
        assertEquals(setOf("ads.example.com"), read.userBlocked)
    }

    @Test
    fun storedJsonThatIsNotSettingsFallsBackToDefaults() {
        val key = stringPreferencesKey("settings_json")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val raw = PreferenceDataStoreFactory.create(
                    corruptionHandler = settingsCorruptionHandler(),
                    scope = scope,
                    produceFile = { file },
                )
                raw.updateData { prefs -> prefs.toMutablePreferences().apply { set(key, "{ not settings") } }
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
            }
        }

        // Defaults leave filtering off, which is the safe direction: visibly not running rather
        // than silently blocking something.
        assertFalse(withStore { it.current() }.filteringEnabled)
    }
}
