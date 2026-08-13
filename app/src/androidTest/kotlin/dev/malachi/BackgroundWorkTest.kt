package dev.malachi

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.malachi.lists.ListUpdateWorker
import dev.malachi.net.FilterWatchdogWorker
import dev.malachi.update.UpdateWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The three things that have to happen to a phone whose owner never opens the app.
 *
 * Malachi is not a thing people look at. It filters for months, and everything that keeps it
 * working in that time is background work: fetching the lists it filters with, fetching its own
 * fixes, and noticing that the filter has stopped. All three are declared in
 * `Application.onCreate`, so this asserts that the declaration actually reached WorkManager —
 * a schedule that silently failed to register looks exactly like one that is working.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundWorkTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun scheduled(uniqueName: String): List<WorkInfo> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)

    private fun assertPending(uniqueName: String) {
        val work = scheduled(uniqueName)
        assertTrue("$uniqueName was never scheduled", work.isNotEmpty())
        assertTrue(
            "$uniqueName is scheduled but finished: ${work.map { it.state }}",
            work.any { !it.state.isFinished },
        )
    }

    @Test
    fun theUpdateCheckIsScheduledWithoutAnybodyOpeningTheApp() {
        // The app was launched by the test runner, not by a person, and this is the state a
        // phone is in for weeks at a time: process alive because of the tunnel, nobody looking.
        assertPending(UpdateWorker.PERIODIC)
    }

    @Test
    fun theWorkThatOnlyMattersWhileFilteringComesAndGoesWithIt() {
        // Both of these used to be declared unconditionally at every process start, which on a
        // phone with filtering switched off is a wakeup every half hour to read a setting that
        // says no, and a twenty-megabyte list refresh for a filter that will not consult it.
        // The watchdog is still the floor under every other recovery path — but only while
        // there is something to recover.
        val store = (app as MalachiApplication).settingsStore
        val before = runBlocking { store.current() }
        try {
            runBlocking { store.update { it.copy(filteringEnabled = false) } }
            awaitGone(ListUpdateWorker.PERIODIC)
            awaitGone(FilterWatchdogWorker.PERIODIC)

            runBlocking { store.update { it.copy(filteringEnabled = true) } }
            awaitPending(ListUpdateWorker.PERIODIC)
            awaitPending(FilterWatchdogWorker.PERIODIC)
        } finally {
            runBlocking { store.update { before } }
        }
    }

    /** Polls, because the schedule is applied by a collector on the application's own scope. */
    private fun awaitPending(uniqueName: String) = awaitState(uniqueName, wanted = true)

    private fun awaitGone(uniqueName: String) = awaitState(uniqueName, wanted = false)

    private fun awaitState(uniqueName: String, wanted: Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val pending = scheduled(uniqueName).any { !it.state.isFinished }
            if (pending == wanted) return
            Thread.sleep(250)
        }
        val states = scheduled(uniqueName).map { it.state }
        throw AssertionError(
            "$uniqueName should ${if (wanted) "be" else "not be"} scheduled while filtering is " +
                "${if (wanted) "on" else "off"}; states were $states",
        )
    }

    // There is deliberately no test here that calls schedule() itself. Enqueuing periodic work
    // runs it almost at once on a fresh install, and an update check and a twenty-megabyte list
    // download are enough load on an emulator to make the tunnel's own tests time out — a test
    // that breaks its neighbours is worse than the coverage it adds. That UPDATE rather than
    // KEEP is used is a one-line claim best read in UpdateWorker.schedule.
}
