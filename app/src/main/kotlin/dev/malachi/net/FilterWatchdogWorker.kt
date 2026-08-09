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
        val app = applicationContext as? MalachiApplication ?: return Result.success()
        val settings = runCatching { app.settingsStore.current() }.getOrNull() ?: return Result.success()

        if (!settings.isFiltering()) return Result.success()
        if (VpnStatus.status.value.tunnelUp) return Result.success()
        if (!VpnController.hasConsent(applicationContext)) {
            // Nothing to be done from here; the user has to grant it in the app.
            DebugLog.i(TAG, "watchdog: filtering is on but consent is missing")
            return Result.success()
        }

        DebugLog.w(TAG, "watchdog: the filter should be running and isn't; restarting it")
        VpnController.start(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "MalachiVpn"
        private const val PERIODIC = "malachi-filter-watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FilterWatchdogWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
