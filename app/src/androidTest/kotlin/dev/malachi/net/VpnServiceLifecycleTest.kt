package dev.malachi.net

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.malachi.MalachiApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Before
    fun quiet() {
        runBlocking { app.settingsStore.update { it.copy(filteringEnabled = false, pausedUntilMs = 0) } }
        awaitTunnel(up = false)
    }

    @After
    fun stop() {
        runBlocking { app.settingsStore.update { it.copy(filteringEnabled = false, pausedUntilMs = 0) } }
        awaitTunnel(up = false)
    }

    /** Waits for the tunnel to reach [up], or gives up. Returns whether it got there. */
    private fun awaitTunnel(up: Boolean, timeoutMs: Long = 10_000): Boolean {
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
    private fun awaitReaders(count: Int, timeoutMs: Long = 5_000): List<Thread> {
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
        assumeTrue("VPN consent has not been granted to this build", hasConsent)

        app.settingsStore.update { it.copy(filteringEnabled = true, pausedUntilMs = 0) }
        startService()

        assertTrue("the tunnel never came up", awaitTunnel(up = true))
        assertEquals(TunnelProblem.NONE, VpnStatus.status.value.problem)
        assertEquals(1, awaitReaders(1).size)
    }

    @Test
    fun stoppingJoinsTheReadLoopBeforeTheDescriptorGoes() = runBlocking {
        assumeTrue("VPN consent has not been granted to this build", hasConsent)

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
        assumeTrue("VPN consent has not been granted to this build", hasConsent)

        // One cycle first, so anything allocated once — the pipe, the pools — is already there.
        app.settingsStore.update { it.copy(filteringEnabled = true) }
        assertTrue(awaitTunnel(up = true))
        app.settingsStore.update { it.copy(filteringEnabled = false) }
        assertTrue(awaitTunnel(up = false))

        val before = descriptorsByKind()
        repeat(5) {
            app.settingsStore.update { s -> s.copy(filteringEnabled = true) }
            startService()
            assertTrue("cycle $it never came up", awaitTunnel(up = true))
            app.settingsStore.update { s -> s.copy(filteringEnabled = false) }
            assertTrue("cycle $it never came down", awaitTunnel(up = false))
        }
        // Quiesce before measuring. Turning the filter on is observed in two places — the
        // switch in MalachiApplication and the watchdog — so a tunnel can legitimately be up
        // again by now, and counting one of those as a leak would be measuring the wrong thing.
        app.settingsStore.update { s -> s.copy(filteringEnabled = false) }
        assertTrue(awaitTunnel(up = false))
        assertTrue(awaitReaders(0).isEmpty())
        Thread.sleep(1_000)
        val after = descriptorsByKind()

        // With the filter off and settled, the tunnel owns nothing: no tun, and no shutdown
        // pipe. A leaked cycle would show as one of each, five times over.
        assertEquals("a tun descriptor outlived the filter: $before -> $after", 0, after["/dev/tun"] ?: 0)
        // Pipes are bounded rather than pinned: this process opens a few for reasons of its
        // own while a test runs. The bound is what makes the assertion mean something — measured
        // on a device, twelve cycles move this number no further than five do, so any growth
        // that scales with the cycles is a leak and this catches it.
        val pipes = (after["pipe"] ?: 0) - (before["pipe"] ?: 0)
        assertTrue("pipe descriptors grew by $pipes over five cycles: $before -> $after", pipes <= 6)
    }

    @Test
    fun changingARuleDoesNotTearTheTunnelDown() = runBlocking {
        assumeTrue("VPN consent has not been granted to this build", hasConsent)

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
        assumeTrue("VPN consent has not been granted to this build", hasConsent)

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
        assumeTrue("VPN consent has not been granted to this build", hasConsent)

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
    fun aPauseThatHasExpiredEndsWithTheFilterRunningAgain() = runBlocking {
        assumeTrue("VPN consent has not been granted to this build", hasConsent)

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
