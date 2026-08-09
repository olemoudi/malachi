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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide dependency container. Manual wiring — the graph is small enough to read. */
class MalachiApplication : Application() {

    val scope = CoroutineScope(SupervisorJob())

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

        // A subscribed list that was never downloaded is a filter that blocks nothing while
        // saying it is on. This covers the first run and any install whose files were cleared.
        scope.launch { runCatching { filterRepository.downloadMissingLists() } }

        scope.launch {
            val settings = settingsStore.current()
            ListUpdateWorker.schedule(this@MalachiApplication, settings.listUpdateHours, settings.listUpdateWifiOnly)
        }

        // Keep the app itself current. Deliberately the throttled variant: this process is
        // restarted by the system more often than a user opens the app, and an unconditional
        // check here would hit the network every time that happened.
        UpdateWorker.schedule(this)
        UpdateWorker.runIfStale(this)

        // Puts the filter back if something killed the process while it should have been
        // running. Does nothing at all in the normal case; see FilterWatchdogWorker.
        FilterWatchdogWorker.schedule(this)

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

        // The refresh schedule is a setting, so it has to be re-applied when it changes rather
        // than only at launch.
        scope.launch {
            settingsStore.settings
                .map { it.listUpdateHours to it.listUpdateWifiOnly }
                .distinctUntilChanged()
                .drop(1)
                .collect { (hours, wifiOnly) ->
                    ListUpdateWorker.schedule(this@MalachiApplication, hours, wifiOnly)
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
