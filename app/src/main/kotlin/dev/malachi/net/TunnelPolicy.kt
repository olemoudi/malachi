package dev.malachi.net

import dev.malachi.data.AppScopeMode
import dev.malachi.data.MalachiSettings
import dev.malachi.data.UpstreamDns
import java.net.InetAddress

/** What a settings change asks the tunnel to do. See [TunnelPolicy.decide]. */
sealed interface TunnelAction {

    /** Filtering is off entirely: drop the tunnel and let the service go. */
    data object StandDown : TunnelAction

    /** Suspended until [untilMs]; no tunnel, and the service has to stay alive to come back. */
    data class Pause(val untilMs: Long) : TunnelAction

    /** The tun has to be built, or rebuilt because its shape changed. */
    data object Rebuild : TunnelAction

    /** Nothing to do: what is running already matches what was asked for. */
    data object LeaveRunning : TunnelAction
}

/** Why a start attempt cannot proceed, or null when it can. */
enum class StartRefusal { NO_APPS_SELECTED, ALWAYS_ON_ELSEWHERE, NO_CONSENT }

/** What to do about an `establish()` that returned null. */
sealed interface StartFailure {
    /** Only the user can fix it; retrying burns wakeups on a dialog nobody is looking at. */
    data class Report(val problem: TunnelProblem) : StartFailure

    /** Time might fix it: another VPN letting go, a network coming back. */
    data class Retry(val problem: TunnelProblem) : StartFailure
}

/**
 * The tunnel's decisions, with none of the tunnel.
 *
 * Every one of these used to be a branch inside [MalachiVpnService], where the only way to
 * exercise it was to run the app on a phone and arrange the situation by hand — which is why
 * several of them were wrong for a long time without anybody noticing. They are ordinary
 * functions over ordinary values, and the service is left holding the descriptors.
 */
object TunnelPolicy {

    /** 5s, doubling to a little over five minutes. */
    const val RETRY_BASE_MS = 5_000L
    const val RETRY_MAX_SHIFT = 6

    /**
     * What [settings] asks of a tunnel that is currently [tunnelUp] with shape [currentShape].
     *
     * The distinction that matters is [TunnelAction.Rebuild] versus
     * [TunnelAction.LeaveRunning]: the app scope and the bypass routes are baked into the tun
     * when it is built, so those cost a rebuild and a visible blink of unfiltered DNS, while a
     * rule or a list change is read per query and must never cause one.
     */
    fun decide(
        settings: MalachiSettings,
        tunnelUp: Boolean,
        currentShape: String?,
        nowMs: Long,
    ): TunnelAction = when {
        !settings.filteringEnabled -> TunnelAction.StandDown
        settings.isPaused(nowMs) -> TunnelAction.Pause(settings.pausedUntilMs)
        !tunnelUp -> TunnelAction.Rebuild
        settings.tunnelShape() != currentShape -> TunnelAction.Rebuild
        else -> TunnelAction.LeaveRunning
    }

    /** How long a pause has left to run, floored at zero so a stale one resumes at once. */
    fun pauseRemainingMs(untilMs: Long, nowMs: Long): Long = (untilMs - nowMs).coerceAtLeast(0)

    /**
     * Whether anything still needs to know *which* app asked. Attribution is a binder round trip
     * on every lookup and buys nothing when the query log is off and no per-app rule exists.
     */
    fun attributionNeeded(settings: MalachiSettings): Boolean =
        settings.queryLogEnabled || settings.appRules.isNotEmpty()

    /** True on the edge where the user switches the query log off, which has to forget it too. */
    fun forgetsQueryLog(previous: MalachiSettings, next: MalachiSettings): Boolean =
        previous.queryLogEnabled && !next.queryLogEnabled

    /**
     * What stands in the way of even trying, in the order the user can act on it. Empty apps
     * first because it is the one no retry can fix, always-on next because its remedy is a
     * different screen entirely, and consent last because asking for it is the ordinary path.
     */
    fun refusal(
        settings: MalachiSettings,
        alwaysOnHeldElsewhere: Boolean,
        hasConsent: Boolean,
    ): StartRefusal? = when {
        settings.scopeMode == AppScopeMode.ONLY_SELECTED && settings.includedApps.isEmpty() ->
            StartRefusal.NO_APPS_SELECTED
        alwaysOnHeldElsewhere -> StartRefusal.ALWAYS_ON_ELSEWHERE
        !hasConsent -> StartRefusal.NO_CONSENT
        else -> null
    }

    /**
     * `establish()` says only "no". Consent is re-read rather than assumed, because it can be
     * withdrawn between the check and the call — and it is the only cause here worth reporting
     * as final. Everything else waits, including "no VPN visible at all", which used to be
     * reported as a missing permission we had just confirmed we had.
     */
    fun diagnose(hasConsent: Boolean, anotherVpnActive: Boolean): StartFailure = when {
        !hasConsent -> StartFailure.Report(TunnelProblem.NO_CONSENT)
        anotherVpnActive -> StartFailure.Retry(TunnelProblem.DISPLACED)
        else -> StartFailure.Retry(TunnelProblem.FAILED)
    }

