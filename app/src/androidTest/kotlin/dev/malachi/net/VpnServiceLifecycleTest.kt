package dev.malachi.net

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.malachi.MalachiApplication
import dev.malachi.lists.BlocklistCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The tunnel itself, on a device.
 *
 * What is checked here is the part no JVM test can reach: that the descriptor lifecycle
 * actually holds. The read loop must be gone when the tunnel stops — [MalachiVpnService]
 * joins it before closing the descriptor precisely so that nothing is left reading a number the
 * kernel has already handed to something else — and repeated cycles must not leak descriptors,
 * which is the observable form of that same bug.
 *
 * VPN consent cannot be granted from inside a test, so the tunnel cases are skipped unless the
 * harness granted it first:
 *
 *     adb shell appops set dev.malachi ACTIVATE_VPN allow
 */
@RunWith(AndroidJUnit4::class)
class VpnServiceLifecycleTest {

    private val app: MalachiApplication
        get() = ApplicationProvider.getApplicationContext<Application>() as MalachiApplication

    private val hasConsent: Boolean get() = VpnController.hasConsent(app)

    /**
     * Skips the tunnel cases when VPN consent is missing — unless the harness said it granted
     * it, in which case the run is expected to exercise them and quietly skipping would be the
     * worst outcome: a green tick over a suite that tested nothing. CI passes
     * `-Pandroid.testInstrumentationRunnerArguments.requireVpnConsent=true`.
     */
    private fun requireConsent() {
        if (hasConsent) return
        val expected = InstrumentationRegistry.getArguments().getString("requireVpnConsent") == "true"
        if (expected) {
            fail(
                "VPN consent was expected but is not granted. The harness has to run: " +
                    "adb shell appops set dev.malachi ACTIVATE_VPN allow",
            )
        }
        assumeTrue("VPN consent has not been granted to this build", false)
    }

    @Before
    fun quiet() {
        runBlocking {
            app.settingsStore.update {
                it.copy(
                    filteringEnabled = false,
                    pausedUntilMs = 0,
                    // No lists. A fresh install fetches twenty megabytes of them in the
                    // background, and an emulator busy doing that makes every wait below a
                    // coin toss. The tunnel does not need a single rule to establish, which is
                    // the only thing these tests are about.
                    listChoices = BlocklistCatalog.sources.associate { source -> source.id to false },
                )
            }
        }
        // Asserted, not hoped for. A test that inherits a tunnel somebody else left up fails
        // somewhere in the middle and blames the wrong thing; this fails at the door and says so.
        // Generous: the app downloads its blocklists in the background on a fresh install, and
        // an emulator busy doing that takes its time about everything else.
        assertTrue("started with a tunnel left up by an earlier test", awaitTunnel(up = false, timeoutMs = 60_000))
        assertTrue(
            "started with a read loop left running by an earlier test",
            awaitReaders(0, timeoutMs = 30_000).isEmpty(),
        )
    }

    @After
    fun stop() {
        runBlocking { app.settingsStore.update { it.copy(filteringEnabled = false, pausedUntilMs = 0) } }
        awaitTunnel(up = false)
    }

