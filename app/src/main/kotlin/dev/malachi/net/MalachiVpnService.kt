package dev.malachi.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
 * **Fail open, everywhere.** A packet we can't parse, an app we can't attribute, an upstream
 * that doesn't answer — every one of those forwards or drops rather than synthesising a refusal.
 * The worst outcome of a bug here is an ad; the worst outcome of the opposite policy is a phone
 * with no working DNS and no obvious culprit.
 *
 * **This service is alive for weeks at a time, so idle cost is the design constraint.** At rest
 * it is one thread parked in `poll()` on the tun — woken by the kernel when a packet arrives,
 * never by a clock — and nothing else: no timer, no wakeup, no allocation. See [readLoop] for
 * why it is `poll()` and not a read. There is no periodic work of any kind, and no ongoing
 * notification to keep current. A lookup that is blocked never leaves the read loop; only
 * a lookup that has to be forwarded costs a thread hand-off, and the sockets it uses are pooled
 * so the per-query cost is a send and a receive rather than a socket, a bind and a `protect()`
 * round trip into the system server.
 */
class MalachiVpnService : VpnService() {

    /**
     * Same reasoning as the application scope: a stray exception in here would otherwise reach
     * the default handler and kill the filter. Logged and survived instead — the settings
     * collector in particular runs for the entire life of the tunnel.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, error -> DebugLog.e(TAG, "service task failed", error) },
    )

    /**
     * Forwarding is blocking I/O with a timeout, and a burst of lookups when an app launches can
     * be dozens at once. Core threads time out, so a phone that isn't resolving anything holds
     * no threads at all — the pool used to keep eight alive for the life of the process.
     */
    private val forwarders = ThreadPoolExecutor(
        FORWARD_THREADS, FORWARD_THREADS, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(FORWARD_QUEUE),
    ) { r -> Thread(r, "malachi-dns").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }

    private lateinit var app: MalachiApplication
    private lateinit var cm: ConnectivityManager

    @Volatile private var settings = MalachiSettings()
    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var tunnelShape: String? = null
    @Volatile private var output: FileOutputStream? = null
    @Volatile private var readerThread: Thread? = null
    private val writeLock = Any()

    /**
     * Serialises everything that starts or stops a tunnel.
     *
     * Four different threads can ask for one: the settings collector, a backoff retry, the
     * platform's `onRevoke` (which arrives on the main thread) and `onDestroy`. Without this
     * they interleave — two `establish()` calls, or a descriptor from one attempt paired with
     * the output stream of another — and the symptom is a tunnel that is up according to every
     * field except the one that matters.
     *
     * The read loop never takes it. Its teardown is handed to [scope] instead, so [stopTunnel]
     * can wait for that thread while holding this lock without the two deadlocking.
     */
    private val tunnelLock = Any()

    /**
     * Whether anything still needs to know *which* app asked. Attribution is a binder round trip
     * into the system server on every single lookup, and it buys nothing when the query log is
     * off and no per-app rule exists — which is the default configuration.
     */
    @Volatile private var attributionNeeded = false

    /** DNS servers of the underlying (non-VPN) network, refreshed by the network callback. */
    @Volatile private var networkDnsServers: List<InetAddress> = emptyList()
    @Volatile private var privateDnsActive = false
    @Volatile private var privateDnsHost: String? = null

    @Volatile private var upstreams: List<InetAddress> = emptyList()

    /** UID → package name. Stable for the life of an install, and asked for on every lookup. */
    private val uidPackages = ConcurrentHashMap<Int, String>()

    /**
     * Pooled, already-protected upstream sockets, one per resolver. `protect()` is what keeps a
     * forwarded query from being routed back into our own tunnel, and it is the expensive part;
     * see [UpstreamSockets] for why they are also connected.
     */
    private val sockets = UpstreamSockets(capacity = FORWARD_THREADS) { target ->
        runCatching {
            DatagramSocket().also { socket ->
                if (!protect(socket)) {
                    socket.close()
                    return@runCatching null
                }
                socket.connect(target, DNS_PORT)
            }
        }.getOrNull()
    }

    private var resumeJob: Job? = null
    private var retryJob: Job? = null
    private var retryAttempt = 0

    /**
     * The most recent start request, so a stop can decline to swallow one that arrived after it.
     * See the stand-down in [applySettings].
     */
    @Volatile private var lastStartId = 0

