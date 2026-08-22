package dev.malachi.net

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.malachi.MalachiApplication
import dev.malachi.data.BypassGuard
import dev.malachi.debug.DebugLog
import dev.malachi.filter.dns.IpPacket
import dev.malachi.lists.BlocklistCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileDescriptor
import java.net.InetAddress

/**
 * What the rest of the phone can still do while the filter is running.
 *
 * Every case here comes from the same report: a tester's Wi-Fi "didn't even ping" with Malachi
 * on, and the phone kept deciding the Wi-Fi was no good and leaving for mobile data. Both are
 * the same shape of bug — a tunnel that carries DNS and nothing else, quietly swallowing traffic
 * that was never DNS — and neither is visible from a JVM test, because both are about what the
 * *platform* does with a real tun.
 *
 * The tests run their probes through `executeShellCommand`, which runs as the shell user and is
 * therefore **inside** the tunnel, unlike this test process: Malachi always puts itself outside
 * its own tun, so a ping from here would prove nothing at all. (That shell traffic really is
 * covered is not an assumption — it is how "block connections without VPN" was measured on a
 * device, where a shell `nc` to a literal IP came back "Permission denied".)
 *
 * VPN consent cannot be granted from inside a test:
 *
 *     adb shell appops set dev.malachi ACTIVATE_VPN allow
 */
@RunWith(AndroidJUnit4::class)
class TunnelConnectivityTest {

    private val app: MalachiApplication
        get() = ApplicationProvider.getApplicationContext<Application>() as MalachiApplication

    private val cm: ConnectivityManager
        get() = app.getSystemService(ConnectivityManager::class.java)

    private fun requireConsent() {
        if (VpnController.hasConsent(app)) return
        if (InstrumentationRegistry.getArguments().getString("requireVpnConsent") == "true") {
            fail(
                "VPN consent was expected but is not granted. The harness has to run: " +
                    "adb shell appops set dev.malachi ACTIVATE_VPN allow",
            )
        }
        assumeTrue("VPN consent has not been granted to this build", false)
    }

    @Before
    fun quiet() = runBlocking {
        app.settingsStore.update {
            it.copy(
                filteringEnabled = false,
                pausedUntilMs = 0,
                bypassGuard = BypassGuard.SYSTEM_RESOLVERS,
                // No lists: twenty megabytes downloading in the background makes every wait here
                // a coin toss, and none of this is about what gets blocked.
                listChoices = BlocklistCatalog.sources.associate { source -> source.id to false },
            )
        }
        assertTrue("started with a tunnel left up by an earlier test", awaitTunnel(up = false, timeoutMs = 60_000))
    }

    @After
    fun stop() = runBlocking {
        app.settingsStore.update { it.copy(filteringEnabled = false, pausedUntilMs = 0) }
        awaitTunnel(up = false)
        // Whatever a hand-off test turned off, the next test and the machine both want back.
        shell("svc wifi enable")
        shell("svc data enable")
        Unit
    }