    /** The backoff, capped so a filter that cannot start doesn't wake the phone forever. */
    fun retryDelayMs(attempt: Int): Long = RETRY_BASE_MS shl attempt.coerceIn(0, RETRY_MAX_SHIFT)

    /**
     * Where allowed lookups go.
     *
     * [parse] takes a literal address and nothing else — a hostname typed into the custom
     * resolver box must not turn this into a blocking DNS lookup on a network callback thread.
     * The sentinel addresses are filtered out because routing our own tun's resolver upstream
     * would be a loop, and an empty result falls back rather than leaving the device with
     * nowhere to ask.
     */
    fun resolveUpstreams(
        upstream: UpstreamDns,
        customUpstream: String,
        networkDnsServers: List<InetAddress>,
        sentinels: Set<String>,
        parse: (String) -> InetAddress?,
    ): List<InetAddress> {
        val configured = when (upstream) {
            UpstreamDns.SYSTEM -> networkDnsServers
            UpstreamDns.CUSTOM -> customUpstream.split(',', ' ').mapNotNull { parse(it.trim()) }
            else -> upstream.addresses.mapNotNull { parse(it) }
        }.filterNot { it.hostAddress.orEmpty() in sentinels }

        return configured.ifEmpty { UpstreamDns.CLOUDFLARE.addresses.mapNotNull { parse(it) } }
    }

    /**
     * How a network ranks when the platform will not say which one is the default and we have to
     * choose — lowest first.
     *
     * This is only reached when the default reported to this app is a VPN, which is to say ours:
     * the question then is which network our protected sockets actually leave by, and the
     * platform's own preference (a wire over Wi-Fi over mobile) is the best available answer.
     * Getting it wrong is not fatal — the resolvers of the other network are usually reachable
     * from this one — but getting it right is what stops a phone on Wi-Fi asking a mobile
     * network's resolvers.
     */
    fun transportRank(wifi: Boolean, ethernet: Boolean, cellular: Boolean): Int = when {
        ethernet -> 0
        wifi -> 1
        cellular -> 2
        else -> 3
    }

    /**
     * Whether what the phone says it has now is worth taking over what the tunnel is holding.
     *
     * Two refusals, and both were paid for. **Empty is never worth adopting**: `LinkProperties`
     * arrive in stages and one that has no DNS servers yet would replace a working list with the
     * fallback, so a network coming up would briefly send every lookup to Cloudflare. And **the
     * same list is not worth adopting either**, because adopting closes every pooled socket and
     * forgets which resolver was answering — a re-check that fired on every failed lookup would
     * otherwise make a network outage cost more than the outage.
     */
    fun worthAdopting(current: List<InetAddress>, offered: List<InetAddress>): Boolean =
        offered.isNotEmpty() && offered != current

    /**
     * The resolver to ask for a query that arrived over [wantsIpv6], preferring one of the same
     * family so the answer can travel back the way it came.
     */
    fun pickUpstream(upstreams: List<InetAddress>, wantsIpv6: Boolean): InetAddress? =
        orderUpstreams(upstreams, wantsIpv6).firstOrNull()

    /**
     * Every resolver worth asking, best first — because asking only one is how a whole network
     * stops resolving.
     *
     * A network commonly hands out two or three DNS servers and there is no promise that the
     * first one works: routers advertise themselves and then filter, or list an address that
     * answers on the LAN and nowhere else. Android's own resolver tries them all and remembers
     * which replied, so with the filter *off* such a network looks perfectly healthy. Asking a
     * single server and dropping the lookup when it stays quiet turns that same network into one
     * where nothing loads at all — reported from a phone where mobile data worked, this Wi-Fi did
     * not, and every domain in the log had been asked for a dozen times.
     *
     * [preferred] is whichever resolver last answered. It goes first so that a dud costs its
     * timeout once rather than on every lookup for as long as the phone stays on that network.
     */
    fun orderUpstreams(
        upstreams: List<InetAddress>,
        wantsIpv6: Boolean,
        preferred: InetAddress? = null,
    ): List<InetAddress> = upstreams.sortedWith(
        compareByDescending<InetAddress> { it == preferred }
            // Same family next: the answer has to travel back the way the query came, and a
            // resolver of the other family is a fallback rather than a first choice.
            .thenByDescending { (it is java.net.Inet4Address) != wantsIpv6 },
    )

    /**
     * How long one resolver may be given when [remaining] of the budget is left and [left] of the
     * candidates are still untried.
     *
     * The point is that the last candidate is reachable at all. Handing the whole budget to the
     * first means a silent resolver eats it entirely and the working one underneath is never
     * asked — which is the bug this exists to prevent, not a theoretical one.
     */
    fun attemptBudgetMs(remaining: Long, left: Int, floorMs: Long): Long = when {
        left <= 1 -> remaining
        else -> maxOf(remaining / left, minOf(floorMs, remaining))
    }
}
