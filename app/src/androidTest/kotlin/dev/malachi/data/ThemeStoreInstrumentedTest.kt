package dev.malachi.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The theme against a real DataStore, in the state this app reaches after months of running: a
 * file that got damaged and never repaired itself.
 *
 * The same lesson as the settings file, learned twice. DataStore answers a damaged file by
 * throwing from every read *and every write*, forever — so catching only the read side, which is
 * all this store used to do, leaves an app that shows the default theme and can never store
 * another one. Choosing dark writes nothing, the next launch is light again, and the throw goes
 * into a view-model coroutine that has no business dying of it.
 */
@RunWith(AndroidJUnit4::class)
class ThemeStoreInstrumentedTest {

    private lateinit var directory: File
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        directory = File(context.cacheDir, "theme-test-${System.nanoTime()}").apply { mkdirs() }
        file = File(directory, "theme.preferences_pb")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** Opens a store over the test's file, runs [block], and shuts it down again. */
    private fun <T> withStore(block: suspend (ThemeStore) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = themeCorruptionHandler(),
                scope = scope,
                produceFile = { file },
            )
            block(ThemeStore(store))
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    @Test
    fun theChosenThemeSurvivesAFreshRead() {
        withStore { it.setMode(ThemeMode.DARK) }
        assertEquals(ThemeMode.DARK, withStore { it.mode.first() })
    }

    @Test
    fun aDamagedFileIsReplacedRatherThanFailingForever() {
        withStore { it.setMode(ThemeMode.DARK) }

        // What an interrupted write leaves behind.
        file.writeBytes(ByteArray(64) { 0x7A })

        // Reading recovers to the default rather than throwing...
        assertEquals(ThemeMode.SYSTEM, withStore { it.mode.first() })

        // ...and, the half that used to be missing, writing works again afterwards.
        withStore { it.setMode(ThemeMode.LIGHT) }
        assertEquals(ThemeMode.LIGHT, withStore { it.mode.first() })
    }

    @Test
    fun aStoredModeThisVersionDoesNotKnowFallsBackToFollowingTheDevice() {
        // A downgrade, or a mode a later version added and this one has never heard of. An
        // unreadable *value* is not the same failure as an unreadable file and must not become
        // one: following the device is always a defensible answer.
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val raw = PreferenceDataStoreFactory.create(
                    corruptionHandler = themeCorruptionHandler(),
                    scope = scope,
                    produceFile = { file },
                )
                raw.updateData { prefs ->
                    prefs.toMutablePreferences().apply { set(stringPreferencesKey("mode"), "MIDNIGHT") }
                }
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
            }
        }

        assertEquals(ThemeMode.SYSTEM, withStore { it.mode.first() })
        // And it is still writable afterwards, which is the whole point of not throwing.
        withStore { it.setMode(ThemeMode.DARK) }
        assertEquals(ThemeMode.DARK, withStore { it.mode.first() })
    }
}
