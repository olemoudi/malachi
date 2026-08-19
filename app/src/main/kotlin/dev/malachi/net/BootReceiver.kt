package dev.malachi.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.malachi.debug.DebugLog
import dev.malachi.update.UpdateNotifications
import dev.malachi.update.Updater

/**
 * Brings the filter back after a reboot and after Malachi updates itself.
 *
 * Both matter, and the second more than the first: a self-updating app replaces its own process,
 * and without this the filter would silently stay off until the user next opened the app —
 * turning every update into a window of unfiltered browsing that nothing announces.
 *
 * The service is started unconditionally and works out for itself whether it should be running:
 * reading that from settings first would mean suspending, and a service started after
 * `onReceive` has returned is a background start the platform refuses. The service reads the
 * same settings on create and stops itself if filtering is off, which costs one process start
 * per boot and is the difference between this working and this being a race.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        DebugLog.i(TAG, "restoring the filter after $action")
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) clearUpAfterSelfUpdate(context)
        VpnController.start(context)
    }

    /**
     * What a silent self-update leaves behind, because the receiver meant to clear it never ran.
     *
     * [dev.malachi.update.InstallReceiver] drops the downloaded APK and withdraws the "an update
     * is available" notification when the install reports success — and a successful silent
     * install replaces this process first, so in the ordinary case neither happens. The result is
     * tens of megabytes in the cache until the day-old sweep collects them, and a notification
     * announcing a version that is now the one running. This broadcast is the one thing every
     * self-update is guaranteed to produce, and by the time it arrives the install is a fact.
     */
    private fun clearUpAfterSelfUpdate(context: Context) {
        UpdateNotifications.cancel(context)
        Updater.discardDownload(context)
    }

    private companion object {
        const val TAG = "MalachiVpn"
    }
}
