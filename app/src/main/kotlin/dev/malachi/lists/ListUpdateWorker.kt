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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.malachi.MalachiApplication
import dev.malachi.withWorkQueue
import dev.malachi.debug.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
         * Stops the periodic refresh. A blocklist exists to be consulted by a filter, so refreshing
         * twenty megabytes of them on a schedule while nothing is being filtered spends a wakeup,
         * a radio and somebody's data plan on a file nothing will read.
         */
        fun cancel(context: Context) {
            withWorkQueue(context, "stop the list refresh") { it.cancelUniqueWork(PERIODIC) }
        }

        /**
         * A refresh now, for the manual button. Conditional like the periodic one; [force] skips
         * the validators and recompiles from scratch, and nothing offers that today.
         *
         * REPLACE keeps a user tapping "update now" from queueing five downloads of the same
         * twenty megabytes.
         */
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

        /**
         * True while the one-off refresh is enqueued or running — waiting for a connection, in
         * practice. Read from WorkManager itself rather than from a flag of ours, because a flag
         * set on the tap would stay set on a device whose WorkManager never runs anything.
         */
        fun queued(context: Context): Flow<Boolean> {
            val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return flowOf(false)
            return manager.getWorkInfosForUniqueWorkFlow(IMMEDIATE).map { infos -> infos.any { !it.state.isFinished } }
        }

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }
}
