package dev.malachi.net

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.core.app.ServiceCompat
import dev.malachi.MalachiApplication
import dev.malachi.R
import dev.malachi.data.AppScopeMode
import dev.malachi.data.BypassGuard
import dev.malachi.data.MalachiSettings
import dev.malachi.data.UpstreamDns
import dev.malachi.debug.DebugLog
import dev.malachi.filter.QueryLog
import dev.malachi.filter.dns.DnsMessage
import dev.malachi.filter.dns.IpPacket
import dev.malachi.filter.dns.UdpDatagram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The filter itself: a local VPN that exists only to see DNS.
 *
 * Android gives an app one honest way to inspect another app's traffic, and it is a tunnel. But
 * a tunnel that carried *everything* would make Malachi responsible for the device's entire
 * network path — throughput, battery, breakage — to read the handful of packets it actually
 * cares about. So the tun is a decoy with a very small route table: it advertises a sentinel
 * address as the device's DNS server and routes that address, and nothing else. Every other byte
 * the phone sends never touches this process.
 *
 * What arrives is therefore a DNS query. It is parsed, attributed to the app that sent it, and
 * either answered locally (blocked) or forwarded to a real resolver and relayed back (allowed).
 *
 * **Fail open, everywhere.** A packet we can't parse, an app we can't attribute, an upstream
 * that doesn't answer — every one of those forwards or drops rather than synthesising a refusal.
 * The worst outcome of a bug here is an ad; the worst outcome of the opposite policy is a phone
 * with no working DNS and no obvious culprit.
 */
class MalachiVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Forwarding is blocking I/O with a timeout, and a burst of lookups when an app launches can
     * be dozens at once. A bounded pool keeps that from becoming dozens of threads.
     */
    private val forwardExecutor = Executors.newFixedThreadPool(FORWARD_THREADS) { r ->
        Thread(r, "malachi-dns").apply { isDaemon = true }
    }
    private val forwarders = forwardExecutor.asCoroutineDispatcher()

    private lateinit var app: MalachiApplication
    private lateinit var cm: ConnectivityManager

    @Volatile private var settings = MalachiSettings()
    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var tunnelShape: String? = null
    @Volatile private var output: FileOutputStream? = null
    @Volatile private var readerThread: Thread? = null
    private val writeLock = Any()

    /** DNS servers of the underlying (non-VPN) network, refreshed by the network callback. */
    @Volatile private var networkDnsServers: List<InetAddress> = emptyList()
    @Volatile private var privateDnsActive = false
    @Volatile private var privateDnsHost: String? = null

    /** Resolved once per tunnel rather than per query; see [resolveUpstreams]. */
    @Volatile private var upstreams: List<InetAddress> = emptyList()

    /** UID → package name. Stable for the life of an install and asked for on every lookup. */
    private val uidPackages = ConcurrentHashMap<Int, String>()

    private var resumeJob: Job? = null
    private var lastUnroutableLogMs = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            adoptNetwork(network, linkProperties)
        }

        override fun onLost(network: Network) {
            networkDnsServers = emptyList()
            upstreams = resolveUpstreams()
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as MalachiApplication
        cm = getSystemService(ConnectivityManager::class.java)
        FilterNotifications.ensureChannel(this)
        // Promoted before anything can fail: a service started with startForegroundService that
        // doesn't reach startForeground within a few seconds is killed with an ANR-shaped crash.
        promote(FilterNotifications.running(this, 0, 0))
        registerNetworkCallback()

        scope.launch { app.settingsStore.settings.collect { applySettings(it) } }
        scope.launch { notificationLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    app.settingsStore.update { it.copy(filteringEnabled = false, pausedUntilMs = 0) }
                }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> scope.launch {
                app.settingsStore.update { it.copy(pausedUntilMs = System.currentTimeMillis() + PAUSE_MILLIS) }
            }
            ACTION_RESUME -> scope.launch { app.settingsStore.update { it.copy(pausedUntilMs = 0) } }
        }
        // START_STICKY: if the system reclaims this process, the filter should come back by
        // itself. It re-reads the settings on create, so a restart lands in the right state.
        return START_STICKY
    }

    /**
     * Reacts to a settings change. Which of the two things it does matters:
     *
     * The set of apps in scope and the bypass routes are baked into the tun when it is built and
     * cannot be changed afterwards, so those need a rebuild — a visible blink of unfiltered DNS.
     * Rules and lists are read per query from [dev.malachi.filter.FilterRepository], so those take
     * effect on the next lookup with no interruption. [MalachiSettings.tunnelShape] is what tells
     * the two apart.
     */
    private suspend fun applySettings(next: MalachiSettings) {
        settings = next
        QueryLog.recording = next.queryLogEnabled

        if (!next.filteringEnabled) {
            stopTunnel()
            stopSelf()
            return
        }

        resumeJob?.cancel()
        if (next.isPaused()) {
            stopTunnel()
            promote(FilterNotifications.paused(this, timeLabel(next.pausedUntilMs)))
            // Nothing else would wake us: the settings flow has no reason to emit again just
            // because a moment in the future has arrived.
            resumeJob = scope.launch {
                delay((next.pausedUntilMs - System.currentTimeMillis()).coerceAtLeast(0))
                app.settingsStore.update { if (it.isPaused()) it.copy(pausedUntilMs = 0) else it }
            }
            return
        }

        val shape = next.tunnelShape()
        if (tunnel == null || shape != tunnelShape) startTunnel(next)
    }

    private fun startTunnel(settings: MalachiSettings) {
        stopTunnel()

        if (settings.scopeMode == AppScopeMode.ONLY_SELECTED && settings.includedApps.isEmpty()) {
            // Establishing with an empty allow-list would filter *everything*, which is the
            // exact opposite of what the screen says. Refuse and explain.
            reportProblem(TunnelProblem.NO_APPS_SELECTED, getString(R.string.status_no_apps_selected))
            return
        }
        if (!VpnController.hasConsent(this)) {
            reportProblem(TunnelProblem.NO_CONSENT, getString(R.string.status_no_consent))
            return
        }

        val pfd = runCatching { build(settings) }
            .onFailure { DebugLog.e(TAG, "could not build the tunnel", it) }
            .getOrNull()
        if (pfd == null) {
            // establish() returns null for a withdrawn consent and for "another VPN owns the
            // tunnel". They are indistinguishable from here, and the second is far more likely
            // when consent was granted a moment ago.
            val displaced = VpnController.hasConsent(this)
            reportProblem(
                if (displaced) TunnelProblem.DISPLACED else TunnelProblem.NO_CONSENT,
                getString(if (displaced) R.string.status_displaced else R.string.status_no_consent),
            )
            return
        }

        tunnel = pfd
        tunnelShape = settings.tunnelShape()
        output = FileOutputStream(pfd.fileDescriptor)
        upstreams = resolveUpstreams()
        QueryLog.reset()
        VpnStatus.up(upstreamLabel(), privateDnsActive, privateDnsHost)
        promote(FilterNotifications.running(this, 0, 0))
        DebugLog.i(TAG, "tunnel up; upstream=${upstreamLabel()} scope=${settings.scopeMode}")

        // A dedicated thread rather than a coroutine: the read is a blocking syscall that only
        // returns when a packet arrives, and parking a pooled dispatcher thread on it forever is
        // how you starve every other coroutine in the process.
        readerThread = Thread({ readLoop(pfd) }, "malachi-tun").apply {
            isDaemon = true
            start()
        }
    }

    private fun build(settings: MalachiSettings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            // Generous for a link that carries nothing but DNS: it lets a large DNSSEC or EDNS
            // answer through in one piece instead of forcing a TCP retry this tunnel can't serve.
            .setMtu(MTU)
            .addAddress(TUN_IPV4, 32)
            .addDnsServer(DNS_IPV4)
            .addRoute(DNS_IPV4, 32)

        // IPv6 is not politeness: a phone on a mobile network is routinely IPv6-only, and a
        // filter that advertised no IPv6 resolver would be bypassed by every lookup on it.
        runCatching {
            builder.addAddress(TUN_IPV6, 128)
                .addDnsServer(DNS_IPV6)
                .addRoute(DNS_IPV6, 128)
        }.onFailure { DebugLog.w(TAG, "no IPv6 on this device; filtering IPv4 only", it) }

        applyScope(builder, settings)
        applyBypassGuard(builder, settings)

        return builder.establish()
    }

    /**
     * Both directions of "which apps", enforced by the platform rather than by us: an app out of
     * scope never reaches this process at all, which is a stronger promise than a check we
     * perform on its packets, and costs nothing at runtime.
     */
    private fun applyScope(builder: Builder, settings: MalachiSettings) {
        when (settings.scopeMode) {
            AppScopeMode.ALL_EXCEPT -> {
                // Always outside its own tunnel: Malachi downloads lists and updates, and
                // routing that through the filter it is updating invites a loop.
                (settings.excludedApps + packageName).forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                        .onFailure { DebugLog.w(TAG, "cannot exclude $pkg (not installed?)") }
                }
            }
            AppScopeMode.ONLY_SELECTED -> {
                settings.includedApps.filter { it != packageName }.forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                        .onFailure { DebugLog.w(TAG, "cannot include $pkg (not installed?)") }
                }
            }
        }
    }

    /**
     * Routes the resolvers an app might use to go around us.
     *
     * Advertising a DNS server only redirects apps that ask the system which resolver to use.
     * An app with `8.8.8.8` compiled into it never asks. The only way to see those lookups is to
     * route the address itself into the tun — but every address routed here is also an address
     * whose *non-DNS* traffic lands in a tunnel that can't carry it, so each step up catches
     * more and risks more. That is why this is a dial the user sets and not a default we choose
     * for them.
     */
    private fun applyBypassGuard(builder: Builder, settings: MalachiSettings) {
        if (settings.bypassGuard == BypassGuard.OFF) return

        // With Private DNS on, the "system resolver" is a DoT endpoint reached over TCP. Routing
        // it here would black-hole the device's DNS entirely, so the guard stands down and the
        // UI says why (see FilterStatus.privateDnsActive).
        val candidates = buildList {
            if (!privateDnsActive) addAll(networkDnsServers)
            if (settings.bypassGuard == BypassGuard.PUBLIC_RESOLVERS) {
                addAll(PUBLIC_RESOLVERS.mapNotNull { numericAddress(it) })
            }
        }

        candidates.distinct()
            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
            .filterNot { it.hostAddress == DNS_IPV4 || it.hostAddress == DNS_IPV6 }
            .forEach { address ->
                runCatching { builder.addRoute(address, if (address is Inet4Address) 32 else 128) }
                    .onFailure { DebugLog.w(TAG, "cannot route ${address.hostAddress}", it) }
            }
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buffer = ByteArray(MTU)
        try {
            while (tunnel === pfd) {
                val length = runCatching { input.read(buffer) }.getOrDefault(-1)
                // End of stream means the descriptor is gone: revoked, replaced by another VPN,
                // or torn down by the system. Reading a dead descriptor returns instantly, so
                // continuing here would spin a core until the process died.
                if (length < 0) {
                    DebugLog.w(TAG, "the tunnel closed underneath us")
                    break
                }
                if (length == 0) continue
                val packet = buffer.copyOf(length)
                scope.launch(forwarders) { runCatching { handle(packet) } }
            }
        } finally {
            runCatching { input.close() }
            if (tunnel === pfd) {
                stopTunnel()
                VpnStatus.down(TunnelProblem.FAILED, getString(R.string.status_tunnel_closed))
            }
        }
    }

    private fun handle(packet: ByteArray) {
        val udp = IpPacket.parseUdp(packet, packet.size) ?: return dropUnroutable("non-UDP")
        if (udp.destinationPort != DNS_PORT) return dropUnroutable("UDP port ${udp.destinationPort}")

        val payload = udp.payload(packet)
        // Not a question we understand: a response, an update, a malformed name. Forward it and
        // let a real resolver be the one to have an opinion.
        val question = DnsMessage.parseQuestion(payload) ?: return forward(udp, payload)

        val packageName = ownerPackage(udp)
        val verdict = app.filterRepository.decide(question.name, packageName)
        QueryLog.record(question.name, packageName, verdict)

        if (verdict.blocked) {
            val response = DnsMessage.blockedResponse(payload, question, settings.blockAnswer.toBlockAnswer())
            writeToTun(IpPacket.buildUdpResponse(udp, response))
        } else {
            forward(udp, payload)
        }
    }

    /** Sends the query on to a real resolver and relays the answer back, byte for byte. */
    private fun forward(request: UdpDatagram, query: ByteArray) {
        val wantsIpv6 = request.destinationAddress.size == 16
        val target = upstreams.firstOrNull { (it is Inet4Address) != wantsIpv6 }
            ?: upstreams.firstOrNull()
            ?: return
        val reply = runCatching {
            DatagramSocket().use { socket ->
                // Without this the query would be routed back into our own tunnel and loop.
                protect(socket)
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                socket.send(DatagramPacket(query, query.size, target, DNS_PORT))
                val buffer = ByteArray(UPSTREAM_BUFFER)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                buffer.copyOf(response.length)
            }
        }.getOrElse {
            // A timeout is a normal event on a flaky network. Dropping is right: the client's
            // own resolver will retry, and inventing an answer would be worse than silence.
            return
        }
        writeToTun(IpPacket.buildUdpResponse(request, reply))
    }

    /**
     * Which app sent this, via the socket's owning UID. Best-effort by design: an unattributed
     * lookup still gets a global verdict, it just can't match a per-app rule.
     */
    private fun ownerPackage(udp: UdpDatagram): String? {
        val uid = runCatching {
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(udp.sourceAddress), udp.sourcePort),
                InetSocketAddress(InetAddress.getByAddress(udp.destinationAddress), udp.destinationPort),
            )
        }.getOrDefault(Process.INVALID_UID)
        if (uid == Process.INVALID_UID || uid < Process.FIRST_APPLICATION_UID) return null
        uidPackages[uid]?.let { return it }
        val name = runCatching { packageManager.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() ?: return null
        uidPackages[uid] = name
        return name
    }

    private fun writeToTun(packet: ByteArray) {
        if (packet.size > MTU) {
            DebugLog.w(TAG, "dropping a ${packet.size}-byte answer that doesn't fit the tunnel")
            return
        }
        val stream = output ?: return
        synchronized(writeLock) { runCatching { stream.write(packet) } }
    }

    /**
     * Something reached the tun that isn't a DNS query — TCP to a routed resolver, or QUIC. We
     * can't carry it and won't pretend to, so it is dropped, which is what the bypass guard is
     * for in the first place. Logged at most once a minute: a chatty app would otherwise fill
     * the debug log with the same line.
     */
    private fun dropUnroutable(what: String) {
        val now = System.currentTimeMillis()
        if (now - lastUnroutableLogMs < UNROUTABLE_LOG_INTERVAL_MS) return
        lastUnroutableLogMs = now
        DebugLog.i(TAG, "dropped traffic the tunnel can't carry ($what)")
    }

    private fun registerNetworkCallback() {
        // The default request already excludes VPNs, so this tracks the real network under us
        // rather than our own tunnel.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, networkCallback) }
            .onFailure { DebugLog.w(TAG, "cannot watch the underlying network", it) }
    }

    private fun adoptNetwork(network: Network, linkProperties: LinkProperties) {
        networkDnsServers = linkProperties.dnsServers.orEmpty()
        privateDnsActive = linkProperties.isPrivateDnsActive
        privateDnsHost = linkProperties.privateDnsServerName
        VpnStatus.privateDns(privateDnsActive, privateDnsHost)
        upstreams = resolveUpstreams()
        // Tells the system which network our forwarded queries really travel over, so they are
        // billed and routed correctly instead of appearing to come from the tunnel.
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
        if (tunnel != null) VpnStatus.up(upstreamLabel(), privateDnsActive, privateDnsHost)
    }

    /**
     * Where allowed lookups go. Resolved to addresses once, because doing it per query would put
     * a settings read and a parse on the hot path for an answer that changes about as often as
     * the user changes networks.
     */
    private fun resolveUpstreams(): List<InetAddress> {
        val configured = when (settings.upstream) {
            UpstreamDns.SYSTEM -> networkDnsServers
            UpstreamDns.CUSTOM -> settings.customUpstream.split(',', ' ').mapNotNull { numericAddress(it.trim()) }
            else -> settings.upstream.addresses.mapNotNull { numericAddress(it) }
        }.filterNot { it.hostAddress == DNS_IPV4 || it.hostAddress == DNS_IPV6 }

        // A network that hands out no resolver, or a custom entry that was typed wrong, must not
        // leave the device with nowhere to ask.
        return configured.ifEmpty { UpstreamDns.CLOUDFLARE.addresses.mapNotNull { numericAddress(it) } }
    }

    private fun upstreamLabel(): String = when (settings.upstream) {
        UpstreamDns.SYSTEM -> getString(R.string.upstream_system)
        else -> upstreams.firstOrNull()?.hostAddress ?: getString(R.string.upstream_system)
    }

    /** Keeps the ongoing notification's counters roughly current without a write per query. */
    private suspend fun notificationLoop() {
        var lastTotal = -1L
        while (scope.isActive) {
            delay(NOTIFICATION_REFRESH_MS)
            if (tunnel == null) continue
            val state = QueryLog.state.value
            if (state.total == lastTotal) continue
            lastTotal = state.total
            promote(FilterNotifications.running(this, state.blocked, state.total))
        }
    }

    private fun promote(notification: android.app.Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                FilterNotifications.NOTIFICATION_ID,
                notification,
                // The type has to be one the running platform knows, and `specialUse` only
                // exists from Android 14. Below that the call takes no type at all — passing
                // one the system can't match against the manifest is rejected outright, which
                // would take the whole filter down on every Android 10-13 device.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        }.onFailure { DebugLog.w(TAG, "could not go foreground", it) }
    }

    private fun reportProblem(problem: TunnelProblem, message: String) {
        DebugLog.w(TAG, "filter not running: $message")
        VpnStatus.down(problem, message)
        promote(FilterNotifications.problem(this, message))
    }

    private fun stopTunnel() {
        val pfd = tunnel ?: return
        tunnel = null
        tunnelShape = null
        readerThread?.interrupt()
        readerThread = null
        runCatching { output?.close() }
        output = null
        runCatching { pfd.close() }
        VpnStatus.down()
    }

    /**
     * The system took the tunnel away — another VPN was started, or the user withdrew consent.
     * Without handling it the service would keep "running" over a dead descriptor and the app
     * would show a filter that was quietly filtering nothing.
     */
    override fun onRevoke() {
        DebugLog.w(TAG, "VPN consent revoked or taken over by another app")
        stopTunnel()
        reportProblem(TunnelProblem.DISPLACED, getString(R.string.status_displaced))
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        forwardExecutor.shutdownNow()
        VpnStatus.down()
        super.onDestroy()
    }

    private fun timeLabel(epochMillis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))

    companion object {
        const val ACTION_STOP = "dev.malachi.net.STOP"
        const val ACTION_PAUSE = "dev.malachi.net.PAUSE"
        const val ACTION_RESUME = "dev.malachi.net.RESUME"

        /** How long the notification's pause action suspends filtering. */
        const val PAUSE_MILLIS = 15 * 60 * 1000L

        private const val TAG = "MalachiVpn"

        // Documentation-only ranges (RFC 5737 / RFC 3849 style private space): addresses that
        // exist only inside this tunnel and can never collide with something real.
        private const val TUN_IPV4 = "10.111.222.1"
        private const val DNS_IPV4 = "10.111.222.2"
        private const val TUN_IPV6 = "fd00:6d61:6c61:6368::1"
        private const val DNS_IPV6 = "fd00:6d61:6c61:6368::2"

        private const val DNS_PORT = 53
        private const val MTU = 4096
        private const val UPSTREAM_BUFFER = 4032
        private const val UPSTREAM_TIMEOUT_MS = 5_000
        private const val FORWARD_THREADS = 8
        private const val NOTIFICATION_REFRESH_MS = 10_000L
        private const val UNROUTABLE_LOG_INTERVAL_MS = 60_000L

        /**
         * Resolvers apps embed to escape network-level filtering. Routed only at the highest
         * bypass-guard setting, because catching them means their non-DNS traffic dies too.
         */
        private val PUBLIC_RESOLVERS = listOf(
            "8.8.8.8", "8.8.4.4", // Google
            "1.1.1.1", "1.0.0.1", // Cloudflare
            "9.9.9.9", "149.112.112.112", // Quad9
            "208.67.222.222", "208.67.220.220", // OpenDNS
            "94.140.14.14", "94.140.15.15", // AdGuard
            "77.88.8.8", "77.88.8.1", // Yandex
            "2001:4860:4860::8888", "2001:4860:4860::8844",
            "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2620:fe::fe", "2620:fe::9",
        )

        /**
         * Parses a literal address, and only a literal address. The numeric check is what keeps
         * a hostname typed into the custom-resolver box from turning this into a blocking DNS
         * lookup on a network callback thread — the one place a lookup must never happen.
         */
        private fun numericAddress(text: String): InetAddress? {
            if (text.isBlank() || !android.net.InetAddresses.isNumericAddress(text)) return null
            return runCatching { InetAddress.getByName(text) }.getOrNull()
        }
    }
}
