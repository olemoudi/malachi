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
        /** Internal so a test can ask WorkManager whether the periodic check is really there. */
        internal const val PERIODIC = "malachi-update-periodic"
        private const val MAX_RETRIES = 5

        /** How often to look, when the system lets us. */
        internal const val PERIOD_HOURS = 12L

        /** Minimum spacing between focus-triggered checks, so app-switching doesn't hammer GitHub. */
        private const val FOCUS_GUARD_MILLIS = 15 * 60 * 1000L

        private val lastEnqueueMs = AtomicLong(0)
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * The periodic check, re-declared on every process start.
         *
         * This is what makes an app that is never opened still update itself: WorkManager keeps
         * the request in its own database and the system runs it, so the only thing needed is a
         * process, and the tunnel is one. A user who never taps the icon still gets fixes.
         *
         * UPDATE rather than KEEP, so that changing the interval or the constraints in a future
         * version actually takes effect. KEEP silently honours whatever an old install
         * registered, which for a self-updating app means a schedule nobody can ever correct.
         *
         * Twelve hours is a floor, not a promise: the system batches this work and defers it in
         * Doze, so an idle phone may check less often. That is the right trade for an update
         * that is never urgent — and [runIfStale] covers the case where somebody does open the
         * app in between.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
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