    // Written from the read loop and from the forwarders' rejection path. Volatile rather than
    // locked: this is a rate limiter for a log line, and losing a count to a race costs nothing.
    @Volatile private var lastUnroutableLogMs = 0L
    @Volatile private var droppedSinceLog = 0L

    /** Write end of the read loop's self-pipe; see [readLoop]. */
    @Volatile private var wakeWrite: FileDescriptor? = null
    private var foregroundStarted = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            adoptNetwork(network, linkProperties)
        }

        override fun onLost(network: Network) {
            networkDnsServers = emptyList()
            upstreams = resolveUpstreams()
        }
    }

    /**
     * A UID's package name is cached for the life of the tunnel ([uidPackages]) because it is
     * asked for on every lookup. That is only true while the set of installed apps holds still:
     * Android recycles UIDs, so a cache kept across an uninstall can attribute a lookup — and
     * therefore apply a per-app rule — to an app that is no longer there. Package changes are
     * rare, so throwing the whole cache away is cheaper than being clever about it.
     */
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            uidPackages.clear()
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as MalachiApplication
        cm = getSystemService(ConnectivityManager::class.java)
        runCatching { FilterNotifications.ensureChannel(this) }
        registerNetworkCallback()
        registerPackageChanges()

        // No startForeground here, and none while filtering: once the tunnel is up the platform
        // binds to this service as the active VPN and that is what keeps the process alive. The
        // status bar already carries the system's VPN key, so a notification of our own would be
        // a second permanent indicator saying the same thing.
        // The flow keeps itself alive across a storage failure; see SettingsStore.settings.
        scope.launch { app.settingsStore.settings.collect { applySettings(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Started from the background, so the platform is owed a notification within seconds —
        // whatever state we happen to be in. This used to be skipped when a tunnel was already
        // up, which is a promise broken: the caller checked before the tunnel came up, and the
        // system answers a missing startForeground with a crash rather than a warning.
        if (intent?.getBooleanExtra(EXTRA_TRANSIENT_FOREGROUND, false) == true) {
            promote(FilterNotifications.starting(this))
            if (tunnel != null) demote()
        }
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    app.settingsStore.update { it.copy(filteringEnabled = false, pausedUntilMs = 0) }
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME -> scope.launch { app.settingsStore.update { it.copy(pausedUntilMs = 0) } }
            // Any other start is a request to look again — from the watchdog, from a receiver,
            // from the launcher. The settings flow has no reason to emit just because somebody
            // asked, so without this a service that is alive but not filtering (a pause whose
            // timer has not fired, a retry that never came round) would stay that way until
            // something edited a setting. Recovery has to have a path that doesn't depend on
            // a clock this process may have slept through.
            else -> if (tunnel == null) {
                scope.launch { applySettings(app.settingsStore.current()) }
            }
        }
        // START_STICKY: if the system reclaims this process, the filter comes back by itself. It
        // re-reads the settings on create, so a restart lands in the right state.
        return START_STICKY
    }

    /**
     * Reacts to a settings change. Which of the two things it does matters:
     *
     * The set of apps in scope and the bypass routes are baked into the tun when it is built and
     * cannot be changed afterwards, so those need a rebuild — a visible blink of unfiltered DNS.
     * Rules and lists are read per query from [dev.malachi.filter.FilterRepository], so those take
     * effect on the next lookup with no interruption. [MalachiSettings.tunnelShape] tells the
     * two apart.
     */
    private fun applySettings(next: MalachiSettings): Unit = synchronized(tunnelLock) {
        val previous = settings
        settings = next
        QueryLog.recording = next.queryLogEnabled
        // Turning the log off has to forget what it already holds. Leaving it would keep the
        // last five hundred domains in memory, and on screen, after the one feature whose whole
        // argument is privacy had been switched off.
        if (TunnelPolicy.forgetsQueryLog(previous, next)) QueryLog.clearRecords()
        attributionNeeded = TunnelPolicy.attributionNeeded(next)

        resumeJob?.cancel()
        when (val action = TunnelPolicy.decide(next, tunnel != null, tunnelShape, System.currentTimeMillis())) {
            TunnelAction.StandDown -> {
                stopTunnel()
                // stopSelf(startId), not stopSelf(): a stop that ignores the id also swallows a
                // start request that arrived while the stop was being decided, and the service
                // then dies with the filter switched on and nothing left to notice. Toggling the
                // filter off and straight back on is exactly when that happens — measured, on a
                // device, as a cycle that never came back up.
                if (lastStartId != 0) stopSelf(lastStartId) else stopSelf()
            }
            is TunnelAction.Pause -> {
                cancelRetry()
                stopTunnel()
                // The one moment we do need to be a foreground service: there is no tunnel for
                // the platform to hold on to, and the resume has to survive the next quarter of
                // an hour.
                promote(FilterNotifications.paused(this, timeLabel(action.untilMs)))
                // Nothing else would wake us: the settings flow has no reason to emit again just
                // because a moment in the future has arrived. And this timer runs on a clock
                // that stops while the device is suspended, which is why onStartCommand looks
                // again on any start — see the comment there.
                resumeJob = scope.launch {
                    delay(TunnelPolicy.pauseRemainingMs(action.untilMs, System.currentTimeMillis()))
                    app.settingsStore.update { if (it.isPaused()) it.copy(pausedUntilMs = 0) else it }
                }
            }
            TunnelAction.Rebuild -> startTunnel(next)
            TunnelAction.LeaveRunning -> Unit
        }
    }

    /** Always under [tunnelLock]; every caller either holds it or goes through [applySettings]. */
    private fun startTunnel(settings: MalachiSettings): Unit = synchronized(tunnelLock) {
        cancelRetry()
        stopTunnel()

        // Checked before the tunnel rather than after it fails: each of these has a remedy the
        // user has to carry out somewhere else, and the symptom is otherwise silence. Always-on
        // only fires when the platform actually named the holder; see VpnController.AlwaysOn.
        val refusal = TunnelPolicy.refusal(
            settings,
            alwaysOnHeldElsewhere = VpnController.alwaysOn(this) is VpnController.AlwaysOn.Other,
            hasConsent = VpnController.hasConsent(this),
        )
        if (refusal != null) {
            reportProblem(refusal.problem(), getString(refusal.message()))
            return
        }

        val pfd = runCatching { build(settings) }
            .onFailure { DebugLog.e(TAG, "could not build the tunnel", it) }
            .getOrNull()
        if (pfd == null) {
            // establish() says only "no". Which "no" it is decides whether waiting can help;
            // TunnelPolicy.diagnose is where that judgement lives.
            when (
                val failure = TunnelPolicy.diagnose(
                    hasConsent = VpnController.hasConsent(this),
                    anotherVpnActive = VpnController.anotherVpnActive(this),
                )
            ) {
                is StartFailure.Report -> reportProblem(failure.problem, getString(failure.problem.message()))
                is StartFailure.Retry -> scheduleRetry(failure.problem, getString(failure.problem.message()))
            }
            return
        }

        tunnel = pfd
        tunnelShape = settings.tunnelShape()
        output = FileOutputStream(pfd.fileDescriptor)
        upstreams = resolveUpstreams()
        retryAttempt = 0
        QueryLog.reset()
        demote()
        FilterNotifications.cancelProblem(this)
        DebugLog.i(TAG, "tunnel up; upstream=${upstreamLabel()} scope=${settings.scopeMode}")

        // A dedicated thread rather than a coroutine: the read is a blocking syscall that only
        // returns when a packet arrives, and parking a pooled dispatcher thread on it forever is
        // how you starve every other coroutine in the process. Parked in read() it costs nothing.
        // The shutdown pipe is made here, before the thread exists, and not inside the loop.
        // Made in there, a tunnel stopped in the moment between start() and the loop's first
        // instruction found no pipe to write to: the wake was lost, the join timed out, the
        // descriptor was closed anyway — and poll() does not wake when its descriptor closes, so
        // that thread stayed parked for the life of the process, holding its fds. Measured on a
        // device as one leaked read loop and five leaked descriptors per five on/off cycles.
        val wake = runCatching { Os.pipe() }
            .onFailure { DebugLog.w(TAG, "no shutdown pipe; falling back to a polled timeout", it) }
            .getOrNull()
        wakeWrite = wake?.get(1)

        readerThread = Thread({ readLoop(pfd, wake) }, "malachi-tun").apply {
            isDaemon = true
            // A crash in here used to end filtering silently, leaving a green light over a
            // tunnel that had stopped reading.
            setUncaughtExceptionHandler { _, error ->
                DebugLog.e(TAG, "the read loop died", error)
                failTunnel(pfd)
            }
            start()
        }
        // Reported only now that something is reading it. Announced before the loop started, the
        // status was true of the descriptor and not of the filter — a window in which the app
        // said it was filtering and nothing was looking at a packet.
        VpnStatus.up(upstreamLabel(), privateDnsActive, privateDnsHost)
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
     * more and risks more. That is why this is a dial the user sets and not a default we choose.
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
            .filterNot { it.hostAddress.orEmpty() in SENTINELS }
            .forEach { address ->
                runCatching { builder.addRoute(address, if (address is Inet4Address) 32 else 128) }
                    .onFailure { DebugLog.w(TAG, "cannot route ${address.hostAddress}", it) }
            }
    }

    /**
     * The read loop. Everything that can be decided without blocking is decided right here —
     * parsing, attribution, the verdict, and writing a refusal back — so a blocked lookup never
     * costs a thread hand-off, a coroutine, or a copy of the packet. Only a lookup that has to
     * be forwarded, which is the one that was going to wait on the network anyway, is handed off.
     */
    private fun readLoop(pfd: ParcelFileDescriptor, wake: Array<FileDescriptor>?) {
        val tunFd = pfd.fileDescriptor
        val buffer = ByteArray(MTU)

        // `establish()` hands back a non-blocking descriptor, so a plain read() returns 0
        // immediately whenever no packet is waiting. Looping on that — which is what a
        // stream-shaped read loop does — spins a core flat out for as long as the filter is on;
        // measured at 97% of one core on an idle phone with the screen off. So the thread waits
        // in poll() instead, which parks it in the kernel until a packet actually arrives.
        //
        // [wake] is a self-pipe, handed in already made. poll() is not interruptible and closing
        // the tun does not wake it, so shutdown writes a byte there and the wait ends at once
        // instead of being discovered by a timeout — which would be another periodic wakeup,
        // forever. A byte written before this loop reaches poll() is not lost: it sits in the
        // pipe, and the first poll returns immediately.
        val timeout = if (wake == null) FALLBACK_POLL_MS else -1
        val fds = buildList {
            add(StructPollfd().apply { fd = tunFd; events = OsConstants.POLLIN.toShort() })
            wake?.let { add(StructPollfd().apply { fd = it[0]; events = OsConstants.POLLIN.toShort() }) }
        }.toTypedArray()

        try {
            while (tunnel === pfd) {
                fds.forEach { it.revents = 0 }
                try {
                    Os.poll(fds, timeout)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    DebugLog.w(TAG, "poll on the tunnel failed", e)
                    break
                }
                // Somebody asked us to stand down.
                if (fds.size > 1 && fds[1].revents.toInt() != 0) break

                val events = fds[0].revents.toInt()
                if (events and (OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) {
                    DebugLog.w(TAG, "the tunnel closed underneath us")
                    break
                }
                if (events and OsConstants.POLLIN == 0) continue

                val length = try {
                    Os.read(tunFd, buffer, 0, buffer.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                    DebugLog.w(TAG, "reading the tunnel failed", e)
                    break
                }
                if (length <= 0) {
                    DebugLog.w(TAG, "the tunnel reached end of stream")
                    break
                }
                runCatching { handle(buffer, length) }
                    .onFailure { DebugLog.w(TAG, "dropped a packet we could not handle", it) }
            }
        } finally {
            // Only if it is still ours: a tunnel that restarted while this loop was winding down
            // has already installed its own pipe, and clearing that one would leave its reader
            // parked in poll() with no way to be woken.
            if (wakeWrite === wake?.get(1)) wakeWrite = null
            wake?.forEach { fd -> runCatching { Os.close(fd) } }
            failTunnel(pfd)
        }
    }

    /**
     * The read loop ended on its own — the descriptor closed underneath it, or it threw.
     *
     * Handed to [scope] rather than done here on purpose. [stopTunnel] waits for this very
     * thread before it closes the descriptor, so tearing the tunnel down from inside it would
     * have the two waiting for each other. Doing nothing when the tunnel has already moved on
     * is the common case: this also runs on every ordinary shutdown.
     */
    private fun failTunnel(pfd: ParcelFileDescriptor) {
        if (tunnel !== pfd) return
        scope.launch {
            synchronized(tunnelLock) {
                if (tunnel !== pfd) return@launch
                stopTunnel()
                scheduleRetry(TunnelProblem.FAILED, getString(R.string.status_tunnel_closed))
            }
        }
    }

    private fun handle(packet: ByteArray, length: Int) {
        val udp = IpPacket.parseUdp(packet, length) ?: return dropNonDns(packet, length)
        if (udp.destinationPort != DNS_PORT) return dropUnroutable("UDP port ${udp.destinationPort}")

        // The only copy taken per lookup, and only of the DNS message — never of the packet.
        val payload = udp.payload(packet)
        // Not a question we understand: a response, an update, a malformed name. Forward it and
        // let a real resolver be the one to have an opinion.
        val question = DnsMessage.parseQuestion(payload) ?: return dispatchForward(udp, payload)

        val packageName = if (attributionNeeded) ownerPackage(udp) else null
        val verdict = app.filterRepository.decide(question.name, packageName)
        QueryLog.record(question.name, packageName, verdict)
        // Counts only, never a domain: this is the half that survives a restart.
        app.statsStore.record(packageName, verdict.blocked)

        if (verdict.blocked) {
            // Answered inline: no network, no thread, no wait.
            writeToTun(IpPacket.buildUdpResponse(udp, DnsMessage.blockedResponse(payload, question, settings.blockAnswer.toBlockAnswer())))
        } else {
            dispatchForward(udp, payload)
        }
    }

    private fun dispatchForward(request: UdpDatagram, query: ByteArray) {
        // Too short to be a DNS message at all, so there is nothing to relay and no transaction
        // id to match a reply against. Dropped here rather than in the forwarder: every one of
        // these used to cost a socket and a protect() round trip before failing, which is a
        // cheap way for any app on the phone to make the filter churn.
        if (DnsMessage.transactionId(query) == null) return dropUnroutable("a ${query.size}-byte query")
        runCatching { forwarders.execute { forward(request, query) } }
            .onFailure {
                // The queue is full: the network is not keeping up. Dropping is right — the
                // client's own resolver retries, and queueing further would only add latency
                // to a lookup that has already given up.
                dropUnroutable("forward queue full")
            }
    }

    /** Sends the query on to a real resolver and relays the answer back, byte for byte. */
    private fun forward(request: UdpDatagram, query: ByteArray) {
        val wantsIpv6 = request.destinationAddress.size == 16
        val target = TunnelPolicy.pickUpstream(upstreams, wantsIpv6) ?: return
        val socket = sockets.borrow(target) ?: return
        val answer = DnsRelay.exchange(
            socket = socket,
            query = query,
            target = target,
            port = DNS_PORT,
            deadlineMs = SystemClock.elapsedRealtime() + UPSTREAM_TIMEOUT_MS,
            bufferSize = UPSTREAM_BUFFER,
            nowMs = SystemClock::elapsedRealtime,
        )
        // Nothing came back, so the socket may still deliver that answer to whoever borrows it
        // next. It is not put back.
        if (answer == null) {
            runCatching { socket.close() }
            return
        }
        sockets.give(socket)
        writeToTun(IpPacket.buildUdpResponse(request, resolveTruncated(answer, query, target)))
    }

    /**
     * If the upstream truncated its answer it expects the client to ask again over TCP — which
     * this tunnel does not carry, so that retry would vanish into it. We make the TCP query
     * ourselves and hand back the complete answer over UDP instead. Rare (mostly DNSSEC and
     * large TXT records), and the alternative is a lookup that hangs with no explanation.
     */
    private fun resolveTruncated(answer: ByteArray, query: ByteArray, target: InetAddress): ByteArray {
        if (answer.size < DNS_HEADER_BYTES || (answer[2].toInt() and 0x02) == 0) return answer
        val full = runCatching {
            Socket().use { socket ->
                protect(socket)
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                socket.connect(InetSocketAddress(target, DNS_PORT), UPSTREAM_TIMEOUT_MS)
                val out = socket.getOutputStream()
                out.write(byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()))
                out.write(query)
                out.flush()
                val input = socket.getInputStream()
                val header = ByteArray(2)
                if (input.read(header) != 2) return@runCatching null
                val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
                if (size !in 1..UPSTREAM_BUFFER) return@runCatching null
                val body = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(body, read, size - read)
                    if (n < 0) return@runCatching null
                    read += n
                }
                body
            }
        }.getOrNull()
        // Only worth swapping in if it actually fits back down the tunnel.
        return if (full != null && full.size + IP_UDP_OVERHEAD <= MTU) full else answer
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
        // The stream is read *inside* the lock, not before it: read outside, a writer could be
        // holding a stream that stopTunnel has already closed and be about to write to a
        // descriptor number the kernel has since given to something else.
        synchronized(writeLock) {
            val stream = output ?: return
            runCatching { stream.write(packet) }
        }
    }

    /**
     * A packet that isn't a UDP datagram we can act on.
     *
     * Most of these are neighbour discovery, and those are not news: they arrive on every tun
     * that has ever existed, and the rate limiter below still let one line through every minute
     * for the entire life of the filter — which is a capped debug log spent entirely on the one
     * message that never means anything. They are dropped in silence. What is still worth
     * saying is the rest: TCP to a routed resolver, or QUIC, which is what the bypass guard is
     * about and what a user debugging a broken app needs to see.
     */
    private fun dropNonDns(packet: ByteArray, length: Int) {
        if (IpPacket.protocol(packet, length) in IpPacket.ROUTINE_ON_A_TUN) return
        dropUnroutable(describe(packet, length))
    }

    /**
     * Something reached the tun that we can't carry and won't pretend to. Logged at most once a
     * minute: a chatty app would otherwise fill the debug log, and writing to it is a file
     * append.
     */
    private fun dropUnroutable(what: String) {
        droppedSinceLog++
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnroutableLogMs < UNROUTABLE_LOG_INTERVAL_MS) return
        lastUnroutableLogMs = now
        DebugLog.i(TAG, "dropped $droppedSinceLog packet(s) the tunnel can't carry, most recently $what")
        droppedSinceLog = 0
    }

    /** IP version and protocol of a packet we declined, for the debug log. */
    private fun describe(packet: ByteArray, length: Int): String {
        if (length < 1) return "empty"
        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> if (length >= 10) "IPv4 proto ${packet[9].toInt() and 0xFF}" else "IPv4 short"
            6 -> if (length >= 7) "IPv6 next-header ${packet[6].toInt() and 0xFF}" else "IPv6 short"
            else -> "not IP"
        }
    }

    /**
     * Watches the network our forwarded queries actually travel over.
     *
     * The *default* network specifically, not every network matching a request — which is what
     * this used to do, and it meant the last network to say anything won. With Wi-Fi and mobile
     * both up, or mid-handover, Malachi would adopt the resolvers of a network it wasn't
     * sending anything over: our upstream sockets are protected, so they leave by the default
     * route, and a resolver reachable only on the other network turns every lookup into a
     * five-second timeout. Malachi is always outside its own tunnel, so the default network
     * here is the real one underneath it.
     */
    private fun registerNetworkCallback() {
        runCatching { cm.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { DebugLog.w(TAG, "cannot watch the underlying network", it) }
    }

    private fun registerPackageChanges() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching {
            ContextCompat.registerReceiver(this, packageChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { DebugLog.w(TAG, "cannot watch package changes", it) }
    }

    private fun adoptNetwork(network: Network, linkProperties: LinkProperties) {
        // Belt and braces against ever pointing the filter at itself: our own tunnel is a
        // network too, and forwarding into it would be a loop with no exit.
        if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return
        networkDnsServers = linkProperties.dnsServers.orEmpty()
        privateDnsActive = linkProperties.isPrivateDnsActive
        privateDnsHost = linkProperties.privateDnsServerName
        VpnStatus.privateDns(privateDnsActive, privateDnsHost)
        upstreams = resolveUpstreams()
        // Tells the system which network our forwarded queries really travel over, so they are
        // billed and routed correctly instead of appearing to come from the tunnel.
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
        // Pooled sockets are bound to the network that existed when they were made.
        sockets.closeAll()

        if (tunnel != null) {
            VpnStatus.up(upstreamLabel(), privateDnsActive, privateDnsHost)
        } else if (settings.isFiltering() && VpnStatus.status.value.problem == TunnelProblem.DISPLACED) {
            // A network came back and we are still displaced: the other VPN may well have been
            // what changed. Worth one attempt now rather than waiting out a backoff that may
            // already have grown to minutes — so the pending one is dropped, not joined.
            cancelRetry()
            retryAttempt = 0
            scheduleRetry(TunnelProblem.DISPLACED, getString(R.string.status_displaced), immediate = true)
        }
    }

    /**
     * Where allowed lookups go. Resolved to addresses once, because doing it per query would put
     * a settings read and a parse on the hot path for an answer that changes about as often as
     * the user changes networks.
     */
    private fun resolveUpstreams(): List<InetAddress> = TunnelPolicy.resolveUpstreams(
        upstream = settings.upstream,
        customUpstream = settings.customUpstream,
        networkDnsServers = networkDnsServers,
        sentinels = SENTINELS,
        parse = ::numericAddress,
    )

    private fun upstreamLabel(): String = when (settings.upstream) {
        UpstreamDns.SYSTEM -> getString(R.string.upstream_system)
        else -> upstreams.firstOrNull()?.hostAddress ?: getString(R.string.upstream_system)
    }

    /**
     * Drops out of the foreground once the tunnel is up, taking the notification with it. The
     * platform's binding to the active VPN is what keeps the process alive from here.
     */
    private fun demote() {
        if (!foregroundStarted) return
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
    }

    private fun promote(notification: android.app.Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                FilterNotifications.NOTIFICATION_ID,
                notification,
                // The type has to be one the running platform knows, and `specialUse` only
                // exists from Android 14. Below that the call takes no type at all — passing one
                // the system can't match against the manifest is rejected outright, which would
                // take the whole filter down on every Android 10-13 device.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
            foregroundStarted = true
        }.onFailure { DebugLog.w(TAG, "could not go foreground", it) }
    }

    /**
     * Tries again later, with a backoff that grows to minutes.
     *
     * Only for causes that time can actually fix — another VPN letting go, a network coming
     * back. A missing consent is not one of those: retrying it would burn wakeups forever on a
     * dialog only the user can answer, so [reportProblem] handles that case by stopping.
     */
    private fun scheduleRetry(problem: TunnelProblem, message: String, immediate: Boolean = false) {
        if (retryJob?.isActive == true) return
        val wait = if (immediate) 0L else TunnelPolicy.retryDelayMs(retryAttempt)
        retryAttempt++
        DebugLog.w(TAG, "filter not running: $message; retrying in ${wait / 1000}s")
        VpnStatus.down(problem, message, retrying = true)
        // A transient foreground start that ends in a retry must not leave "Starting…" pinned to
        // the status bar for the whole backoff — which, at the top of it, is five minutes.
        demote()
        retryJob = scope.launch {
            delay(wait)
            val current = app.settingsStore.current()
            if (current.isFiltering()) startTunnel(current)
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    /** A problem no amount of retrying will fix; the UI offers the action that will. */
    private fun reportProblem(problem: TunnelProblem, message: String) {
        DebugLog.w(TAG, "filter not running: $message")
        VpnStatus.down(problem, message)
        // The tunnel is not coming up, so a transient foreground start has nothing left to wait
        // for: without this its "Starting…" notification stayed up forever, next to the problem
        // notification posted below — two at once, one of them the permanent one this app
        // deliberately doesn't have.
        demote()
        // Only for the states a person has to resolve; a retryable hiccup stays quiet.
        FilterNotifications.postProblem(this, message)
    }

    /**
     * Takes the tunnel down, and does not return until nothing can still be using it.
     *
     * The waiting is the point. Closing the descriptor while the read loop is between its
     * `poll()` and its `read()`, or while a forwarder is inside `write()`, does not merely lose
     * that packet: the descriptor number is free the instant it is closed and the kernel hands
     * it to whatever this process opens next — an upstream socket, a settings file — so the
     * read or the write lands on something else entirely. Rare, and the sort of thing that is
     * never diagnosed from the outside. So the writers are locked out first, then the reader is
     * joined, and only then is the descriptor closed.
     */
    private fun stopTunnel(): Unit = synchronized(tunnelLock) {
        val pfd = tunnel ?: return
        tunnel = null
        tunnelShape = null
        // Ends the poll() the read loop is parked in. Closing the tun would not: poll has no
        // idea the descriptor went away, and would sit there until something else arrived.
        runCatching { wakeWrite?.let { Os.write(it, byteArrayOf(1), 0, 1) } }

        // Under the same lock the writers hold, so no forwarder can be inside write() once this
        // returns, and any that arrives later finds no stream and drops.
        synchronized(writeLock) {
            runCatching { output?.close() }
            output = null
        }

        val reader = readerThread
        readerThread = null
        // Never from the read loop itself — its teardown goes through failTunnel for exactly
        // this reason — but the guard costs nothing and a self-join would hang the tunnel.
        if (reader != null && reader !== Thread.currentThread()) {
            reader.join(READER_JOIN_MS)
            if (reader.isAlive) DebugLog.w(TAG, "the read loop did not stop in time")
        }

        runCatching { pfd.close() }
        sockets.closeAll()
        // The flush cadence is a lookup count, so a tunnel that stops has to push what is left.
        runCatching { app.statsStore.flush() }
        VpnStatus.down()
    }

    /**
     * The system took the tunnel away — another VPN was started, or the user withdrew consent.
     * Without handling it the service would keep "running" over a dead descriptor and the app
     * would show a filter that was quietly filtering nothing.
     */
    /**
     * The system took the tunnel away — another VPN was started, or the user withdrew consent.
     *
     * Deliberately *not* calling through to `super`, whose implementation is `stopSelf()`. That
     * destroys the service, and `onDestroy` cancels the very retry scheduled two lines earlier —
     * so the documented recovery from "another VPN took over, and then let go" never happened:
     * it waited for the half-hourly watchdog instead. The service stays up to retry, and gives
     * up by itself when the cause turns out to be a withdrawn consent (see [startTunnel]).
     *
     * Off the calling thread because this arrives on the main one and [stopTunnel] waits for the
     * read loop.
     */
    override fun onRevoke() {
        DebugLog.w(TAG, "VPN consent revoked or taken over by another app")
        scope.launch {
            synchronized(tunnelLock) {
                stopTunnel()
                // Retried rather than reported: the usual cause is another VPN connecting, and
                // that is exactly the kind of thing that stops again on its own.
                scheduleRetry(TunnelProblem.DISPLACED, getString(R.string.status_displaced))
            }
        }
    }

    override fun onDestroy() {
        stopTunnel()
        cancelRetry()
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(packageChanges) }
        scope.cancel()
        forwarders.shutdownNow()
        VpnStatus.down()
        super.onDestroy()
    }

    private fun timeLabel(epochMillis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))

    private fun StartRefusal.problem(): TunnelProblem = when (this) {
        StartRefusal.NO_APPS_SELECTED -> TunnelProblem.NO_APPS_SELECTED
        StartRefusal.ALWAYS_ON_ELSEWHERE -> TunnelProblem.ALWAYS_ON_ELSEWHERE
        StartRefusal.NO_CONSENT -> TunnelProblem.NO_CONSENT
    }

    private fun StartRefusal.message(): Int = problem().message()

    /** The one sentence that names each problem. Kept out of [TunnelPolicy] so it stays pure. */
    private fun TunnelProblem.message(): Int = when (this) {
        TunnelProblem.NO_APPS_SELECTED -> R.string.status_no_apps_selected
        TunnelProblem.ALWAYS_ON_ELSEWHERE -> R.string.status_always_on_elsewhere
        TunnelProblem.NO_CONSENT -> R.string.status_no_consent
        TunnelProblem.DISPLACED -> R.string.status_displaced
        else -> R.string.status_tunnel_closed
    }

    companion object {
        const val ACTION_STOP = "dev.malachi.net.STOP"
        const val ACTION_RESUME = "dev.malachi.net.RESUME"

        /** Set when the caller could only start us as a foreground service; see [demote]. */
        const val EXTRA_TRANSIENT_FOREGROUND = "transient_foreground"

        /** How long the notification's pause action suspends filtering. */
        const val PAUSE_MILLIS = 15 * 60 * 1000L

        private const val TAG = "MalachiVpn"

        // Private space: addresses that exist only inside this tunnel and can never collide
        // with something real.
        private const val TUN_IPV4 = "10.111.222.1"
        private const val DNS_IPV4 = "10.111.222.2"
        private const val TUN_IPV6 = "fd00:6d61:6c61:6368::1"
        private const val DNS_IPV6 = "fd00:6d61:6c61:6368::2"

        /**
         * The addresses that exist only inside the tunnel. Routing one of them upstream, or
         * offering one as a resolver to forward to, would be a loop with no exit.
         */
        private val SENTINELS = setOf(DNS_IPV4, DNS_IPV6)

        private const val DNS_PORT = 53
        private const val DNS_HEADER_BYTES = 12
        private const val MTU = 4096
        private const val UPSTREAM_BUFFER = 4032
        private const val IP_UDP_OVERHEAD = 48
        private const val UPSTREAM_TIMEOUT_MS = 5_000
        private const val FORWARD_THREADS = 4
        private const val FORWARD_QUEUE = 128
        private const val UNROUTABLE_LOG_INTERVAL_MS = 60_000L

        /**
         * How long a shutdown waits for the read loop. It is woken by a byte down the self-pipe
         * and returns in microseconds; this only bites on a device where the pipe couldn't be
         * made and the loop is on the fallback poll timeout instead.
         */
        private const val READER_JOIN_MS = 2_000L

        /** Only used if the shutdown pipe could not be made; see [readLoop]. */
        private const val FALLBACK_POLL_MS = 1_000


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
