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

    /**
     * What the chosen channel last said it had, whether or not it was newer than what is running.
     *
     * Separate from [state] because it answers a different question and outlives the answer to
     * the first: the settings screen needs to say "the stable channel is on 1.0.0-beta" while
     * this phone sits on a test build that is ahead of it, and there is no update in flight to
     * carry that fact. Null until a manifest has actually been read — the screen must be able to
     * tell "behind you" from "not asked yet".
     */
    private val offered = MutableStateFlow<UpdateInfo?>(null)
    val channelOffer: StateFlow<UpdateInfo?> = offered

    internal fun report(state: UpdateUiState) {
        mutable.value = state
    }

    internal fun channelOffers(info: UpdateInfo) {
        offered.value = info
    }

    /** Forgotten when the channel changes: it described the other one. */
    internal fun forgetChannelOffer() {
        offered.value = null
    }
}
