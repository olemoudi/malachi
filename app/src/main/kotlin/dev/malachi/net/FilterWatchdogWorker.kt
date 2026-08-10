package dev.malachi.net

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.malachi.MalachiApplication
import dev.malachi.debug.DebugLog
import java.util.concurrent.TimeUnit

/**
 * Puts the filter back if the process was killed while it should have been running.
 *
 * Malachi posts no ongoing notification, which is what the user asked for and is reasonable
 * while the tunnel is up: the platform binds to the active VPN and will not reclaim the process.
 * The gap is what happens *after* something kills it anyway — an aggressive vendor battery
 * manager, a hard low-memory kill. START_STICKY did not bring it back in testing, and without a
 * foreground notification there is nothing else holding the process, so the filter would stay
 * off until somebody happened to open the app.
 *
 * So: a periodic check that does nothing at all in the normal case. It reads one setting, sees
 * the tunnel is up, and returns. WorkManager batches it with whatever else the system was
 * already waking for, which is the cheapest form a recurring check can take — and the interval
 * is deliberately half an hour rather than the fifteen-minute floor, because the cost of
 * noticing late is a few minutes of unfiltered ads, not a broken phone.
 *
 * Setting Malachi as the always-on VPN makes this redundant: the system then restarts the
 * tunnel itself, immediately. That is why the app asks.
 */
class FilterWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        restoreIfNeeded(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "MalachiVpn"
        internal const val PERIODIC = "malachi-filter-watchdog"

        /**
         * Restarts the filter if it should be running and isn't.
         *
         * Called both from the periodic check and from `Application.onCreate`, and the second
         * one matters more: *anything* that revives this process — a worker, a broadcast, the
         * user opening the app — passes through there, so recovery usually happens at the first
         * sign of life rather than at the next half-hour boundary. The periodic job is the floor
         * for a phone where nothing else wakes the app at all.
         */
        suspend fun restoreIfNeeded(context: Context) {
            val app = context.applicationContext as? MalachiApplication ?: return
            val settings = runCatching { app.settingsStore.current() }.getOrNull() ?: return

            if (!settings.isFiltering()) return
            if (VpnStatus.status.value.tunnelUp) return
            if (!VpnController.hasConsent(context)) {
                // Nothing to be done from here; the user has to grant it in the app.
                DebugLog.i(TAG, "watchdog: filtering is on but VPN consent is missing")
                return
            }
            DebugLog.w(TAG, "watchdog: the filter should be running and isn't; restarting it")
            VpnController.start(context)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FilterWatchdogWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
