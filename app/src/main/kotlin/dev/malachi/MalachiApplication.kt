package dev.malachi

import android.app.Application
import dev.malachi.data.AppInventory
import dev.malachi.data.SettingsStore
import dev.malachi.data.ThemeStore
import dev.malachi.debug.DebugLog
import dev.malachi.filter.FilterRepository
import dev.malachi.lists.BlocklistStore
import dev.malachi.lists.ListUpdateWorker
import dev.malachi.net.FilterWatchdogWorker
import dev.malachi.net.VpnController
import dev.malachi.stats.StatsStore
import dev.malachi.update.Updater
import dev.malachi.update.UpdateWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide dependency container. Manual wiring — the graph is small enough to read. */
class MalachiApplication : Application() {

    /**
     * The application-wide scope, with a handler that turns a stray exception into a log line.
     *
     * Without one, an uncaught exception in *any* coroutine launched here reaches the default
     * thread handler and takes the process down — a supervisor job stops siblings being
     * cancelled, it does not stop the crash. For a process expected to stay up for months,
     * every background task is a chance to be wrong once, and being wrong once should not cost
     * the whole filter.
     */
    val scope = CoroutineScope(
        SupervisorJob() +
            CoroutineExceptionHandler { _, error -> DebugLog.e(TAG, "background task failed", error) },
    )

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var themeStore: ThemeStore
        private set
    lateinit var appInventory: AppInventory
        private set
    lateinit var blocklistStore: BlocklistStore
        private set
    lateinit var filterRepository: FilterRepository
        private set
    lateinit var statsStore: StatsStore
        private set

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)

        settingsStore = SettingsStore(this)
        themeStore = ThemeStore(this)
        appInventory = AppInventory(this)
        blocklistStore = BlocklistStore(this)
        filterRepository = FilterRepository(settingsStore, blocklistStore, scope)
        statsStore = StatsStore(this)

        pruneStorage()

        // Corrections to what a past version wrote into the user's settings. A field default
        // only ever reaches a fresh install; this reaches the ones that already exist.
        scope.launch {
            runCatching { settingsStore.update { it.migrated() } }
                .onFailure { DebugLog.w(TAG, "could not migrate the stored settings", it) }
        }

        // A subscribed list that was never downloaded is a filter that blocks nothing while
        // saying it is on. This covers the first run and any install whose files were cleared.
        scope.launch { runCatching { filterRepository.downloadMissingLists() } }

        // Keep the app itself current. The periodic job is what updates a phone whose owner
        // never opens the app; the one-off check on launch belongs to the activity, which knows
        // that somebody is actually there. Asking here as well meant a network request every
        // time the *system* revived this process — a worker, a broadcast, a Doze cycle — which
        // is many times more often than anyone opens anything.
        UpdateWorker.schedule(this)
        // Whatever brought this process back — a worker, a broadcast, the launcher — is also
        // the earliest moment we can notice the filter is missing, so we look now rather than
        // waiting for the periodic check to come round.
        scope.launch { FilterWatchdogWorker.restoreIfNeeded(this@MalachiApplication) }

        observeFilterSwitch()
    }

    /**
     * Throws away anything on disk that has outlived its purpose.
     *
     * This app is meant to run for months without being opened, and the failure mode of that is
     * not a crash — it is a phone that quietly has a gigabyte less free space than it did. The
     * standing rule is that every file Malachi writes has a bound: the blocklists are pruned
     * against the subscribed set, the debug log and the statistics are capped by construction,
     * and the only genuinely large file — a downloaded APK — is cleared here.
     *
     * The update APK normally deletes itself when the install reaches a terminal state, but a
     * successful self-update restarts the process before that receiver runs, so roughly every
     * update leaves forty-odd megabytes behind. A day is long enough for any install that was
     * genuinely in flight, and the file is re-downloadable in any case.
     */
    private fun pruneStorage() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val apk = java.io.File(cacheDir, Updater.APK_FILE)
                val age = System.currentTimeMillis() - apk.lastModified()
                if (apk.exists() && age > STALE_APK_MILLIS) {
                    DebugLog.i(TAG, "removing a stale update download (${apk.length()} bytes)")
                    apk.delete()
                }
            }.onFailure { DebugLog.w(TAG, "could not prune the cache", it) }
        }
    }

    /**
     * Starts and stops the tunnel service when the *intent* to filter changes.
     *
     * `drop(1)` skips the first emission on purpose: that one is just the stored value being
     * read at process start, and acting on it would be a background foreground-service start —
     * which Android refuses, and punishes. The service is started deliberately instead, by the
     * screen the user touched or by [dev.malachi.net.BootReceiver] in a context that allows it.
     */
    private fun observeFilterSwitch() {
        scope.launch {
            settingsStore.settings
                .map { it.filteringEnabled }
                .distinctUntilChanged()
                .drop(1)
                .collect { enabled ->
                    if (enabled) {
                        runCatching { VpnController.start(this@MalachiApplication) }
                            .onFailure { DebugLog.e(TAG, "could not start the filter service", it) }
                    } else {
                        VpnController.stop(this@MalachiApplication)
                    }
                }
        }

        // Everything periodic this app asks the system for, applied from the settings rather than
        // declared once at startup — and never dropping the first value, because that first value
        // is what registers the work on a fresh process and cancels it on a phone that has since
        // switched filtering off.
        //
        // Both of these earn their wakeups only while the filter is meant to be running: a
        // watchdog looking for a filter nobody asked for finds nothing every half hour forever,
        // and a blocklist refreshed for a filter that is off is twenty megabytes nothing will
        // read, on somebody's data plan.
        scope.launch {
            settingsStore.settings
                .map { Triple(it.filteringEnabled, it.listUpdateHours, it.listUpdateWifiOnly) }
                .distinctUntilChanged()
                .collect { (filtering, hours, wifiOnly) ->
                    if (filtering) {
                        ListUpdateWorker.schedule(this@MalachiApplication, hours, wifiOnly)
                        FilterWatchdogWorker.schedule(this@MalachiApplication)
                    } else {
                        ListUpdateWorker.cancel(this@MalachiApplication)
                        FilterWatchdogWorker.cancel(this@MalachiApplication)
                    }
                }
        }

        // Switching a list on has to actually fetch it; nothing else would.
        scope.launch {
            settingsStore.settings
                .map { it.listChoices }
                .distinctUntilChanged()
                .drop(1)
                .collect { runCatching { filterRepository.downloadMissingLists() } }
        }
    }

    private companion object {
        const val TAG = "Malachi"

        /** How long a downloaded APK may sit in the cache before it is assumed abandoned. */
        const val STALE_APK_MILLIS = 24 * 60 * 60 * 1000L
    }
}
