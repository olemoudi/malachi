package dev.malachi.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the self-update machinery is doing right now, for the settings screen. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val installedVersionCode: Int) : UpdateUiState
    data class Downloading(val target: UpdateInfo) : UpdateUiState

    /** Waiting for the user to accept the system's install dialog. */
    data class PendingConfirmation(val target: UpdateInfo?) : UpdateUiState
    data class Failed(val step: String) : UpdateUiState

    /**
     * A check was asked for and declined without looking. Both of these used to report
     * "up to date", which is a claim about the world made without having looked at it — the
     * button said the app was current when nothing had been fetched.
     */
    data object AlreadyChecking : UpdateUiState
    data object SkippedMetered : UpdateUiState
}

/**
 * Process-wide update status. [Updater] and [InstallReceiver] write; the settings screen reads.
 * A plain singleton rather than an injected dependency, because checks fire from several entry
 * points (launch, the periodic worker, the manual button) and all of them should feed one line
 * of status rather than three disagreeing ones.
 */
object UpdateCenter {
    private val mutable = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = mutable

    internal fun report(state: UpdateUiState) {
        mutable.value = state
    }
}
