package dev.malachi.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.malachi.MainActivity
import dev.malachi.MalachiApplication
import dev.malachi.R
import dev.malachi.data.AppScopeMode
import dev.malachi.data.BypassGuard
import dev.malachi.data.MalachiSettings
import dev.malachi.data.UpstreamDns
import dev.malachi.debug.DebugLog
import dev.malachi.filter.AppTrace
import dev.malachi.filter.QueryLog
import dev.malachi.filter.TraceReason
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

    /**
     * Wall clock until which every lookup is narrated into the in-memory log; 0 when it is not.
     * Zero is checked first so the normal case is one volatile read and a comparison.
     */
    @Volatile private var traceUntilMs = 0L

    /**
     * The app whose every query is written into [AppTrace], and when that stops. Null costs one
     * volatile read per lookup, which is what the whole feature costs while nobody is diagnosing.
     */
    @Volatile private var traceApp: String? = null
    @Volatile private var traceAppUntilMs = 0L

    /** DNS servers of the underlying (non-VPN) network, refreshed by the network callback. */
    @Volatile private var networkDnsServers: List<InetAddress> = emptyList()

    /** The network the current resolvers came from, for the diagnostics header. */
    @Volatile private var activeNetwork: Network? = null

    /**
     * Its interface name and when it was adopted, both for the diagnostics header.
     *
     * "These are `rmnet16`'s resolvers and they were adopted eleven hours ago" is the whole
     * diagnosis of a phone that resolves nothing on a Wi-Fi, and it did not fit in the header
     * that was there — which named the addresses without saying whose they were or how old.
     */
    @Volatile private var networkLabel: String = ""
    @Volatile private var adoptedAtMs = 0L
    @Volatile private var lastResolverRecheckMs = 0L

    /**
     * Bumped whenever the resolvers change under us, so a lookup already in flight can tell.
     *
     * A forward holds the list it started with. On a phone that changes network in the middle of
     * one — which is the whole of walking around a house with bad Wi-Fi — the remaining resolvers
     * in that list belong to a network that is gone, and spending the rest of a five-second
     * budget on them delays the client's retry by five seconds for nothing.
     */
    @Volatile private var networkGeneration = 0

    /**
     * Whether the platform will pick the best non-VPN network for us rather than reporting all of
     * them. `registerBestMatchingNetworkCallback` is Android 12; below that we rank them
     * ourselves. See [registerNetworkCallback].
     */
    private val platformPicksBest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    @Volatile private var lastLockdown = false
    @Volatile private var privateDnsActive = false
    @Volatile private var privateDnsHost: String? = null

    @Volatile private var upstreams: List<InetAddress> = emptyList()

    /** The resolver that last answered, tried first next time. See [forward]. */
    @Volatile private var lastGoodUpstream: InetAddress? = null
    @Volatile private var lastSilentUpstreamLogMs = 0L
    @Volatile private var lastSocketTroubleLogMs = 0L

    /** Consecutive refusals of [pin]; at [PIN_ATTEMPTS] the tunnel stops asking. */
    @Volatile private var pinFailures = 0
    @Volatile private var lastUnvalidatedLogMs = 0L
    @Volatile private var lastLockdownCheckMs = 0L

    /** When a network we are *not* using last made us go and decide again; see the callback. */
    @Volatile private var lastUnderlyingRecheckMs = 0L

    /** UID → package name. Stable for the life of an install, and asked for on every lookup. */
    private val uidPackages = ConcurrentHashMap<Int, String>()

    /**
     * Pooled, already-protected upstream sockets, one per resolver. `protect()` is what keeps a
     * forwarded query from being routed back into our own tunnel, and it is the expensive part;
     * see [UpstreamSockets] for why they are also connected.
     */
    private val sockets = UpstreamSockets(capacity = FORWARD_THREADS) { target -> openUpstream(target) }

    /**
     * Whether an alarm to end a pause is out there. True to begin with, because one set by a
     * previous process outlives it; see [cancelPauseAlarm].
     */
    @Volatile private var pauseAlarmArmed = true

    /**
     * The network last declared to the platform with `setUnderlyingNetworks`, so it is only
     * declared again when it really moved. The call is a binder round trip and the callbacks that
     * reach it fire several times a minute on a phone being carried around.
     *
     * Cleared with the tunnel: the declaration belongs to a tun, so a rebuilt one has none.
     */
    @Volatile private var declaredUnderlying: Network? = null

    private var resumeJob: Job? = null
    private var retryJob: Job? = null
    private var diagnoseJob: Job? = null
    private var retryAttempt = 0

    /**
     * Set once [onDestroy] has run, so nothing can build a tunnel for a service that is gone.
     *
     * Written and read under [tunnelLock], which is what makes it a decision rather than a hope:
     * either a start gets the lock first and establishes — and the teardown behind it joins that
     * read loop — or the teardown gets there first and the start declines.
     */
    @Volatile private var destroyed = false

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
        /**
         * Both of these adopt, and [adoptNetwork] is idempotent, because missing one of them is
         * not a missing log line — it is a phone asking a network's resolvers hours after leaving
         * that network, with every lookup timing out and nothing anywhere saying why.
         */
        override fun onAvailable(network: Network) {
            runCatching { cm.getLinkProperties(network) }.getOrNull()?.let { adoptNetwork(network, it) }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            adoptNetwork(network, linkProperties)
        }

        /**
         * Only the network we are actually asking matters here.
         *
         * This used to wipe the resolvers whichever network went away — and on a handover the
         * platform announces the new default *before* the old one finishes disappearing. So
         * walking out of Wi-Fi range threw away the mobile resolvers that had just been adopted
         * one callback earlier, and every lookup on the phone moved to the fallback resolver
         * without a word: working DNS, sent somewhere the user never chose, until something else
         * happened to change. On a network with names of its own — a router, a NAS — or one that
         * blocks outside resolvers, it is not even working DNS.
         */
        override fun onLost(network: Network) {
            if (network != activeNetwork) return
            activeNetwork = null
            // The replacement is usually already up: this is a handover, not an outage. Adopting
            // it here rather than waiting means the gap is one callback long instead of however
            // long the new network takes to mention its link properties.
            val replacement = runCatching { cm.activeNetwork }.getOrNull()?.let { realNetwork(it) }
            val linkProperties = replacement?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }
            if (replacement != null && linkProperties != null) {
                adoptNetwork(replacement, linkProperties)
                return
            }
            networkDnsServers = emptyList()
            upstreams = resolveUpstreams()
            // Never silent: this is the moment the phone stops asking the resolvers it was given
            // and starts asking the fallback.
            DebugLog.w(
                TAG,
                "the network we were asking has gone and nothing replaced it yet; falling back to " +
                    upstreams.joinToString { it.hostAddress.orEmpty() },
            )
        }
    }

    /**
     * The networks under the tunnel, which is a different question from "what is my default
     * network" and has to be asked separately once the tunnel is up. See [registerNetworkCallback].
     *
     * Every event here is only a prompt to decide again. On Android 12 and up the platform has
     * already decided — it reports its best match and nothing else — so that network is taken as
     * named; below that anything matching reports itself, and choosing the last one to speak is
     * how this app once ended up adopting the resolvers of a network it was sending nothing over.
     */
    private val underlyingCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = adoptUnderlying(network.takeIf { platformPicksBest })

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            adoptUnderlying(network.takeIf { platformPicksBest })

        /**
         * Capabilities move constantly and almost none of it matters here.
         *
         * On mobile this fires as the signal and the bandwidth estimates move — several times a
         * minute on a phone being carried around — and a capability change on the network we are
         * *already* using cannot change its resolvers: those arrive through
         * [onLinkPropertiesChanged], and losing validation makes the network stop matching the
         * request altogether, which arrives as [onLost]. So the common case is a reference
         * comparison instead of the three or four binder round trips an adoption costs, in a
         * process that stays alive for weeks.
         *
         * What is kept is lockdown: these callbacks are the only regular heartbeat this service
         * has for noticing that the user turned "block connections without VPN" on in a screen
         * this app sends them to, and one binder call a minute for that is affordable where one
         * per tick is not.
         */
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != activeNetwork) {
                // On Android 12+ the platform reports only its best match, so this really is a
                // prompt to move and is acted on at once. Below that *every* matching network
                // reports itself — and a capability is a signal strength and a bandwidth
                // estimate, which move constantly on a phone in a pocket. Re-deciding on each of
                // those means `allNetworks`, a capability read per network, a link-properties
                // read and two more binder calls in the adoption, several times a minute, to
                // arrive at the answer we already had. The events that genuinely need to be
                // prompt arrive as onAvailable, onLost and onLinkPropertiesChanged, none of which
                // is throttled, and a failing lookup re-asks within five seconds regardless.
                if (platformPicksBest) return adoptUnderlying(network)
                val now = SystemClock.elapsedRealtime()
                if (now - lastUnderlyingRecheckMs < UNDERLYING_RECHECK_INTERVAL_MS) return
                lastUnderlyingRecheckMs = now
                return adoptUnderlying(null)
            }
            publishLockdown()
        }

        // Whatever went away, the question is the same: which network is under us now.
        override fun onLost(network: Network) = adoptUnderlying(null)
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
        // The flow keeps itself alive across a storage failure; see SettingsStore.settings. The
        // *body* needs the same promise for a different reason: an exception thrown out of a
        // collector ends that collection for good, and this is the only thing in the process that
        // reacts to a settings change. A single throw — a notification an OEM would not let us
        // build, a resource that would not resolve — and the switch in the app writes `false`
        // forever while the tunnel goes on filtering, with nothing anywhere saying why.
        scope.launch {
            app.settingsStore.settings.collect { next ->
                runCatching { applySettings(next) }
                    .onFailure { DebugLog.e(TAG, "could not apply a settings change", it) }
            }
        }
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
            ACTION_RESUME -> scope.launch {
                app.settingsStore.update { it.copy(pausedUntilMs = 0) }
                // DataStore skips the write when the value is unchanged, so clearing a pause that
                // has already lapsed emits nothing and reacts to nothing. Arriving here with no
                // tunnel is precisely the case that needs a look — the alarm that ends a pause
                // can be beaten to the write by anything else and must still leave a filter up.
                if (tunnel == null) applySettings(app.settingsStore.current())
            }
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
        // Only when the filter is switched on, not on every tunnel that comes up. It used to be
        // reset inside startTunnel, which meant changing which apps are covered, toggling the
        // bypass, or a retry after another VPN took the tunnel all threw away every domain the
        // log had ever seen — including the ones somebody had opened this screen to look at.
        if (!previous.filteringEnabled && next.filteringEnabled) QueryLog.reset()
        attributionNeeded = TunnelPolicy.attributionNeeded(next)
        val wasTracing = traceUntilMs != 0L
        traceUntilMs = next.diagnosticsUntilMs
        if (!wasTracing && next.isDiagnosing()) traceEnvironment(next)
        applyAppTrace(next)

        resumeJob?.cancel()
        cancelPauseAlarm()
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
                // The deadline is a wall-clock moment, so it needs a wall clock. `delay` runs on
                // one that stops while the device is suspended: a fifteen minute pause is fifteen
                // minutes *awake*, and with the screen off that is hours. Every reader of
                // `isPaused()` uses the real clock, so in between the app says the pause is over
                // while the only thing that would end it is still counting — a home screen
                // spinning on "starting the filter" with nothing starting it. Reported from a
                // phone, which is where this can be seen at all.
                schedulePauseAlarm(action.untilMs)
                // Kept as well as the alarm, not instead of it: while the device is awake this
                // fires on the second, and it costs nothing when the alarm gets there first —
                // both do the same single write, and the second one is a no-op.
                resumeJob = scope.launch {
                    delay(TunnelPolicy.pauseRemainingMs(action.untilMs, System.currentTimeMillis()))
                    app.settingsStore.update { if (it.isPaused()) it.copy(pausedUntilMs = 0) else it }
                }
            }
            TunnelAction.Rebuild -> startTunnel(next)
            TunnelAction.LeaveRunning -> if (TunnelPolicy.upstreamMoved(previous, next)) adoptUpstreams()
        }
    }

    /** Always under [tunnelLock]; every caller either holds it or goes through [applySettings]. */
    private fun startTunnel(settings: MalachiSettings): Unit = synchronized(tunnelLock) {
        // The one check that has to come before everything else. `onDestroy` tears the tunnel
        // down and cancels the scope, but a coroutine already inside this method is not
        // interruptible — it is synchronous from end to end, so cancellation is only noticed at a
        // suspension point that never arrives. Without this, a start that lost the race for the
        // lock goes on to establish a tunnel and start a read loop belonging to a service that no
        // longer exists, and nothing will ever stop it: the only thing that would have was the
        // `onDestroy` that has already been and gone.
        //
        // Diagnosed from an emulator's logcat, not guessed: two service instances created 27ms
        // apart, three tunnels up in 116ms, one read loop still alive a minute later, and — the
        // tell — no "the read loop did not stop in time" anywhere, because no stop was ever run
        // for it at all.
        if (destroyed) return
        cancelRetry()
        stopTunnel()

        // Checked before the tunnel rather than after it fails: each of these has a remedy the
        // user has to carry out somewhere else, and the symptom is otherwise silence. Always-on
        // only fires when the platform actually named the holder; see VpnController.AlwaysOn.
        val refusal = TunnelPolicy.refusal(
            settings,
            alwaysOnHeldElsewhere = VpnController.alwaysOn(this) is VpnController.AlwaysOn.Other,
            hasConsent = VpnController.hasConsent(this),
            selectedAppsPresent = selectedAppsPresent(settings),
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
        publishLockdown(force = true)
        output = FileOutputStream(pfd.fileDescriptor)
        // The declaration belongs to a tun, so a freshly built one has none — and until it is
        // made the platform cannot see what this tunnel is carried by. Declared here rather than
        // waiting for the next network callback, because "up to the next callback" is how long
        // the phone spends unsure whether its connection is metered, and the things that read
        // that are Play Store updates, cloud backups and Data Saver.
        activeNetwork?.let { declareUnderlying(it) }
        upstreams = resolveUpstreams()
        retryAttempt = 0
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
            // **A VPN is metered unless it says otherwise, and this one carries nothing but DNS.**
            // Without this the whole phone believes it is on a metered connection for as long as
            // the filter runs, and the platform acts on that belief: Play Store holds back
            // automatic updates, photo and cloud backups stop, Data Saver restricts background
            // data, streaming apps drop quality. Measured here — the tunnel's capabilities came
            // back without NOT_METERED while the Wi-Fi underneath it had it.
            //
            // It also broke this app from the inside. The blocklist refresh is Wi-Fi-only *by
            // default*, which WorkManager expresses as NetworkType.UNMETERED — a constraint the
            // tunnel itself made permanently unsatisfiable, so on a default install the periodic
            // refresh never ran again once filtering was switched on.
            //
            // false does not mean "pretend it is Wi-Fi": with the underlying networks declared
            // (see adoptNetwork), the platform derives meteredness from what is actually
            // underneath, which is the honest answer for a tunnel that carries only lookups.
            .setMetered(false)
            .apply {
                // The button the system's own VPN dialog shows for configuring a VPN. Without an
                // intent here Android simply omits it, so Malachi's entry in Settings → VPN has a
                // gear that leads nowhere. Nullable only because the compat helper says so; a
                // missing button is not worth refusing to build a tunnel over.
                PendingIntentCompat.getActivity(
                    this@MalachiVpnService,
                    0,
                    Intent(this@MalachiVpnService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                    false,
                )?.let { setConfigureIntent(it) }
            }
            // Generous for a link that carries nothing but DNS: it lets a large DNSSEC or EDNS
            // answer through in one piece instead of forcing a TCP retry this tunnel can't serve.
            .setMtu(MTU)
            // Lets an app that binds a socket to a *particular* network reach it, instead of
            // being held inside a tunnel that has no route there.
            //
            // Without this, Android's rule is absolute: "applications cannot bypass the VPN",
            // whatever the VPN actually routes. Android Auto has to open a socket to the head
            // unit over the link it is plugged into, so it is refused before it starts and
            // reports a communication error blaming "a VPN". The route table is irrelevant —
            // ours carries two sentinel addresses and trips it exactly like one carrying
            // everything.
            //
            // What it costs: an app that deliberately binds to the underlying network resolves
            // through that network's resolver and is not filtered. That is a real hole and it is
            // narrow — ordinary apps, and the ad SDKs inside them, ask the system resolver and
            // land in the tun as before. The bypass guard still catches a hardcoded 8.8.8.8,
            // because hardcoding a resolver and binding to a network are different things and
            // trackers do the first, not the second.
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

        // See MalachiSettings.bypassAllowed: without this an app may not reach a network it
        // binds to, whatever we route, and Android Auto fails before it starts.
        if (settings.bypassAllowed) builder.allowBypass()

        // Not `applyScope(builder, settings)` and on with it: in ONLY_SELECTED a builder that
        // took none of the chosen apps carries no restriction, and Android reads that as "filter
        // everything". Refusing here is refusing to do the opposite of what the screen says.
        val applied = applyScope(builder, settings)
        if (!TunnelPolicy.scopeIsSelective(settings.scopeMode, applied)) {
            DebugLog.e(TAG, "none of the selected apps could be covered; refusing to filter every app instead")
            return null
        }
        applyBypassGuard(builder, settings)

        return builder.establish()
    }

    /**
     * Both directions of "which apps", enforced by the platform rather than by us: an app out of
     * scope never reaches this process at all, which is a stronger promise than a check we
     * perform on its packets, and costs nothing at runtime.
     */
    private fun applyScope(builder: Builder, settings: MalachiSettings): Int {
        var applied = 0
        when (settings.scopeMode) {
            AppScopeMode.ALL_EXCEPT -> {
                // Always outside its own tunnel: Malachi downloads lists and updates, and
                // routing that through the filter it is updating invites a loop.
                (settings.excludedApps + packageName).forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg); applied++ }
                        .onFailure { DebugLog.w(TAG, "cannot exclude $pkg (not installed?)") }
                }
            }
            AppScopeMode.ONLY_SELECTED -> {
                settings.includedApps.filter { it != packageName }.forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg); applied++ }
                        .onFailure { DebugLog.w(TAG, "cannot include $pkg (not installed?)") }
                }
            }
        }
        return applied
    }

    /**
     * How many of the apps the user picked are still on the phone.
     *
     * Asked before the tunnel is built rather than after, because "you chose only these apps and
     * none of them are here any more" is a sentence with a screen behind it, while an
     * `establish()` that quietly did the wrong thing is not. Meaningless in ALL_EXCEPT — every
     * app is in scope there whether or not the exclusions resolve — so that mode answers with a
     * number no check can fail.
     */
    private fun selectedAppsPresent(settings: MalachiSettings): Int {
        if (settings.scopeMode != AppScopeMode.ONLY_SELECTED) return Int.MAX_VALUE
        return settings.includedApps.count { pkg ->
            pkg != packageName && runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess
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
        val question = DnsMessage.parseQuestion(payload)
            ?: return dispatchForward(udp, payload, "(unparsed)", type = 0, traced = false)

        val packageName = if (attributionNeeded) ownerPackage(udp) else null
        val verdict = app.filterRepository.decide(question.name, packageName)
        // One resolution is two or three queries — A, AAAA, and HTTPS from a browser — and
        // counting each of them made a domain somebody looked up once report itself as seen
        // twice. The log decides which of them began the lookup; the statistics follow it, or
        // the two would disagree about the same traffic by a factor of two.
        val newLookup = QueryLog.record(question.name, packageName, verdict, question.type)
        // Counts only, never a domain: this is the half that survives a restart.
        if (newLookup) app.statsStore.record(packageName, verdict.blocked)

        // Decided once and carried down the forward path: the outcome of a forwarded lookup is
        // settled on another thread, and asking again there would be asking about a different
        // moment. One volatile read when nobody is diagnosing anything.
        val traced = tracingApp(packageName)

        if (verdict.blocked) {
            if (tracing()) DebugLog.trace(TAG, "${question.name}: blocked (${verdict.detail})")
            if (traced) AppTrace.blocked(question.name, question.type, verdict.detail, verdict.source)
            // Answered inline: no network, no thread, no wait.
            writeToTun(IpPacket.buildUdpResponse(udp, DnsMessage.blockedResponse(payload, question, settings.blockAnswer.toBlockAnswer())))
        } else {
            dispatchForward(udp, payload, question.name, question.type, traced)
        }
    }

    private fun dispatchForward(
        request: UdpDatagram,
        query: ByteArray,
        name: String,
        type: Int,
        traced: Boolean,
    ) {
        // Too short to be a DNS message at all, so there is nothing to relay and no transaction
        // id to match a reply against. Dropped here rather than in the forwarder: every one of
        // these used to cost a socket and a protect() round trip before failing, which is a
        // cheap way for any app on the phone to make the filter churn.
        if (DnsMessage.transactionId(query) == null) {
            if (traced) AppTrace.dropped(name, type, TraceReason.MALFORMED)
            return dropUnroutable("a ${query.size}-byte query")
        }
        runCatching { forwarders.execute { forward(request, query, name, type, traced) } }
            .onFailure {
                // The queue is full: the network is not keeping up. Dropping is right — the
                // client's own resolver retries, and queueing further would only add latency
                // to a lookup that has already given up.
                if (traced) AppTrace.dropped(name, type, TraceReason.BUSY)
                dropUnroutable("forward queue full")
            }
    }

    /**
     * Sends the query on to a real resolver and relays the answer back, byte for byte — trying
     * each resolver the network offered until one replies.
     *
     * Asking only the first was enough to make an entire Wi-Fi look broken: its router advertised
     * a resolver that never answered, Android's own resolver quietly moved to the second, and
     * Malachi kept asking the silent one and dropping the lookup. The whole budget is still
     * [UPSTREAM_TIMEOUT_MS] — it is divided between the candidates rather than spent on the first
     * of them, so a dud cannot starve the one underneath it.
     */
    private fun forward(request: UdpDatagram, query: ByteArray, name: String, type: Int, traced: Boolean) {
        val wantsIpv6 = request.destinationAddress.size == 16
        val candidates = TunnelPolicy.orderUpstreams(upstreams, wantsIpv6, lastGoodUpstream)
        if (candidates.isEmpty()) return
        val began = SystemClock.elapsedRealtime()
        val deadline = began + UPSTREAM_TIMEOUT_MS
        val generation = networkGeneration

        candidates.forEachIndexed { index, target ->
            // The network moved while we were waiting. Everything left in this list belongs to
            // the network that has gone, so the rest of the budget would be spent on resolvers
            // that cannot answer — and the client's retry, which will use the new ones, is held
            // up for exactly that long.
            if (networkGeneration != generation) {
                if (tracing()) DebugLog.trace(TAG, "$name: the network changed mid-lookup; dropping")
                if (traced) AppTrace.dropped(name, type, TraceReason.NETWORK_CHANGED)
                return dropUnroutable("the network changed mid-lookup")
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return@forEachIndexed
            val loan = sockets.borrow(target) ?: run {
                if (tracing()) DebugLog.trace(TAG, "$name: no socket for ${target.hostAddress}")
                return@forEachIndexed
            }
            val startedAt = SystemClock.elapsedRealtime()
            val answer = DnsRelay.exchange(
                socket = loan.socket,
                query = query,
                target = target,
                port = DNS_PORT,
                deadlineMs = SystemClock.elapsedRealtime() +
                    TunnelPolicy.attemptBudgetMs(remaining, candidates.size - index, MIN_UPSTREAM_ATTEMPT_MS),
                bufferSize = UPSTREAM_BUFFER,
                nowMs = SystemClock::elapsedRealtime,
            )
            if (answer == null) {
                // Nothing came back, so this socket may still deliver that answer to whoever
                // borrows it next. It is not put back.
                runCatching { loan.socket.close() }
                if (tracing()) {
                    DebugLog.trace(
                        TAG,
                        "$name: no answer from ${target.hostAddress} after " +
                            "${SystemClock.elapsedRealtime() - startedAt}ms" +
                            if (index < candidates.lastIndex) ", trying the next" else " (last resolver)",
                    )
                }
                noteSilentUpstream(target)
                return@forEachIndexed
            }
            sockets.give(loan)
            // Remembered so the next lookup starts with the one that works rather than paying
            // the dud's timeout again.
            lastGoodUpstream = target
            // The elapsed time is measured from the start of the *lookup*, not of this attempt:
            // a name that took four seconds because the first DNS server was silent is a name the
            // app waited four seconds for, and reporting the 80ms the second one took would hide
            // the very delay somebody opened this screen to find.
            if (traced) {
                AppTrace.answered(
                    name,
                    type,
                    target.hostAddress.orEmpty(),
                    SystemClock.elapsedRealtime() - began,
                )
            }
            if (tracing()) {
                DebugLog.trace(
                    TAG,
                    "$name: answered by ${target.hostAddress} in " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms, ${answer.size} bytes",
                )
            }
            writeToTun(IpPacket.buildUdpResponse(request, resolveTruncated(answer, query, target)))
            return
        }
        if (tracing()) DebugLog.trace(TAG, "$name: NOTHING ANSWERED — ${candidates.size} resolver(s) tried")
        // The other way an app hangs, and the one no blocklist can explain: the lookup was
        // allowed, it left, and nothing came back. Without this line on the timeline the reader
        // would go on hunting for a domain to exempt that does not exist.
        if (traced) {
            AppTrace.unanswered(
                name,
                type,
                candidates.joinToString { it.hostAddress.orEmpty() },
                SystemClock.elapsedRealtime() - began,
            )
        }
        dropUnroutable("no resolver answered (${candidates.size} tried)")
        // Every resolver we hold is silent. Either the network is down — in which case this
        // costs two binder calls every five seconds and finds nothing — or we are holding the
        // resolvers of a network we have left, which is exactly what it exists to notice.
        recheckResolvers()
    }

    /**
     * Names a resolver that went quiet, at most once a minute.
     *
     * Rate-limited because this is the hot path and a network whose resolver is down produces one
     * of these per lookup — but worth saying at all, because "which resolver stopped answering"
     * is the single fact that explains a phone that resolves nothing on one Wi-Fi and is fine on
     * every other network.
     */
    /**
     * Says a socket went wrong, at most once a minute and never with a stack trace.
     *
     * Learned from a real report: a per-socket failure logged with its throwable is fifteen lines
     * of trace, once per lookup, and a phone that cannot reach its resolvers produces one every
     * time an app breathes. The log filled with identical stacks and evicted the lines that
     * explained what was happening — the diagnostics defeated by their own noise. The class and
     * message are the whole diagnosis here; the frames never varied.
     */
    private fun noteSocketTrouble(what: String) {
        if (tracing()) DebugLog.trace(TAG, what)
        val now = SystemClock.elapsedRealtime()
        if (now - lastSocketTroubleLogMs < SILENT_UPSTREAM_LOG_INTERVAL_MS) return
        lastSocketTroubleLogMs = now
        DebugLog.w(TAG, what)
    }

    private fun noteSilentUpstream(target: InetAddress) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSilentUpstreamLogMs < SILENT_UPSTREAM_LOG_INTERVAL_MS) return
        lastSilentUpstreamLogMs = now
        DebugLog.w(TAG, "no answer from ${target.hostAddress}; trying the next resolver")
    }

    /**
     * A protected socket for talking to [target], pinned to the network its resolver came from
     * when the platform allows it.
     *
     * **Why pin at all.** `protect()` says "do not route this back into my own tunnel"; it does
     * not choose a way out of the phone, so the packet follows the system's default route. That
     * is the same network our resolvers came from — except for the seconds around a handover,
     * which on a phone walking around a house with weak Wi-Fi is most of the interesting time.
     * The failure is silent and total: a LAN resolver asked over mobile, or an ISP's mobile
     * resolvers asked over Wi-Fi, and nothing answers either way. Both are in one user's trace an
     * hour apart. Pinning makes the resolver and the route agree by construction rather than by
     * timing.
     *
     * **Why this is not the thing that was reverted in 0.9.4.** That code pinned to a network
     * reference held in a field only the default-network callback ever updated — and that
     * callback goes quiet once the tunnel is up, so it was very likely pinning sockets to a
     * network that no longer existed, which is one of the things `EPERM` means. The reference
     * used here is kept current by [underlyingCallback] and re-read on every adoption. That is a
     * diagnosis and not a certainty — a device that simply refuses the call would look identical
     * — so it is best-effort in both directions: a refused pin costs one socket, three in a row
     * turn pinning off for the life of the service, and the diagnostics header says which
     * happened. RethinkDNS pins its DNS sockets the same way, which is what suggested the
     * reference rather than the call was at fault.
     */
    private fun openUpstream(target: InetAddress): DatagramSocket? {
        val network = activeNetwork.takeIf { pinFailures < PIN_ATTEMPTS }
        if (network != null) {
            newUpstream(target, network)?.let { return it }
            // The pin was refused and the socket it may have damaged is already closed. An
            // unpinned socket still works; it just follows the default route, as it did before.
        }
        return newUpstream(target, null)
    }

    private fun newUpstream(target: InetAddress, pinTo: Network?): DatagramSocket? = runCatching {
        DatagramSocket().also { socket ->
            if (!protect(socket)) {
                noteSocketTrouble("could not protect a socket for ${target.hostAddress}")
                socket.close()
                return null
            }
            if (pinTo != null && !pin(socket, pinTo, target)) {
                socket.close()
                return null
            }
            socket.connect(target, DNS_PORT)
        }
    }.onFailure {
        noteSocketTrouble("no socket for ${target.hostAddress}: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /** Ties one socket to one network. False when the platform refused; never throws. */
    private fun pin(socket: DatagramSocket, network: Network, target: InetAddress): Boolean =
        runCatching {
            network.bindSocket(socket)
            // Only consecutive failures count: one refusal is a network that went away between
            // the adoption and the socket, which is ordinary on a phone that keeps changing.
            pinFailures = 0
            true
        }.onFailure {
            pinFailures++
            noteSocketTrouble(
                "could not pin a socket for ${target.hostAddress} to its network: " +
                    "${it.javaClass.simpleName}: ${it.message}",
            )
            if (pinFailures == PIN_ATTEMPTS) {
                DebugLog.w(TAG, "this device refuses to pin sockets to a network; following the default route instead")
            }
        }.getOrDefault(false)

    /**
     * If the upstream truncated its answer it expects the client to ask again over TCP — which
     * this tunnel does not carry, so that retry would vanish into it. We make the TCP query
     * ourselves and hand back the complete answer over UDP instead. Rare (mostly DNSSEC and
     * large TXT records), and the alternative is a lookup that hangs with no explanation.
     */
    private fun resolveTruncated(answer: ByteArray, query: ByteArray, target: InetAddress): ByteArray {
        if (answer.size < DNS_HEADER_BYTES || (answer[2].toInt() and 0x02) == 0) return answer
        // One budget for the whole exchange, not one per syscall. A resolver that sets TC and
        // then dawdles used to be able to hold a forwarder for the connect timeout plus a fresh
        // five seconds per read, and there are only [FORWARD_THREADS] of them: four such answers
        // and every other lookup on the phone is dropped with "forward queue full" for as long as
        // it lasts. The client stopped waiting long before any of that, so spending it buys
        // nothing at all.
        val deadline = SystemClock.elapsedRealtime() + TCP_FALLBACK_BUDGET_MS
        fun left(): Int = (deadline - SystemClock.elapsedRealtime()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val full = runCatching {
            Socket().use { socket ->
                protect(socket)
                if (left() <= 0) return@runCatching null
                socket.connect(InetSocketAddress(target, DNS_PORT), left())
                socket.soTimeout = left().coerceAtLeast(1)
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
                    if (left() <= 0) return@runCatching null
                    socket.soTimeout = left()
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
        // While diagnosing, every one of these matters: a rate-limited line hides the very
        // pattern being looked for — a client retrying over TCP, say, which this tunnel routes
        // and cannot answer.
        if (tracing()) DebugLog.trace(TAG, "dropped: $what")
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

        // And a second one that asks for what the first stops saying. Measured on a phone over
        // twenty minutes of walking around a house: after `tunnel up`, every single adoption came
        // from a lookup failing, and not one from the default-network callback — because once the
        // tunnel is established *our* default network is the tunnel, and that network does not
        // change when the thing underneath it does. NET_CAPABILITY_NOT_VPN is how to ask about
        // the thing underneath.
        // VALIDATED, and it is not decoration: a Wi-Fi that has associated but cannot reach
        // anything still has INTERNET, so without this the platform can name it the best match —
        // and then the tunnel adopts a router's resolvers and (since sockets are pinned) sends
        // every lookup out of a network with no way out. Which is what walking back into range of
        // a weak access point looks like. Android does not switch to such a network either; this
        // is asking the same question it asks.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        runCatching {
            // The check is spelled out here rather than read from [platformPicksBest] because
            // lint only recognises the inline form, and a NewApi it cannot see through fails
            // `lintVitalRelease` — which is to say the release build, not this one.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12 and up will name its *best* match and nothing else, which is the
                // question being asked — our protected sockets leave by the platform's choice,
                // not by ours, so its answer beats any ranking of our own.
                cm.registerBestMatchingNetworkCallback(request, underlyingCallback, Handler(Looper.getMainLooper()))
            } else {
                // Below that, every matching network reports itself and the last one to speak
                // would win — the bug this app already paid for once. So these events are only a
                // signal to go and decide again; see [bestUnderlyingNetwork].
                cm.registerNetworkCallback(request, underlyingCallback)
            }
        }.onFailure { DebugLog.w(TAG, "cannot watch the networks under the tunnel", it) }
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

    private fun adoptNetwork(reported: Network, reportedLinkProperties: LinkProperties) {
        // Our own tunnel is a network too, and adopting it would point the filter at its own
        // sentinel — a loop with no exit. This used to `return` here, and that silence cost a
        // phone eleven hours of DNS: once the tunnel is up the default network reported to this
        // app can be the tunnel itself, so every network change after that was discarded without
        // a word, and the filter went on asking a mobile network's resolvers over a Wi-Fi that
        // routed to none of them. A VPN answer is resolved one step down instead.
        val network = realNetwork(reported) ?: return
        val linkProperties = if (network == reported) {
            reportedLinkProperties
        } else {
            runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return
        }

        val dnsServers = linkProperties.dnsServers.orEmpty()
        // Adoption arrives more than once for the same network — onAvailable and then
        // onLinkPropertiesChanged — and it is not free: it closes every pooled socket and
        // forgets which resolver was answering. Only a real change is worth that.
        val changed = network != activeNetwork || dnsServers != networkDnsServers
        activeNetwork = network
        networkDnsServers = dnsServers

        if (changed) {
            networkLabel = linkProperties.interfaceName.orEmpty()
            adoptedAtMs = SystemClock.elapsedRealtime()
            // Named, not counted. "Which resolvers did this network actually hand us" is the
            // first question for every report of "it does not resolve on this Wi-Fi", and until
            // now the log answered it with the word "system".
            DebugLog.i(
                TAG,
                "network $networkLabel: dns=" +
                    dnsServers.joinToString(prefix = "[", postfix = "]") { it.hostAddress.orEmpty() },
            )
            upstreams = resolveUpstreams()
            // The resolver that worked belonged to the network that has just gone.
            lastGoodUpstream = null
            // Pooled sockets are bound to the network that existed when they were made.
            sockets.closeAll()
            // And so is every idle HTTP connection: a phone that changes networks often gets a
            // `REFUSED_STREAM` or a timeout on the first request after each change, because the
            // pooled HTTP/2 connection left over from the last network is dead. The updater
            // survives it by retrying; a blocklist download does not, and comes back as a red
            // line on the Lists screen until the next scheduled refresh.
            runCatching { Http.client.connectionPool.evictAll() }
            // Anything already waiting on the old network's resolvers is now waiting for nothing.
            networkGeneration++
        }

        // Everything below this line used to run on *every* callback, and two of the calls are
        // binder round trips. `onAvailable` and `onLinkPropertiesChanged` both arrive for the same
        // network, and below Android 12 a network we are not even using reports itself as well —
        // so an idle phone with Wi-Fi and mobile both up paid for all of this several times a
        // minute, forever, to re-state facts that had not moved.
        val privateDnsMoved = privateDnsActive != linkProperties.isPrivateDnsActive ||
            privateDnsHost != linkProperties.privateDnsServerName
        privateDnsActive = linkProperties.isPrivateDnsActive
        privateDnsHost = linkProperties.privateDnsServerName
        // Deliberately not folded into `changed`: Private DNS is a switch in the system settings
        // and moves without the network or its resolvers moving at all.
        if (privateDnsMoved) VpnStatus.privateDns(privateDnsActive, privateDnsHost)
        publishLockdown(force = changed)
        declareUnderlying(network)

        if (tunnel != null) {
            if (changed || privateDnsMoved) VpnStatus.up(upstreamLabel(), privateDnsActive, privateDnsHost)
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
     * The network our forwarded queries really leave by, given whatever the platform called the
     * default — or null when there is nothing to forward over.
     *
     * A VPN is never the answer: ours is the only tunnel our own sockets are protected from, and
     * adopting its resolvers means forwarding to our own sentinel. From API 31 the VPN's own
     * capabilities name what it was built on, which is exactly the question being asked. Below
     * that, and when the platform declares nothing, the phone is asked for every network it has
     * and the validated non-VPN one is taken.
     */
    private fun realNetwork(reported: Network): Network? {
        if (!isVpn(reported)) return reported
        val candidate = bestUnderlyingNetwork()
        // Never silent. A tunnel that cannot name the network underneath it keeps the resolvers
        // it already had, and whoever reads this log has to be told that is what happened.
        if (candidate == null) DebugLog.w(TAG, "the default network is a VPN with nothing usable underneath it")
        return candidate
    }

    /**
     * The network our forwarded queries leave by, chosen by us.
     *
     * `NetworkCapabilities.getUnderlyingNetworks()` would answer this exactly, and it is not in
     * the public SDK — the compiler said so, it was not assumed. So the phone is asked for every
     * network it has and they are ranked the way the platform ranks them itself: validated, with
     * internet, not a VPN, wire before Wi-Fi before mobile. On Android 12 and up this is only a
     * fallback — [underlyingCallback] gets the platform's own answer, which is better than any
     * ranking because it is the same choice our sockets are about to follow.
     */
    private fun bestUnderlyingNetwork(): Network? = runCatching {
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks
            .mapNotNull { network -> cm.getNetworkCapabilities(network)?.let { network to it } }
            .filterNot { (_, caps) -> caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .filter { (_, caps) -> caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }

        // A network that is connected and that the system has decided reaches nothing. Worth a
        // line, because from inside this app it is otherwise invisible: the phone sits on mobile
        // data next to its own router, Android calls the Wi-Fi "low quality" and does not
        // re-check it until something forces a re-evaluation — which starting or stopping a VPN
        // happens to do. That is the platform's judgement and not ours to override, but a report
        // that says "it connects and the phone won't use it" should not need guessing at.
        candidates.filterNot { (_, caps) -> caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .forEach { (network, _) -> noteUnvalidated(network) }

        candidates
            .filter { (_, caps) -> caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .minByOrNull { (_, caps) ->
                TunnelPolicy.transportRank(
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                )
            }
            ?.first
    }.getOrNull()

    /**
     * Adopts [named] if the platform named it, and otherwise whichever network we would pick.
     *
     * The naming is trusted only while it still holds up: a network is named as the best match
     * and can lose its validation a moment later without another callback, and adopting a network
     * that cannot reach anything is worse than keeping one that can — the sockets are pinned to
     * it. Falling back to our own ranking asks the same question a second time.
     */
    private fun adoptUnderlying(named: Network?) {
        val network = named?.takeIf { isValidated(it) } ?: bestUnderlyingNetwork() ?: return
        val linkProperties = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return
        adoptNetwork(network, linkProperties)
    }

    private fun isVpn(network: Network): Boolean =
        cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    /** Names a connected network the system will not use, at most once a minute. */
    private fun noteUnvalidated(network: Network) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnvalidatedLogMs < SILENT_UPSTREAM_LOG_INTERVAL_MS) return
        lastUnvalidatedLogMs = now
        val name = runCatching { cm.getLinkProperties(network)?.interfaceName }.getOrNull() ?: "a network"
        DebugLog.w(TAG, "$name is connected but the system has not validated it; its resolvers are not adopted")
    }

    /** Whether the platform has confirmed this network actually reaches the internet. */
    private fun isValidated(network: Network): Boolean =
        cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    /**
     * Asks the phone which resolvers it is on, after a lookup found that none of ours would
     * answer — and adopts them if they are not the ones we hold.
     *
     * The network callback is the fast path and this is the floor underneath it. A callback that
     * does not arrive, or arrives naming a network we cannot see past, used to mean the filter
     * asked a dead resolver until something else happened to change; the only symptom was every
     * lookup timing out, which is indistinguishable from a network that is simply down. Costs two
     * binder calls, at most once every [RESOLVER_RECHECK_INTERVAL_MS], and only ever on the path
     * where everything has already failed.
     */
    private fun recheckResolvers() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastResolverRecheckMs < RESOLVER_RECHECK_INTERVAL_MS) return
        lastResolverRecheckMs = now
        scope.launch {
            val reported = runCatching { cm.activeNetwork }.getOrNull() ?: return@launch
            val network = realNetwork(reported) ?: return@launch
            val linkProperties = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return@launch
            if (!TunnelPolicy.worthAdopting(networkDnsServers, linkProperties.dnsServers.orEmpty())) return@launch
            DebugLog.w(TAG, "no resolver answered and this network offers others; adopting them")
            adoptNetwork(network, linkProperties)
        }
    }

    /**
     * Names the network our forwarded queries really travel over, so they are billed and routed
     * correctly instead of appearing to come from the tunnel — and so the platform derives this
     * tun's meteredness from what is actually underneath it.
     *
     * Only when it is a different network from the one already declared: re-declaring the same
     * one changes nothing and costs a binder round trip, and the callbacks that reach here fire
     * several times a minute on a phone being carried around.
     */
    private fun declareUnderlying(network: Network) {
        if (network == declaredUnderlying) return
        declaredUnderlying = network
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
    }

    /**
     * Re-reads where lookups go, for a tunnel that is staying up.
     *
     * Everything the old resolvers left behind goes with them: the pooled sockets are *connected*
     * to them and cannot carry a query anywhere else, the resolver remembered as the one that
     * answers is one we are no longer asking, and a lookup already in flight is spending its
     * budget on a list the user has just replaced.
     */
    private fun adoptUpstreams() {
        upstreams = resolveUpstreams()
        lastGoodUpstream = null
        sockets.closeAll()
        networkGeneration++
        DebugLog.i(TAG, "lookups now go to ${upstreamLabel()}")
        if (tunnel != null) VpnStatus.up(upstreamLabel(), privateDnsActive, privateDnsHost)
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
        // The declaration belongs to the tun that has just gone, so the next one has to make it
        // again rather than being skipped as already-current.
        declaredUnderlying = null
        sockets.closeAll()
        // The flush cadence is a lookup count, so a tunnel that stops has to push what is left.
        runCatching { app.statsStore.flush() }
        VpnStatus.down()
    }

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
        // *Which* tunnel was revoked matters. Establishing a new one revokes the old one, so a
        // rebuild — changing the app scope, or the bypass guard — delivers this for a descriptor
        // we have already replaced. Acting on it unconditionally meant waiting for the lock the
        // rebuild was holding and then tearing down the tunnel that rebuild had just brought up,
        // and scheduling a retry to build a third: a filter that flapped every time the user
        // edited which apps it covered. Measured as two read loops alive at once.
        val revoked = tunnel ?: return
        DebugLog.w(TAG, "VPN consent revoked or taken over by another app")
        scope.launch {
            synchronized(tunnelLock) {
                if (tunnel !== revoked) return@launch
                stopTunnel()
                // Retried rather than reported: the usual cause is another VPN connecting, and
                // that is exactly the kind of thing that stops again on its own.
                scheduleRetry(TunnelProblem.DISPLACED, getString(R.string.status_displaced))
            }
        }
    }

    override fun onDestroy() {
        // Both under the one lock, so a start cannot slip between them. Marking first is what
        // makes a start that is already waiting on the lock decline instead of building a tunnel
        // nobody is left to take down; see [startTunnel].
        synchronized(tunnelLock) {
            destroyed = true
            stopTunnel()
        }
        cancelRetry()
        diagnoseJob?.cancel()
        // The buffer lives in this process either way, but nothing is left claiming to record.
        AppTrace.stop()
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        runCatching { cm.unregisterNetworkCallback(underlyingCallback) }
        runCatching { unregisterReceiver(packageChanges) }
        scope.cancel()
        forwarders.shutdownNow()
        VpnStatus.down()
        super.onDestroy()
    }

    /**
     * The wall-clock end of a pause, as an alarm rather than a timer.
     *
     * Inexact on purpose: `setAndAllowWhileIdle` needs no permission, survives Doze, and a pause
     * that ends a minute late is not a bug — one that ends when the phone next happens to be
     * awake is. Deliberately *not* cancelled in [onDestroy] either: an alarm outlives the process
     * it was set from, so a pause interrupted by a low-memory kill still ends on time and brings
     * the filter back with it.
     */
    private fun schedulePauseAlarm(untilMs: Long) {
        val alarms = getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, untilMs, pauseAlarm()) }
            .onSuccess { pauseAlarmArmed = true }
            .onFailure { DebugLog.w(TAG, "could not schedule the end of the pause", it) }
    }

    /**
     * Withdraws the pause alarm, and does nothing at all when there is none to withdraw.
     *
     * Reached from [applySettings], which runs on *every* settings emission — every rule written,
     * every switch, every step of a guided search. Unconditional it was building a `PendingIntent`
     * and calling `AlarmManager.cancel` each time, which is two round trips into the system server
     * for an alarm that almost never exists, in a process that is alive for weeks.
     *
     * Starts armed on purpose: an alarm outlives the process that set it (that is the whole point
     * of using one), so the first settings change after a start still clears whatever the last
     * process left behind. After that this knows.
     */
    private fun cancelPauseAlarm() {
        if (!pauseAlarmArmed) return
        pauseAlarmArmed = false
        val alarms = getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pauseAlarm()) }
    }

    private fun pauseAlarm(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, MalachiVpnService::class.java).setAction(ACTION_RESUME),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** True while the diagnostics window is open; the zero check keeps the hot path free. */
    private fun tracing(): Boolean = traceUntilMs != 0L && System.currentTimeMillis() < traceUntilMs

    /**
     * Arms or disarms the per-app timeline, and schedules the end of its window.
     *
     * The scheduled end is not what stops the recording — [tracingApp] reads the wall clock and
     * would stop on its own. What it is for is making the expiry an *event*: it clears the
     * setting, so attribution goes back to whatever the rest of the app needs and the screen
     * stops claiming to be watching something. Without it a window that lapsed while nobody was
     * looking would leave a binder round trip per lookup until the next time a setting moved.
     */
    private fun applyAppTrace(next: MalachiSettings) {
        diagnoseJob?.cancel()
        val target = next.diagnosing()
        traceApp = target
        traceAppUntilMs = if (target != null) next.diagnoseUntilMs else 0
        if (target == null) {
            AppTrace.stop()
            // Only a window that has run out is tidied away; one the user turned off is already
            // clean, and writing to the settings from here on every emission would be a loop.
            if (next.diagnoseApp.isNotEmpty()) {
                scope.launch { app.settingsStore.update { it.copy(diagnoseApp = "", diagnoseUntilMs = 0) } }
            }
            return
        }
        AppTrace.watch(target)
        diagnoseJob = scope.launch {
            // A monotonic delay, so a phone that sleeps through it tidies up late. That is the
            // right trade here and not in the pause: the deadline is already enforced per lookup,
            // and this is only housekeeping — an alarm to wake a sleeping phone for it would be
            // spending battery to switch something off that has already stopped.
            delay((next.diagnoseUntilMs - System.currentTimeMillis()).coerceAtLeast(0))
            app.settingsStore.update {
                if (it.diagnosing() == null) it.copy(diagnoseApp = "", diagnoseUntilMs = 0) else it
            }
        }
    }

    /**
     * True when this lookup belongs to the app being diagnosed.
     *
     * Ordered so the common case — every other app on the phone, or none being watched — is one
     * volatile read and at most one string comparison. The clock is only read for the app that is
     * actually being traced, and reading it there is what ends the window even if the job that
     * tidies up is still asleep.
     */
    private fun tracingApp(packageName: String?): Boolean {
        val target = traceApp ?: return false
        if (packageName == null || packageName != target) return false
        if (System.currentTimeMillis() < traceAppUntilMs) return true
        traceApp = null
        traceAppUntilMs = 0
        AppTrace.stop()
        return false
    }

    /**
     * Everything about this phone's DNS that a report needs, written once when the window opens.
     *
     * A trace of lookups without it is unreadable at a distance: whether a resolver is the
     * network's or one the user chose, whether Private DNS is in the way, and what the network
     * actually handed out are the three things that change what the rest of the lines mean.
     */
    private fun traceEnvironment(settings: MalachiSettings) {
        DebugLog.trace(TAG, "— diagnostics on for ${DIAGNOSTICS_MINUTES} minutes —")
        DebugLog.trace(TAG, "app ${dev.malachi.BuildConfig.VERSION_NAME} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        DebugLog.trace(TAG, "upstream setting=${settings.upstream}${if (settings.customUpstream.isNotBlank()) " (${settings.customUpstream})" else ""}")
        DebugLog.trace(TAG, "resolvers in use=${upstreams.joinToString { it.hostAddress.orEmpty() }}")
        // Whose resolvers, and how old. Without both, a list of four addresses that nothing
        // answers reads as a broken network rather than as the last network's leftovers.
        val adoptedAgo = if (adoptedAtMs == 0L) "never" else "${(SystemClock.elapsedRealtime() - adoptedAtMs) / 1000}s ago"
        DebugLog.trace(
            TAG,
            "network=${networkLabel.ifEmpty { "unknown" }} (adopted $adoptedAgo) " +
                "dns=${networkDnsServers.joinToString { it.hostAddress.orEmpty() }} " +
                "private=${privateDnsHost ?: if (privateDnsActive) "automatic" else "off"}",
        )
        // Whether the queries are leaving by the network their resolvers came from or merely by
        // whatever the default route happens to be. The whole question of 0.9.2 through 0.9.10,
        // in one word, on the phone that can answer it.
        DebugLog.trace(TAG, "sockets pinned to their network=${if (pinFailures < PIN_ATTEMPTS) "yes" else "no, refused"}")
        DebugLog.trace(TAG, "scope=${settings.scopeMode} bypass=${settings.bypassAllowed} guard=${settings.bypassGuard} lockdown=$lastLockdown")
    }

    /**
     * **Do not bind these sockets to a network.** It was tried, in 0.9.2, on the theory that
     * `protect()` exempts a socket from the tunnel without choosing a way out of the phone.
     * `Network.bindSocket` refused with `EPERM` on every socket on a Pixel 8 Pro — and it does
     * not refuse cleanly: it duplicates the descriptor before it throws, and the socket that
     * comes back is broken. The signature in the trace is unmistakable, a resolver "not
     * answering" in one millisecond where it had answered in eighty a second earlier, and four
     * resolvers exhausted in three milliseconds.
     *
     * `protect()` is what this tunnel needs and all it needs: the queries it sends do leave, and
     * the traces show them answered. If a resolver is ever genuinely unreachable for want of an
     * interface, the answer is to find out why rather than to reach for this again.
     */
    /**
     * Notes whether the platform is dropping everything that does not leave through this tunnel.
     *
     * Read here rather than once at startup because it is a switch the user can throw at any
     * time, in a screen this app sends them to; the network callbacks are the only regular
     * heartbeat available, and a lockdown change is itself a connectivity change.
     */
    private fun publishLockdown(force: Boolean = false) {
        // The read is a binder round trip and the callers are network callbacks, which on a
        // moving phone arrive several times a minute and almost never because of this. Lockdown
        // is a switch a person throws by hand in a settings screen, so a minute of lag costs
        // nothing and a call per callback is a battery bug. [force] is for the moments that are
        // genuinely about it: a tunnel coming up, a network actually being adopted.
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLockdownCheckMs < LOCKDOWN_CHECK_INTERVAL_MS) return
        lastLockdownCheckMs = now
        val locked = runCatching { isLockdownEnabled }.getOrDefault(false)
        if (locked != lastLockdown) {
            lastLockdown = locked
            DebugLog.w(TAG, "block-connections-without-VPN is ${if (locked) "on" else "off"}")
        }
        VpnStatus.lockdown(locked)
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

        /** How long the diagnostics window stays open before closing itself. */
        const val DIAGNOSTICS_MINUTES = 15
        const val DIAGNOSTICS_MILLIS = DIAGNOSTICS_MINUTES * 60 * 1000L

        /**
         * How long one app is watched before the window shuts itself.
         *
         * Longer than the log-wide window above, because the errand is different: that one is
         * "reproduce the thing and read the log", this one is a sequence of attempts — use the
         * app, come back, exempt a name, use it again — and half an hour is about two of those
         * with room to think. Re-armed with one tap, and what it caught survives the expiry.
         */
        const val DIAGNOSE_APP_MINUTES = 30
        const val DIAGNOSE_APP_MILLIS = DIAGNOSE_APP_MINUTES * 60 * 1000L

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

        /**
         * The whole of what the TCP retry of a truncated answer may cost, connect included.
         *
         * Shorter than a UDP lookup's budget on purpose: by the time we are here the client has
         * already had its answer relayed to it *if* this fails, so the only thing being bought is
         * a better answer, and there are four forwarder threads between every app on the phone
         * and its DNS.
         */
        private const val TCP_FALLBACK_BUDGET_MS = 3_000L

        /** The least a resolver is given before the next one is tried. */
        private const val MIN_UPSTREAM_ATTEMPT_MS = 1_200L
        private const val SILENT_UPSTREAM_LOG_INTERVAL_MS = 60_000L

        /**
         * How often the phone may be re-asked which resolvers it has, when ours answer nothing.
         *
         * Short, because until it fires nothing on the device resolves; bounded, because the
         * other reason every resolver goes quiet is a network that is simply down, and that must
         * not turn into two binder calls per lookup for as long as it lasts.
         */
        private const val RESOLVER_RECHECK_INTERVAL_MS = 5_000L

        /**
         * How many sockets in a row may fail to pin before the tunnel stops trying.
         *
         * Three rather than one because a refusal is also what a network that vanished between
         * the adoption and the socket looks like, and that is ordinary on a phone that changes
         * network every few minutes. A device that refuses the call outright fails all three.
         */
        private const val PIN_ATTEMPTS = 3

        /**
         * How often a capability change on the network already in use is allowed to cost a
         * binder call. It buys one thing — noticing lockdown — and lockdown is a switch a person
         * throws by hand, so a minute of lag is nothing and a call per tick is a battery bug.
         */
        private const val LOCKDOWN_CHECK_INTERVAL_MS = 60_000L

        /**
         * How often a capability change on a network we are not using may cost a re-decision.
         *
         * Only reached below Android 12, where every matching network reports itself rather than
         * the platform naming its best. Everything that genuinely changes which network is
         * underneath us arrives through another callback that is not throttled at all.
         */
        private const val UNDERLYING_RECHECK_INTERVAL_MS = 30_000L
        private const val FORWARD_THREADS = 4
        private const val FORWARD_QUEUE = 128
        private const val UNROUTABLE_LOG_INTERVAL_MS = 60_000L

        /**
         * How long a shutdown waits for the read loop. It is woken by a byte down the self-pipe
         * and returns in microseconds, so this is pure headroom — but giving up on the wait is
         * how a reader gets detached from a descriptor that is about to close, which is the one
         * outcome worth spending seconds to avoid. Generous for the sake of a contended machine
         * where "microseconds" is not what a scheduler does.
         */
        private const val READER_JOIN_MS = 8_000L

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
