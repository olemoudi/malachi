package dev.malachi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.data.ThemeMode
import dev.malachi.net.VpnController
import dev.malachi.ui.MalachiApp
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.theme.MalachiTheme
import dev.malachi.ui.theme.resolvesToDark
import dev.malachi.update.UpdateWorker

class MainActivity : ComponentActivity() {

    private val vm: MalachiViewModel by viewModels {
        MalachiViewModel.Factory(application as MalachiApplication)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    /**
     * The VPN consent dialog. Android will only raise it from an activity, so turning the filter
     * on always comes back through here — and the setting is only committed once consent has
     * actually been granted, so a cancelled dialog leaves the switch telling the truth instead
     * of showing a filter that never started.
     */
    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) vm.confirmFilterEnabled() else vm.filterConsentRefused()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        val app = application as MalachiApplication

        setContent {
            val themeMode by app.themeStore.mode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            MalachiTheme(darkTheme = themeMode.resolvesToDark()) {
                MalachiApp(vm = vm, onRequestVpnConsent = ::requestVpnConsent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Catch up on updates whenever the app regains focus; throttled internally.
        UpdateWorker.runIfStale(this)
        // The only way to change any of the VPN environment is to leave for the system settings
        // and come back, so coming back is when what we show about it has gone stale.
        vm.refreshVpnEnvironment()
        // And if the filter is meant to be running and isn't, this is both the best moment to
        // notice — somebody is looking at the screen that claims it is coming up — and a context
        // the platform will let us start a service from.
        vm.ensureFilterRunning()
    }

    /**
     * Asks for consent if it isn't already held, then turns the filter on.
     *
     * The always-on check comes first, and only fires when the platform actually told us who
     * holds that slot. When another app holds it, Android refuses the handover and returns the
     * consent dialog as *cancelled* — the same result as the user pressing Cancel — so asking
     * would walk them through a dialog that cannot succeed and explain nothing. Where the
     * platform won't say (which is most devices now), we ask anyway and explain afterwards.
     */
    private fun requestVpnConsent() {
        if (VpnController.alwaysOn(this) is VpnController.AlwaysOn.Other) {
            vm.filterBlockedByAlwaysOn()
            return
        }
        val intent = VpnController.consentIntent(this)
        if (intent == null) {
            vm.confirmFilterEnabled()
            return
        }
        runCatching { vpnConsent.launch(intent) }.onFailure { vm.filterConsentRefused() }
    }

    /**
     * The filter runs as a foreground service, and on Android 13+ its ongoing notification needs
     * permission. Refusing doesn't stop the filter, but it hides the one surface that lets
     * someone pause it without opening the app, so it is worth asking for once.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
