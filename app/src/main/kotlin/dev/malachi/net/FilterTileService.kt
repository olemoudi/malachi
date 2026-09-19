package dev.malachi.net

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.malachi.MainActivity
import dev.malachi.MalachiApplication
import dev.malachi.R
import dev.malachi.data.MalachiSettings
import dev.malachi.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The filter's switch in the quick-settings panel.
 *
 * A tap does what the switch on the home screen does, from wherever the phone is: on, off, or —
 * while paused — back on. A long press opens the app (the activity declares
 * `QS_TILE_PREFERENCES`), which is where pausing for a chosen length lives.
 *
 * **It costs nothing while the panel is closed.** Not an active tile: the system binds it only
 * while the panel is showing, and the only work it does then is to watch two flows that already
 * exist, so the tile can change under a finger when the filter comes up or goes down.
 *
 * **Consent is the one thing it cannot get by itself.** Android shows the VPN dialog only over an
 * activity, so a tap with no consent held opens the app and asks there, exactly as the switch
 * would. Everything else happens without leaving the shade.
 */
class FilterTileService : TileService() {

    private var watching: Job? = null

    private val app: MalachiApplication get() = application as MalachiApplication

    override fun onStartListening() {
        super.onStartListening()
        watching?.cancel()
        watching = app.scope.launch(Dispatchers.Main) {
            combine(app.settingsStore.settings, VpnStatus.status) { settings, status -> settings to status.tunnelUp }
                .collect { (settings, tunnelUp) -> render(settings, tunnelUp) }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        app.scope.launch(Dispatchers.Main) {
            val settings = app.settingsStore.current()
            when {
                settings.filteringEnabled && settings.isPaused() ->
                    app.settingsStore.update { it.copy(pausedUntilMs = 0) }
                settings.filteringEnabled ->
                    app.settingsStore.update { it.copy(filteringEnabled = false, pausedUntilMs = 0) }
                VpnController.hasConsent(this@FilterTileService) -> {
                    VpnStatus.starting()
                    app.settingsStore.update { it.copy(filteringEnabled = true, pausedUntilMs = 0) }
                    // Started here as well as by the settings observer: this is a moment the
                    // platform will let a service start, and the observer may not reach it first.
                    VpnController.start(this@FilterTileService)
                }
                else -> openForConsent()
            }
        }
    }

    private fun openForConsent() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_TURN_ON)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { DebugLog.w(TAG, "could not open the app from the tile", it) }
    }

    private fun render(settings: MalachiSettings, tunnelUp: Boolean) {
        val tile = qsTile ?: return
        val paused = settings.filteringEnabled && settings.isPaused()
        tile.state = if (settings.filteringEnabled && !paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shield)
        tile.subtitle = when {
            paused -> getString(
                R.string.tile_paused_until,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(settings.pausedUntilMs)),
            )
            settings.filteringEnabled && tunnelUp -> getString(R.string.tile_on)
            settings.filteringEnabled -> getString(R.string.tile_starting)
            else -> getString(R.string.tile_off)
        }
        tile.updateTile()
    }

    private companion object {
        const val TAG = "MalachiTile"
    }
}
