package dev.malachi.net

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/**
 * The one place that starts and stops the filter.
 *
 * Android will not let an app establish a tunnel without an explicit, one-time consent dialog,
 * and that dialog can only be raised from an activity. So starting the filter is two steps —
 * ask, then start — and everything that wants to turn it on goes through here rather than each
 * caller reinventing which half it needs.
 */
object VpnController {

    /**
     * The consent dialog to launch, or null when consent is already held. Null is the common
     * case: consent survives reboots and updates, and is only withdrawn by the user or by
     * another VPN app taking over.
     */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun hasConsent(context: Context): Boolean = consentIntent(context) == null

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, MalachiVpnService::class.java))
    }

    fun stop(context: Context) {
        // Deliberately not startForegroundService: the service's whole job here is to stop, and
        // asking the system to promote it first would be a promise to post a notification we are
        // about to cancel.
        runCatching {
            context.startService(
                Intent(context, MalachiVpnService::class.java).setAction(MalachiVpnService.ACTION_STOP),
            )
        }
    }
}
