package dev.malachi.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dev.malachi.debug.DebugLog
import java.io.File

/**
 * Receives PackageInstaller status callbacks.
 *
 * When Malachi is its own installer of record the install is silent and lands as
 * STATUS_SUCCESS. Otherwise the system asks for confirmation, which arrives here as
 * STATUS_PENDING_USER_ACTION with an intent to launch. Launching it directly only works while
 * the app is in the foreground — background activity starts have been blocked since Android 10 —
 * so a tappable notification is posted as well. That is what makes the update reliable when the
 * check ran from the periodic worker.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        DebugLog.i(TAG, "install status=$status message=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                UpdateCenter.report(UpdateUiState.PendingConfirmation(target = null))
                // Guarded, and the order matters: this used to be able to throw — a channel the
                // system would not create, a notification an OEM's own rules refused — and take
                // the direct launch below with it. The two are alternatives, not a sequence:
                // whichever of them works is the one that lets the update finish.
                runCatching { UpdateNotifications.notifyConfirmationNeeded(context, Intent(confirm)) }
                    .onFailure { DebugLog.w(TAG, "could not post the confirmation notification", it) }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // A self-update normally restarts the process before this runs; tidy up if not.
                UpdateNotifications.cancel(context)
                UpdateCenter.report(UpdateUiState.Idle)
                discardApk(context)
            }
            else -> {
                UpdateNotifications.cancel(context)
                UpdateCenter.report(UpdateUiState.Failed("install status $status${message?.let { ": $it" } ?: ""}"))
                discardApk(context)
            }
        }
    }

    /**
     * Drops the downloaded APK once the install reached a terminal state: it is tens of
     * megabytes sitting in the cache, and the next check would download it again anyway.
     */
    private fun discardApk(context: Context) {
        runCatching { File(context.cacheDir, Updater.APK_FILE).delete() }
    }

    companion object {
        const val ACTION = "dev.malachi.update.INSTALL_STATUS"
        private const val TAG = "MalachiUpdater"
    }
}
