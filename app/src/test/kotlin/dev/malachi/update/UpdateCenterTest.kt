package dev.malachi.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * One line of status, written by three entry points that do not know about each other.
 *
 * Checks fire from app launch, from the twelve-hourly worker and from the manual button, and the
 * screen shows whichever of them spoke last. That is right for results and wrong for refusals:
 * a check that declined to run knows nothing about the one that is running.
 */
class UpdateCenterTest {

    private val offer = UpdateInfo(versionCode = 49, versionName = "1.4.1-alpha", apk = "https://example.test/a.apk")

    @BeforeEach
    fun idle() = UpdateCenter.report(UpdateUiState.Idle)

    @Test
    fun `a refusal does not erase a download that is in flight`() {
        // The reported shape: a manual check is downloading, the app regains focus, the worker
        // fires and is turned away — and the screen stopped naming the version that was coming.
        // A download reports nothing further unless it fails, so that is where it stayed.
        UpdateCenter.report(UpdateUiState.Downloading(offer))

        UpdateCenter.reportDeclined(UpdateUiState.AlreadyChecking)

        assertEquals(UpdateUiState.Downloading(offer), UpdateCenter.state.value)
    }

    @Test
    fun `nor one waiting for the user to accept the install`() {
        UpdateCenter.report(UpdateUiState.PendingConfirmation(target = null))

        UpdateCenter.reportDeclined(UpdateUiState.SkippedMetered)

        assertEquals(UpdateUiState.PendingConfirmation(target = null), UpdateCenter.state.value)
    }

    @Test
    fun `but a refusal is still said when nothing is happening`() {
        // The other half, and the reason these states exist at all: both used to report
        // "up to date", which is a claim about the world made without having looked at it.
        UpdateCenter.reportDeclined(UpdateUiState.SkippedMetered)
        assertEquals(UpdateUiState.SkippedMetered, UpdateCenter.state.value)

        UpdateCenter.report(UpdateUiState.Checking)
        UpdateCenter.reportDeclined(UpdateUiState.AlreadyChecking)
        assertEquals(
            UpdateUiState.AlreadyChecking,
            UpdateCenter.state.value,
            "a bare 'checking' says nothing about whose check it is",
        )
    }

    @Test
    fun `a finished check is replaceable by a refusal`() {
        UpdateCenter.report(UpdateUiState.UpToDate(48))

        UpdateCenter.reportDeclined(UpdateUiState.SkippedMetered)

        assertEquals(UpdateUiState.SkippedMetered, UpdateCenter.state.value)
    }
}