    // ---- the machinery ----------------------------------------------------------------------

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }

    /** Whether a ping from *inside* the tunnel gets an answer. */
    private fun pings(address: String, count: Int = 2, waitSeconds: Int = 4): Boolean {
        val output = shell("ping -c $count -W $waitSeconds $address")
        return output.contains("bytes from")
    }

    private fun awaitTunnel(up: Boolean, timeoutMs: Long = 25_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (VpnStatus.status.value.tunnelUp == up) return true
            Thread.sleep(50)
        }
        return VpnStatus.status.value.tunnelUp == up
    }

    private suspend fun filterOn() {
        requireConsent()
        app.settingsStore.update { it.copy(filteringEnabled = true, pausedUntilMs = 0) }
        app.startService(Intent(app, MalachiVpnService::class.java))
        assertTrue("the tunnel never came up", awaitTunnel(up = true))
    }

    /** The networks under the tunnel, which is where the phone's real resolvers live. */
    @Suppress("DEPRECATION")
    private fun underlyingNetworks(): List<Pair<Network, LinkProperties>> = cm.allNetworks
        .filterNot { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        .mapNotNull { network -> cm.getLinkProperties(network)?.let { network to it } }

    private fun networkResolvers(): List<InetAddress> =
        underlyingNetworks().flatMap { (_, properties) -> properties.dnsServers }

    private fun hasTransport(transport: Int): Boolean = underlyingNetworks().any { (network, _) ->
        cm.getNetworkCapabilities(network)?.hasTransport(transport) == true
    }

    /**
     * Whether the filter has ever adopted a network — the log line is the only observable form
     * of it, and it is the line a report is read for.
     */
    private fun adoptedANetwork(): Boolean = adoptedInterface() != null

    /** The interface whose resolvers the filter is asking, according to its own log. */
    private fun adoptedInterface(): String? = DebugLog.entries.value
        .lastOrNull { it.message.startsWith("network ") && it.message.contains("dns=") }
        ?.message?.removePrefix("network ")?.substringBefore(':')?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun liveInterfaces(): Set<String> =
        underlyingNetworks().mapNotNull { (_, properties) -> properties.interfaceName }.toSet()

    /** Waits for something to become true, because a network change is not instantaneous. */
    private fun eventually(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(500)
        }
        return runCatching(condition).getOrDefault(false)
    }

    // ---- the router has to stay reachable ---------------------------------------------------

    @Test
    fun theNetworksOwnResolverIsNotSwallowedByTheTunnel() = runBlocking {
        // The reported bug, exactly. A home network hands out its router as the DNS server, and
        // the bypass guard used to route whatever the network handed out — so the router landed
        // in a tunnel that answers DNS and nothing else, and everything else addressed to it
        // disappeared without a word, ICMP being routine on a tun and never logged. An emulator
        // is the same shape: its resolver is 10.0.2.3, a private address on the other side of
        // the same gateway.
        val resolver = networkResolvers().firstOrNull { it.address.size == 4 }
        assumeTrue("this device's network hands out no IPv4 resolver", resolver != null)
        val address = resolver!!.hostAddress!!
        assumeTrue("the resolver does not answer pings with the filter off", pings(address))

        // The guard is turned on *after* the tunnel is up and the network's resolvers are known,
        // and that ordering is the test rather than a detail of it: the routes are frozen when
        // the tun is built, so a tunnel built in the first moments of a fresh service — before
        // any callback has said what the resolvers are — routes nothing and would pass this
        // whatever the rule said. Changing the guard rebuilds the tun, with the resolvers known.
        app.settingsStore.update { it.copy(bypassGuard = BypassGuard.OFF) }
        filterOn()
        assertTrue("the filter never adopted a network", eventually(30_000) { adoptedANetwork() })
        app.settingsStore.update { it.copy(bypassGuard = BypassGuard.SYSTEM_RESOLVERS) }
        assertTrue("the tunnel did not come back after the guard changed", eventually(30_000) { VpnStatus.status.value.tunnelUp })

        assertTrue(
            "$address stopped answering pings with the filter on: the guard is routing the " +
                "network's own address into a tunnel that cannot carry anything but DNS",
            eventually(20_000) { pings(address) },
        )
    }

    @Test
    fun theTunnelAnswersAPingToItsOwnResolver() = runBlocking {
        // The other half: an address this tunnel *does* route has to answer, or "does it ping"
        // — the first question anybody asks of a network — is answered with silence by the one
        // component that knows the truth. The sentinel exists only inside the tun, so there is
        // no host to ask and Malachi answers it itself.
        filterOn()

        assertTrue(
            "the tunnel's own DNS server does not answer a ping; a routed address that ignores " +
                "ICMP is what makes a working network look dead",
            eventually(20_000) { pings("10.111.222.2") },
        )
    }

    @Test
    fun aPublicResolverTheGuardRoutesStillAnswersAPing() = runBlocking {
        // At the top guard setting the tunnel really does route 8.8.8.8, and pinging a public
        // resolver is the single most common way anybody checks whether their internet works.
        // Relayed through a protected ICMP socket, so the answer comes back from the real host.
        assumeTrue("no route to 8.8.8.8 from here with the filter off", pings("8.8.8.8"))
        app.settingsStore.update { it.copy(bypassGuard = BypassGuard.PUBLIC_RESOLVERS) }
        filterOn()

        assertTrue(
            "8.8.8.8 is routed into the tunnel and no longer answers",
            eventually(25_000) { pings("8.8.8.8") },
        )
    }

    @Test
    fun anUnprivilegedIcmpSocketIsAvailableAndCanBeProtected() {
        // The platform facts the relay stands on, checked rather than remembered: that Android
        // lets an ordinary app open an ICMP datagram socket at all (it does — that is how
        // InetAddress.isReachable works without root), and that a descriptor number obtained by
        // dup can be handed to VpnService.protect, which is the only public way to reach one.
        val socket: FileDescriptor = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
        try {
            assertNotNull(socket)
            val duplicated = ParcelFileDescriptor.dup(socket)
            duplicated.use { assertTrue("a dup of an ICMP socket has no descriptor number", it.fd > 0) }
        } finally {
            runCatching { Os.close(socket) }
        }
    }

    @Test
    fun aRelayedPingRoundTripsAndBuildsAValidReply() {
        // The whole exchange the tunnel performs for a ping, minus the tun: send an echo request
        // through a ping socket, read what comes back, and turn it into the packet that would go
        // down the tunnel. What this pins is the shape of a ping socket's reply — no IP header,
        // and an identifier the kernel has replaced with its own — because building the reply
        // packet on the other assumption produces something no client will ever match.
        val target = InetAddress.getByName("127.0.0.1")
        val request = echoRequestTo(target)
        val socket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
        try {
            Os.connect(socket, target, 0)
            Os.write(socket, request.message, 0, request.message.size)
            val buffer = ByteArray(512)
            val read = Os.read(socket, buffer, 0, buffer.size)
            assertTrue("nothing came back from a ping to loopback", read >= 8)
            // A ping socket hands back the ICMP message itself, with no IP header in front of it.
            assertTrue("the reply is not an echo reply: ${buffer[0]}", buffer[0].toInt() == 0)

            val reply = IpPacket.buildEchoReply(request, buffer, read)
            assertNotNull("the reply could not be turned into a packet", reply)
            val parsed = IpPacket.protocol(reply!!, reply.size)
            assertTrue("the reply is not ICMP", parsed == IpPacket.PROTOCOL_ICMP)
            // And it carries the identifier the *client* used, not the one the kernel swapped in.
            val identifier = ((reply[24].toInt() and 0xFF) shl 8) or (reply[25].toInt() and 0xFF)
            assertTrue("the reply came back under the kernel's identifier, so no client matches it", identifier == 0x4321)
        } finally {
            runCatching { Os.close(socket) }
        }
    }

    private fun echoRequestTo(target: InetAddress): dev.malachi.filter.dns.IcmpEcho {
        val message = ByteArray(16)
        message[0] = 8 // echo request
        message[4] = 0x43
        message[5] = 0x21
        message[7] = 1 // sequence
        val packet = ByteArray(20 + message.size)
        packet[0] = 0x45
        packet[2] = ((packet.size ushr 8).toByte())
        packet[3] = packet.size.toByte()
        packet[8] = 64
        packet[9] = IpPacket.PROTOCOL_ICMP.toByte()
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(packet, 12)
        target.address.copyInto(packet, 16)
        message.copyInto(packet, 20)
        return IpPacket.parseEcho(packet, packet.size)!!
    }

    // ---- the hand-off between Wi-Fi and mobile data -------------------------------------------

    @Test
    fun theFilterFollowsThePhoneFromWifiToMobileAndBack() = runBlocking {
        assumeTrue("this device has no Wi-Fi", hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assumeTrue("this device has no mobile network", hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        filterOn()
        assertTrue("nothing resolved before the hand-off", eventually(30_000) { resolvesThroughTheTunnel() })

        // Out of Wi-Fi range, as far as the platform is concerned.
        shell("svc wifi disable")
        assertTrue(
            "the phone never moved to the mobile network",
            eventually(45_000) { !hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
        )

        // The bug this is here for: the filter goes on asking the resolvers of the network the
        // phone has left, every lookup times out, and from the outside the phone simply has no
        // working internet — which is what "it made me go out through mobile data" looks like
        // from the other side of the same failure.
        assertTrue("the tunnel did not survive the hand-off", VpnStatus.status.value.tunnelUp)
        // The invariant, and deliberately not "an adoption happened": whether the phone had to
        // change network at all depends on which one it was using, and on an emulator that is
        // whichever the image happens to make the default — this asserted a log line that CI's
        // phone had no reason to write, because its filter was on the mobile network to begin
        // with. What must always hold is that the filter is not left asking a network the phone
        // no longer has. That is the eleven-hour bug stated as a property.
        assertTrue(
            "the filter is still using ${adoptedInterface()}, which this phone no longer has " +
                "(it has ${liveInterfaces()}): every lookup now goes to a network that is gone",
            eventually(60_000) { adoptedInterface()?.let { it in liveInterfaces() } ?: true },
        )
        assertTrue(
            "nothing resolved after moving to the mobile network: the filter is still holding " +
                "the resolvers of the network the phone left",
            eventually(60_000) { resolvesThroughTheTunnel() },
        )

        // And back, which is the direction the tester was complaining about.
        shell("svc wifi enable")
        assertTrue(
            "the Wi-Fi never came back",
            eventually(60_000) { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
        )
        assertTrue("the tunnel did not survive the way back", VpnStatus.status.value.tunnelUp)
        assertTrue(
            "nothing resolved after returning to Wi-Fi",
            eventually(60_000) { resolvesThroughTheTunnel() },
        )
    }

    @Test
    fun theRoutersOwnAddressSurvivesAHandover() = runBlocking {
        // The two bugs together, which is how they were reported: come back into Wi-Fi range and
        // the phone should be able to reach its own gateway again — not merely resolve names.
        assumeTrue("this device has no Wi-Fi", hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        filterOn()
        val resolver = networkResolvers().firstOrNull { it.address.size == 4 }?.hostAddress
        assumeTrue("this device's network hands out no IPv4 resolver", resolver != null)

        shell("svc wifi disable")
        Thread.sleep(5_000)
        shell("svc wifi enable")
        assertTrue(
            "the Wi-Fi never came back",
            eventually(60_000) { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
        )

        assertTrue(
            "$resolver does not answer after a hand-off",
            eventually(45_000) { pings(resolver!!) },
        )
    }

    /**
     * Whether a name looks up from inside the tunnel.
     *
     * `ping` is used for the lookup rather than for the ping: it resolves the name through the
     * system resolver of whoever runs it, and whoever runs it here is the shell, which is inside
     * the tun. "unknown host" is a DNS failure whatever happens to the packets afterwards, which
     * is why the assertion is on that and not on a reply.
     */
    private fun resolvesThroughTheTunnel(): Boolean {
        val output = shell("ping -c 1 -W 3 android.com")
        return !output.contains("unknown host", ignoreCase = true) &&
            !output.contains("Name or service not known", ignoreCase = true) &&
            output.isNotBlank()
    }

    @Test
    fun theLogSaysWhichNetworkTheResolversCameFrom() = runBlocking {
        // Not decoration: "these are rmnet16's resolvers and they were adopted eleven hours ago"
        // is the whole diagnosis of a phone that resolves nothing on a Wi-Fi, and a report
        // without it cannot be read at a distance.
        filterOn()
        assertTrue(
            "the debug log never named the network its resolvers came from",
            eventually(30_000) { adoptedANetwork() },
        )
    }
}
