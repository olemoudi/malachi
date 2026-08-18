package dev.malachi

import android.content.Context
import androidx.work.WorkManager
import dev.malachi.debug.DebugLog

/**
 * WorkManager, if this device will hand one over.
 *
 * `WorkManager.getInstance` is not the safe accessor it looks like. It throws when the
 * initialization provider never ran — which is what a ROM that strips or kills content providers
 * produces, and what an aggressive "app slimming" tool produces — and the calls underneath it
 * throw too: the platform's JobScheduler refuses an app more than a hundred jobs, and a damaged
 * WorkManager database surfaces as a SQLiteException from the enqueue.
 *
 * None of that is a reason for this app to stop. The periodic work here is a floor under things
 * that also happen by other means — the watchdog is duplicated by `Application.onCreate` and by
 * the home screen, the list refresh by the manual button, the update check by opening the app —
 * so a phone whose WorkManager will not start is a phone that filters and updates slightly less
 * promptly, not one that crashes on launch.
 *
 * Which is precisely what it used to do: [dev.malachi.update.UpdateWorker.schedule] is called
 * from `Application.onCreate`, so a throw there is an app that cannot be opened at all, on a
 * device nobody can reach to fix it.
 */
internal inline fun withWorkQueue(context: Context, what: String, block: (WorkManager) -> Unit) {
    runCatching { block(WorkManager.getInstance(context)) }
        .onFailure { DebugLog.w("MalachiWork", "could not $what on this device", it) }
}
