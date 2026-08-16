package dev.malachi.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.malachi.debug.DebugLog

/**
 * The one place that starts and stops the filter, and the one place that knows what can stand
 * in the way of it starting.
 *
 * Android allows exactly one VPN at a time and will not let an app establish a tunnel without
 * an explicit consent dialog that only an activity can raise. Two things can make that dialog a
 * dead end, and both are invisible from inside it: another app already holds the tunnel, or —
 * worse — another app is configured as the device's *always-on* VPN, in which case the system
 * refuses the handover outright and the dialog returns a cancel that looks exactly like the
 * user having pressed cancel. Naming that difference is most of this file.
 */
object VpnController {

    private const val TAG = "MalachiVpn"

    /** Secure settings key naming the package configured as the always-on VPN, if any. */
    private const val ALWAYS_ON_VPN_APP = "always_on_vpn_app"

    /**
     * The consent dialog to launch, or null when consent is already held. Null is the common
     * case: consent survives reboots and updates, and is only withdrawn by the user or by
     * another VPN app taking over.
     */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun hasConsent(context: Context): Boolean = runCatching { consentIntent(context) == null }.getOrDefault(false)

    /**
     * Who owns the device's always-on VPN slot — as far as we are allowed to know.
     *
     * [Unknown] is the normal answer on a current Android. The setting that holds this is
     * readable by the system and by nobody else, and it was tempting to read it anyway and treat
     * the null as "nobody has it": that produces an app which tells a user who has *already*
     * configured always-on that they haven't, forever. A state we cannot observe is modelled as
     * one we cannot observe.
     */
    sealed interface AlwaysOn {
        data object Unknown : AlwaysOn
        data object None : AlwaysOn
        data object Malachi : AlwaysOn
        data class Other(val packageName: String) : AlwaysOn
    }

    fun alwaysOn(context: Context): AlwaysOn {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, ALWAYS_ON_VPN_APP)
        }.getOrNull() ?: return AlwaysOn.Unknown
        return when {
            raw.isBlank() -> AlwaysOn.None
            raw == context.packageName -> AlwaysOn.Malachi
            else -> AlwaysOn.Other(raw)
        }
    }

    /**
     * Whether some other app currently holds a VPN.
     *
     * Unlike the always-on setting this really is observable: a VPN shows up as a network with
     * the VPN transport, and ours is only there while our own tunnel is up. It is what turns
     * "the permission was refused" into "something else is holding the one tunnel Android has".
     */
    @Suppress("DEPRECATION") // No one-shot replacement exists; the alternative is a callback we
    // would have to keep registered for the life of the app to answer a question asked twice.
    fun anotherVpnActive(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)

    /**
     * The system's VPN settings, where always-on is turned on and another app's hold is
     * released. There is no way to set always-on programmatically without being a device owner,
     * so the honest thing is to take the user straight to the screen that can.
     */
    fun openVpnSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrElse {
        DebugLog.w(TAG, "no VPN settings screen on this device", it)
        // Every device has the top-level settings app even when it hides the VPN page.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * The system screen where Private DNS is turned off.
     *
     * `android.settings.PRIVATE_DNS_SETTINGS` is not in the SDK and does not resolve everywhere —
     * it is absent on a current AOSP build, checked rather than assumed — so it is tried first and
     * the network dashboard, which is the screen that actually contains the Private DNS entry, is
     * the fallback. A button that lands two taps away beats one that lands nowhere.
     */
    fun openPrivateDnsSettings(context: Context): Boolean {
        val destinations = listOf(
            "android.settings.PRIVATE_DNS_SETTINGS",
            Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (action in destinations) {
            val opened = runCatching {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (opened) return true
        }
        DebugLog.w(TAG, "no settings screen would open for Private DNS")
        return false
    }

    /**
     * Android's own page for one app — where its battery and data use are.
     *
     * Malachi is asked "is this app draining my battery" and cannot answer it: a DNS lookup is
     * not a measurement of power, and a number invented from one would be a confident lie. What
     * this app can say is how much an app *talks*, and then hand over to the part of the system
     * that really does keep the other figures.
     */
    fun openAppInfo(context: Context, packageName: String): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrElse {
        DebugLog.w(TAG, "no app info screen for $packageName", it)
        false
    }

    /**
     * Launches another app, for the guided search's "go and make it fail" step.
     *
     * Best-effort by nature: plenty of the apps this filter covers have no launcher entry at all —
     * a sync agent, a preinstalled service — and there is nothing to open. The caller is expected
     * to leave a way forward that does not depend on it.
     */
    fun openApp(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse {
        DebugLog.w(TAG, "could not launch $packageName", it)
        false
    }

    /**
     * Starts the filter, preferring the way that costs the user nothing.
     *
     * A plain service start first, because `startForegroundService` is a promise to post a
     * notification within five seconds that the system enforces with a crash — and there is no
     * notification to post while filtering. Most callers are somewhere that is allowed: the
     * screen the user just touched, or inside a receiver's `onReceive`.
     *
     * The exception is the watchdog, which by definition runs in the background and would be
     * refused. That path falls back to a foreground start and pays for a notification that is
     * withdrawn the moment the tunnel is up.
     */
    fun start(context: Context): Boolean {
        val intent = Intent(context, MalachiVpnService::class.java)
        runCatching {
            context.startService(intent)
            return true
        }
        // Refused because we are in the background — which happens exactly once, on the recovery
        // path, when the watchdog finds the filter dead. The platform will only allow a start
        // from here if we promise a notification, so we promise one and take it straight back
        // down as soon as the tunnel is up (see MalachiVpnService.demote).
        return runCatching {
            ContextCompat.startForegroundService(
                context,
                intent.putExtra(MalachiVpnService.EXTRA_TRANSIENT_FOREGROUND, true),
            )
            true
        }.getOrElse {
            DebugLog.w(TAG, "could not start the filter service from here", it)
            false
        }
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
