package dev.malachi.lists

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.malachi.MalachiApplication
import dev.malachi.withWorkQueue
import dev.malachi.debug.DebugLog
import java.util.concurrent.TimeUnit

/**
 * Keeps the subscribed lists current.
 *
 * The lists are the whole product: a domain added to a tracker network on Monday is one nobody
 * is protected from until their copy of the list catches up. They are also somebody else's
 * bandwidth, so the refresh is conditional (see [BlocklistStore]) and the schedule is the
 * user's — hourly for someone who cares, daily by default, Wi-Fi only unless they say otherwise.
 */
class ListUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MalachiApplication ?: return Result.failure()
        val forced = inputData.getBoolean(KEY_FORCE, false)
        return runCatching { app.filterRepository.refreshLists(force = forced) }
            .fold(
                onSuccess = { Result.success() },
                onFailure = {
                    DebugLog.w(TAG, "list refresh failed", it)
                    if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
                },
            )
    }

    companion object {
        private const val TAG = "MalachiLists"
        internal const val PERIODIC = "malachi-lists-periodic"
        private const val IMMEDIATE = "malachi-lists-now"
        private const val KEY_FORCE = "force"
        private const val MAX_RETRIES = 4

        /**
         * (Re)schedules the periodic refresh. REPLACE rather than KEEP because the interval and
         * the network constraint are settings: changing "every 24 hours, Wi-Fi only" to "every
         * 6 hours, any connection" has to take effect, and KEEP would silently ignore it.
         */
        fun schedule(context: Context, everyHours: Int, wifiOnly: Boolean) {
            val request = PeriodicWorkRequestBuilder<ListUpdateWorker>(
                everyHours.coerceIn(1, 24 * 7).toLong(), TimeUnit.HOURS,
            )
                .setConstraints(constraints(wifiOnly))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            withWorkQueue(context, "schedule the list refresh") {
                it.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
        }

        /**
         * A refresh now — the manual button, or the first run after a list was switched on.
         * [force] skips the conditional-request headers and recompiles from scratch.
         *
         * REPLACE keeps a user tapping "update now" from queueing five downloads of the same
         * twenty megabytes.
         */
        /**
         * Stops the periodic refresh. A blocklist exists to be consulted by a filter, so refreshing
         * twenty megabytes of them on a schedule while nothing is being filtered spends a wakeup,
         * a radio and somebody's data plan on a file nothing will read.
         */
        fun cancel(context: Context) {
            withWorkQueue(context, "stop the list refresh") { it.cancelUniqueWork(PERIODIC) }
        }

        fun runNow(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<ListUpdateWorker>()
                .setConstraints(constraints(wifiOnly = false))
                .setInputData(androidx.work.workDataOf(KEY_FORCE to force))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            withWorkQueue(context, "refresh the lists now") {
                it.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.REPLACE, request)
            }
        }

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }
}
