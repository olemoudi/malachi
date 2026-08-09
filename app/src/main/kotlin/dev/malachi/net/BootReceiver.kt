package dev.malachi.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.malachi.debug.DebugLog

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
        VpnController.start(context)
    }

    private companion object {
        const val TAG = "MalachiVpn"
    }
}
