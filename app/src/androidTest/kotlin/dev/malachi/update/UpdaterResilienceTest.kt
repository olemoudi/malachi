package dev.malachi.update

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.malachi.data.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The self-update path, against a real server, on a device.
 *
 * This is the one piece of Malachi that cannot be fixed remotely if it breaks: an app outside a
 * store has no one to push it anything, so a check that throws where nobody catches it does not
 * cost one update, it costs every future one on a phone nobody can reach. It had no test at all
 * beyond its pure helpers, which is the wrong shape of coverage for the riskiest code here.
 *
 * What is pinned: a transient failure is retried rather than surfaced, an unreachable server ends
 * as a retryable outcome instead of an exception, and nothing that goes wrong escapes as a throw.
 */
@RunWith(AndroidJUnit4::class)
class UpdaterResilienceTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private val hits = AtomicInteger(0)

    /** Responses to serve in order; the last one repeats once the list runs out. */
    private var script: List<MockResponse> = emptyList()

    @Before
    fun start() {
        hits.set(0)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val index = hits.getAndIncrement()
                return script.getOrElse(index) { script.lastOrNull() ?: MockResponse().setResponseCode(404) }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
        UpdateCenter.report(UpdateUiState.Idle)
    }

    // The channel is stated rather than read off the device: every case here is about what the
    // updater does with what it fetched, and a test whose answer depends on a setting left behind
    // by another test is a test that reports the wrong thing on the day it fails.
    private fun updater(channel: UpdateChannel = UpdateChannel.STABLE) = Updater(
        app,
        versionJsonUrl = server.url("/version.json").toString(),
        channelOverride = channel,
    )

    private fun installedVersionCode(): Int {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return info.longVersionCode.toInt()
    }

    // -------------------------------------------------------------------------------------

    @Test
    fun aServerThatFailsOnceIsRetriedRatherThanReported() = runBlocking {
        // Exactly the failure that was reported from a phone: one blip, and the screen said the
        // update had failed. The second go succeeds and the user never learns there was a first.
        script = listOf(
            MockResponse().setResponseCode(503),
            MockResponse().setBody("""{"versionCode":1,"versionName":"0.0.1","apk":""}"""),
        )

        val outcome = updater().checkAndUpdate(force = true)

        assertEquals(UpdateCheckOutcome.UP_TO_DATE, outcome)
        assertEquals("the fetch was not retried", 2, hits.get())
    }

    @Test
    fun aServerThatKeepsFailingEndsAsRetryableRatherThanAsAThrow() = runBlocking {
        script = listOf(MockResponse().setResponseCode(500))

        val outcome = updater().checkAndUpdate(force = true)

        assertEquals(UpdateCheckOutcome.TRANSIENT_FAILURE, outcome)
        assertTrue("every attempt should have been made", hits.get() >= 3)
        assertTrue(UpdateCenter.state.value is UpdateUiState.Failed)
    }

    @Test
    fun garbageInsteadOfJsonIsNotAnUpdateAndNotACrash() = runBlocking {
        // A captive portal answers everything with a login page and a 200.
        script = listOf(MockResponse().setBody("<html><body>Sign in to continue</body></html>"))

        val outcome = updater().checkAndUpdate(force = true)

        assertEquals(UpdateCheckOutcome.TRANSIENT_FAILURE, outcome)
    }

    @Test
    fun aDeadServerIsAnOutcomeRatherThanAnException() = runBlocking {
        // Nothing listening at all: the connection is refused rather than answered. Whatever the
        // socket layer throws must not cross out of the check.
        server.shutdown()

        val outcome = updater().checkAndUpdate(force = true)

        assertEquals(UpdateCheckOutcome.TRANSIENT_FAILURE, outcome)
    }

    @Test
    fun anApkUrlPointingSomewhereElseIsRefusedBeforeAnythingIsDownloaded() = runBlocking {
        // version.json is fetched over the network, so it must not be able to aim the downloader.
        val newer = installedVersionCode() + 1
        script = listOf(
            MockResponse().setBody("""{"versionCode":$newer,"versionName":"9.9","apk":"https://example.com/evil.apk"}"""),
        )

        val outcome = updater().checkAndUpdate(force = true)

        assertEquals(UpdateCheckOutcome.INSTALL_FAILURE, outcome)
        assertEquals("nothing beyond version.json should have been requested", 1, hits.get())
    }

    @Test
    fun aSecondCheckWhileOneIsRunningDoesNotClaimTheAppIsCurrent() = runBlocking {
        // Reporting UP_TO_DATE here was a claim about the world made without looking at it: the
        // button said "you are on the latest version" having fetched nothing.
        script = listOf(
            MockResponse().setBody("""{"versionCode":1,"apk":""}""")
                .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS),
        )

        var second: UpdateCheckOutcome? = null
        var stateWhenRefused: UpdateUiState? = null
        coroutineScope {
            val first = launch(Dispatchers.IO) { updater().checkAndUpdate(force = true) }
            delay(500)
            second = updater().checkAndUpdate(force = true)
            // Read before the first check finishes and reports its own result over the top.
            stateWhenRefused = UpdateCenter.state.value
            first.join()
        }

        assertEquals(UpdateCheckOutcome.NOT_ATTEMPTED, second)
        assertEquals(UpdateUiState.AlreadyChecking, stateWhenRefused)
    }

    // ---- channels ------------------------------------------------------------------------

    @Test
    fun aManifestThatIsNotThereIsAnOutcomeRatherThanAThrow() = runBlocking {
        // A channel whose manifest 404s — a promotion half done, a file renamed — must be a
        // retryable outcome. This is the one part of the app that cannot be fixed remotely, so a
        // throw crossing into the worker would cost every future check, not this one.
        script = listOf(MockResponse().setResponseCode(404))
        assertEquals(UpdateCheckOutcome.TRANSIENT_FAILURE, updater().checkAndUpdate(force = true))
    }

    @Test
    fun aManifestNamingTheOtherChannelIsNeverSilentlyFollowed() = runBlocking {
        // The failure having channels at all exists to prevent: a manifest that names a build
        // from the other lineage. The app is on the stable channel here, and it must not end up
        // running a test build because a file on the internet said so.
        val ahead = installedVersionCode() + 1
        script = listOf(
            MockResponse().setBody(
                """{"versionCode": $ahead, "versionName": "9.9.9-alpha", "apk": "https://example.test/malachi.apk"}""",
            ),
        )
        // Refused before anything is downloaded, because the url is not on the release host —
        // and the channel check behind it is covered without a device in UpdatePolicyTest.
        assertEquals(UpdateCheckOutcome.INSTALL_FAILURE, updater().checkAndUpdate(force = true))
    }

    @Test
    fun releaseNotesSurviveTheRoundTripAndAreOptional() = runBlocking {
        // Both shapes a real manifest takes, against a real parse.
        val ahead = installedVersionCode() + 1
        val withNotes = UpdateInfo.parse(
            """{"versionCode": $ahead, "versionName": "9.9.9-beta", "apk": "https://example.test/a.apk",
               "notes": {"en": "hello", "es": "hola"}}""",
        )
        assertEquals("hola", withNotes?.notesFor("es"))

        val without = UpdateInfo.parse(
            """{"versionCode": $ahead, "versionName": "9.9.9-beta", "apk": "https://example.test/a.apk"}""",
        )
        assertEquals("", without?.notesFor("es"))
    }
}
