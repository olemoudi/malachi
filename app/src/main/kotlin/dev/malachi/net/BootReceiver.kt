package dev.malachi.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.malachi.MalachiApplication
import dev.malachi.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Brings the filter back after a reboot and after Malachi updates itself.
 *
 * Both matter, and the second more than the first: a self-updating app replaces its own process,
 * and without this the filter would silently stay off until the user next opened the app —
 * turning every update into a window of unfiltered browsing that nothing announces.
 *
 * VPN consent survives both events, so no dialog is needed; if it was withdrawn, the service
 * reports that instead of failing quietly.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext as? MalachiApplication ?: return
        val pending = goAsync()
        app.scope.launch(Dispatchers.IO) {
            try {
                val settings = app.settingsStore.current()
                if (!settings.filteringEnabled) return@launch
                DebugLog.i(TAG, "restarting the filter after $action")
                VpnController.start(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MalachiVpn"
    }
}