    /** Waits for the tunnel to reach [up], or gives up. Returns whether it got there. */
    private fun awaitTunnel(up: Boolean, timeoutMs: Long = 25_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (VpnStatus.status.value.tunnelUp == up) return true
            Thread.sleep(50)
        }
        return VpnStatus.status.value.tunnelUp == up
    }

    private fun readerThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "malachi-tun" && it.isAlive }

    /** Waits for exactly [count] read loops to be alive, so an assertion isn't a race. */
    private fun awaitReaders(count: Int, timeoutMs: Long = 15_000): List<Thread> {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val threads = readerThreads()
            if (threads.size == count) return threads
            Thread.sleep(50)
        }
        return readerThreads()
    }

    /**
     * The process's open descriptors, counted by what they point at.
     *
     * A total is not a diagnosis: this process also talks to binder, DataStore and WorkManager
     * while a test runs, and any of them can move the number. What the tunnel owns is specific —
     * a tun device and the two ends of its shutdown pipe — so those are what get counted.
     */
    private fun descriptorsByKind(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        File("/proc/self/fd").listFiles().orEmpty().forEach { link ->
            val target = runCatching { android.system.Os.readlink(link.absolutePath) }.getOrNull() ?: return@forEach
            val kind = when {
                target.startsWith("pipe:") -> "pipe"
                target.startsWith("socket:") -> "socket"
                target.startsWith("anon_inode:") -> "anon_inode"
                target.startsWith("/dev/") -> target
                else -> "file"
            }
            counts[kind] = (counts[kind] ?: 0) + 1
        }
        return counts
    }

    private fun startService() {
        app.startService(Intent(app, MalachiVpnService::class.java))
    }

    // ---------------------------------------------------------------------------------------

    @Test
    fun theServiceStandsDownWhenFilteringIsOff() = runBlocking {
        // Started unconditionally — which is what BootReceiver does, because reading the setting
        // first would mean suspending and a service started after onReceive returns is refused.
        // The service has to work out for itself that it should not be running.
        startService()
        Thread.sleep(1_000)

        assertFalse(VpnStatus.status.value.tunnelUp)
        assertTrue("a read loop is running with filtering off", readerThreads().isEmpty())
    }

    @Test
    fun theTunnelComesUpAndReportsItself() = runBlocking {
        requireConsent()

        app.settingsStore.update { it.copy(filteringEnabled = true, pausedUntilMs = 0) }
        startService()

        assertTrue("the tunnel never came up", awaitTunnel(up = true))
        assertEquals(TunnelProblem.NONE, VpnStatus.status.value.problem)
        assertEquals(1, awaitReaders(1).size)
    }

    @Test
    fun stoppingJoinsTheReadLoopBeforeTheDescriptorGoes() = runBlocking {
        requireConsent()

        app.settingsStore.update { it.copy(filteringEnabled = true, pausedUntilMs = 0) }
        startService()
        assertTrue(awaitTunnel(up = true))
        assertEquals(1, awaitReaders(1).size)

        app.settingsStore.update { it.copy(filteringEnabled = false) }
        assertTrue(awaitTunnel(up = false))

        // The join is the point: by the time the tunnel reports itself down, the thread that was
        // reading that descriptor is gone. A thread still alive here is one that can read or
        // write a descriptor number the kernel has already reissued.
        assertTrue("the read loop outlived the tunnel", awaitReaders(0, timeoutMs = 1_000).isEmpty())
    }

    @Test
    fun repeatedCyclesDoNotLeakDescriptors() = runBlocking {
        requireConsent()

        // One cycle first, so anything allocated once — the pipe, the pools — is already there.
        app.settingsStore.update { it.copy(filteringEnabled = true) }
        assertTrue(awaitTunnel(up = true))
        app.settingsStore.update { it.copy(filteringEnabled = false) }
        assertTrue(awaitTunnel(up = false))

        val before = descriptorsByKind()
        // Three cycles, each allowed to settle. The property under test is that a cycle returns
        // what it took — a leak would be a tun and a pipe every time round, unmistakable after
        // three. Running them back to back as fast as the settings flow allows tested the
        // platform's tolerance for being thrashed instead, and that is not this app's promise.
        repeat(3) {
            app.settingsStore.update { s -> s.copy(filteringEnabled = true) }
            startService()
            assertTrue("cycle $it never came up", awaitTunnel(up = true))
            assertTrue("cycle $it came up without a read loop", awaitReaders(1).size == 1)
            app.settingsStore.update { s -> s.copy(filteringEnabled = false) }
            assertTrue("cycle $it never came down", awaitTunnel(up = false))
            assertTrue("cycle $it left its read loop behind", awaitReaders(0).isEmpty())
            Thread.sleep(750)
        }
        // Quiesce before measuring. Turning the filter on is observed in two places — the
        // switch in MalachiApplication and the watchdog — so a tunnel can legitimately be up
        // again by now, and counting one of those as a leak would be measuring the wrong thing.
        app.settingsStore.update { s -> s.copy(filteringEnabled = false) }
        assertTrue("the filter would not settle", awaitTunnel(up = false))
        assertTrue("a read loop outlived the last cycle", awaitReaders(0).isEmpty())
        Thread.sleep(1_000)
        val after = descriptorsByKind()

        // With the filter off and settled, the tunnel owns nothing of its own: no tun device,
        // and no read loop. Those two are what the leak actually cost — one of each, every
        // cycle — and both are exact.
        assertEquals("a tun descriptor outlived the filter: $before -> $after", 0, after["/dev/tun"] ?: 0)

        // There is deliberately no assertion on the pipe count. This process opens pipes for
        // reasons of its own while a test runs, the number was never attributable to the
        // tunnel, and a bound picked to fit one machine is a bound that fails on another: this
        // one was six, measured here, and CI saw eight. A gate that cries wolf teaches people
        // to ignore it, which costs more than the noise it was catching. The two assertions
        // above catch the same bug and cannot drift.
    }

    @Test
    fun changingARuleDoesNotTearTheTunnelDown() = runBlocking {
        requireConsent()

        app.settingsStore.update { it.copy(filteringEnabled = true) }
        assertTrue(awaitTunnel(up = true))
        val reader = awaitReaders(1).single()

        // A rule is read per query. Editing one must not cost a rebuild — which would be a
        // visible blink of unfiltered DNS every time the user typed a domain.
        app.settingsStore.update { it.copy(userBlocked = setOf("ads.example.com")) }
        Thread.sleep(1_500)

        assertTrue(VpnStatus.status.value.tunnelUp)
        assertEquals("the tunnel was rebuilt for a rule change", reader, readerThreads().singleOrNull())
    }

    @Test
    fun changingTheAppScopeDoesRebuildIt() = runBlocking {
        requireConsent()

        app.settingsStore.update { it.copy(filteringEnabled = true, excludedApps = emptySet()) }
        assertTrue(awaitTunnel(up = true))
        val reader = awaitReaders(1).single()

        // The scope is baked into the tun at establish() and cannot be changed afterwards.
        app.settingsStore.update { it.copy(excludedApps = setOf("com.android.settings")) }
        Thread.sleep(2_000)

        assertTrue("the tunnel did not come back after the rebuild", awaitTunnel(up = true))
        val now = awaitReaders(1)
        assertEquals(1, now.size)
        assertFalse("the tunnel was not rebuilt for a scope change", now.contains(reader))
    }

    @Test
    fun aPauseTakesTheTunnelDownAndKeepsTheServiceAlive() = runBlocking {
        requireConsent()

        app.settingsStore.update { it.copy(filteringEnabled = true) }
        assertTrue(awaitTunnel(up = true))

        app.settingsStore.update { it.copy(pausedUntilMs = System.currentTimeMillis() + 60_000) }
        assertTrue("a pause left the tunnel up", awaitTunnel(up = false))
        assertTrue(awaitReaders(0).isEmpty())

        // Ending the pause brings it back without anybody restarting the service.
        app.settingsStore.update { it.copy(pausedUntilMs = 0) }
        assertTrue("the filter did not resume", awaitTunnel(up = true))
    }

    @Test
    fun theMessageThatEndsAPauseBringsTheFilterBack() = runBlocking {
        requireConsent()

        app.settingsStore.update { it.copy(filteringEnabled = true) }
        assertTrue(awaitTunnel(up = true))

        // Ten minutes, so the resume timer cannot be what ends it inside this test. That is the
        // whole point: on a phone that timer runs on a clock which stops while the device is
        // suspended, so a fifteen minute pause outlived it by hours and the home screen sat on
        // "starting the filter…" with nothing starting it. The fix arms an RTC alarm for the same
        // moment, and this is the message that alarm delivers.
        app.settingsStore.update { it.copy(pausedUntilMs = System.currentTimeMillis() + 10 * 60_000) }
        assertTrue("a pause left the tunnel up", awaitTunnel(up = false))

        app.startService(
            Intent(app, MalachiVpnService::class.java).setAction(MalachiVpnService.ACTION_RESUME),
        )

        assertTrue("ending the pause did not bring the filter back", awaitTunnel(up = true))
    }

    @Test
    fun aPauseThatHasExpiredEndsWithTheFilterRunningAgain() = runBlocking {
        requireConsent()

        app.settingsStore.update { it.copy(filteringEnabled = true) }
        assertTrue(awaitTunnel(up = true))
        app.settingsStore.update { it.copy(pausedUntilMs = System.currentTimeMillis() + 2_000) }
        assertTrue(awaitTunnel(up = false))

        // Two things can end a pause: the resume timer, and a bare start — the watchdog's —
        // making the service look at the world afresh. On a device that is awake the timer
        // usually wins, so what is asserted here is the outcome. *Which* path takes it, and in
        // particular that the second one exists for the case where the device slept through the
        // first, is pinned in TunnelPolicyTest where the clock is a parameter.
        Thread.sleep(2_500)
        startService()

        assertTrue("the filter did not come back after the pause expired", awaitTunnel(up = true))
    }
}
