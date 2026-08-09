package dev.malachi.update

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Runs the update check off the main thread, retrying transient failures with backoff. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        when (Updater(applicationContext).checkAndUpdate()) {
            UpdateCheckOutcome.TRANSIENT_FAILURE ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            else -> Result.success()
        }

    companion object {
        private const val PERIODIC = "malachi-update-periodic"
        private const val MAX_RETRIES = 5

        /** Minimum spacing between focus-triggered checks, so app-switching doesn't hammer GitHub. */
        private const val FOCUS_GUARD_MILLIS = 15 * 60 * 1000L

        private val lastEnqueueMs = AtomicLong(0)
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Idempotent periodic check (~ every 12h). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One-off immediate check (launch, boot, the manual button). Bypasses the guard. */
        fun runNow(context: Context) {
            lastEnqueueMs.set(SystemClock.elapsedRealtime())
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        /** Focus-triggered check: runs at most once per guard window. */
        fun runIfStale(context: Context) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val last = lastEnqueueMs.get()
                if (last != 0L && now - last < FOCUS_GUARD_MILLIS) return
                if (lastEnqueueMs.compareAndSet(last, now)) break
            }
            runNow(context)
        }
    }
}
