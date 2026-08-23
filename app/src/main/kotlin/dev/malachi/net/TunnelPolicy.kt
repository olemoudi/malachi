package dev.malachi.net

import dev.malachi.data.AppScopeMode
import dev.malachi.data.BypassGuard
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
     *
     * A per-app diagnosis needs it too, and needs it even with the log switched off — which is
     * precisely the configuration somebody debugging one app on a phone they keep private would
     * be in. It costs what it costs for as long as that window is open, and the window shuts
     * itself.
     */
    fun attributionNeeded(settings: MalachiSettings): Boolean =
        settings.queryLogEnabled || settings.appRules.isNotEmpty() || settings.diagnoseApp.isNotEmpty()

    /** True on the edge where the user switches the query log off, which has to forget it too. */
    fun forgetsQueryLog(previous: MalachiSettings, next: MalachiSettings): Boolean =
        previous.queryLogEnabled && !next.queryLogEnabled

    /**
     * True when where lookups are sent has changed, though the tunnel itself may stay.
     *
     * Deliberately not part of [MalachiSettings.tunnelShape]: the resolver list is not baked into
     * the tun, so changing it must not cost a rebuild and a blink of unfiltered DNS. But it is not
     * read per query either — it is resolved once, because doing it per lookup would put a
     * settings read and a parse on the hot path — so something has to notice, and until this
     * nothing did. Choosing a different DNS server while the filter was running saved the setting,
     * showed it on the settings screen, and went on asking the old one until the phone happened to
     * change network: the home screen and the settings screen naming different resolvers, both
     * confidently, for as long as that took.
     */
    fun upstreamMoved(previous: MalachiSettings, next: MalachiSettings): Boolean =
        previous.upstream != next.upstream || previous.customUpstream != next.customUpstream

    /**
     * What stands in the way of even trying, in the order the user can act on it. Empty apps
     * first because it is the one no retry can fix, always-on next because its remedy is a
     * different screen entirely, and consent last because asking for it is the ordinary path.
     */
    fun refusal(
        settings: MalachiSettings,
        alwaysOnHeldElsewhere: Boolean,
        hasConsent: Boolean,
        selectedAppsPresent: Int,
    ): StartRefusal? = when {
        settings.scopeMode == AppScopeMode.ONLY_SELECTED && selectedAppsPresent <= 0 ->
            StartRefusal.NO_APPS_SELECTED
        alwaysOnHeldElsewhere -> StartRefusal.ALWAYS_ON_ELSEWHERE
        !hasConsent -> StartRefusal.NO_CONSENT
        else -> null
    }

    /**
     * Whether a tun built in [AppScopeMode.ONLY_SELECTED] would actually be selective, given that
     * [applied] of the chosen apps went into the builder.
     *
     * This is not belt and braces, it is the difference between filtering three apps and filtering
     * the phone. `Builder.addAllowedApplication` throws for a package that is not installed, and
     * the platform's rule for the allow-list is that it applies **only if the method was called at
     * least once** — so a tun whose every `addAllowedApplication` was refused carries no
     * restriction at all, which Android reads as "every app on the device". Somebody who chose
     * three apps and has since uninstalled all three would get the exact opposite of what their
     * screen says, with nothing raised anywhere: the filter comes up, reports itself as running,
     * and covers their bank.
     *
     * Counting what actually went in is the only way to tell the two apart, because `establish()`
     * succeeds either way.
     */
    fun scopeIsSelective(mode: AppScopeMode, applied: Int): Boolean =
        mode != AppScopeMode.ONLY_SELECTED || applied > 0

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
        captivePortal: Boolean = false,
        parse: (String) -> InetAddress?,
    ): List<InetAddress> {
        val chosen = when {
            // Behind a captive portal the only DNS server that answers is the portal's own, and
            // that is not a preference — it is the whole of what the network will carry until
            // somebody signs in. A hotel, an airport, a coffee shop: the portal drops port 53 to
            // anywhere else, so a phone whose upstream is set to Cloudflare resolves *nothing*,
            // including the page it is being asked to sign in on. Worse than the sum of it: the
            // tunnel is itself a network the platform validates by resolving a name through it,
            // so every app on the phone is told there is no internet at all.
            //
            // Deliberately keyed on the platform's own answer (NET_CAPABILITY_CAPTIVE_PORTAL)
            // rather than on lookups failing: it is set the moment Android's probe finds a
            // portal, cleared the moment the portal is signed in, and needs no probing of ours.
            captivePortal && networkDnsServers.isNotEmpty() -> networkDnsServers
            else -> when (upstream) {
                UpstreamDns.SYSTEM -> networkDnsServers
                UpstreamDns.CUSTOM -> customUpstream.split(',', ' ').mapNotNull { parse(it.trim()) }
                else -> upstream.addresses.mapNotNull { parse(it) }
            }
        }.filterNot { it.hostAddress.orEmpty() in sentinels }

        return chosen.ifEmpty { UpstreamDns.CLOUDFLARE.addresses.mapNotNull { parse(it) } }
    }

    /**
     * What the bypass guard may consider routing into the tun, before the public-address filter
     * in [routableByGuard].
     *
     * **A named Private DNS server stands the whole guard down, and that is not caution.** With
     * a hostname configured, every lookup the phone makes leaves as DoT over TCP to whatever
     * that name resolves to — and the names people configure are `dns.google`,
     * `one.one.one.one`, `dns.quad9.net`: the very addresses in [publicResolvers]. Routing one
     * of them into a tun that answers UDP 53 and nothing else black-holes the only DNS path the
     * device has, in strict mode with no fallback, so the phone stops resolving anything at all.
     * Nothing is lost by standing down either: with DoT in force there is no plaintext lookup
     * for the guard to catch, which is exactly what the screen already tells the user.
     *
     * Automatic Private DNS is a different thing and only excuses the *network's* own resolvers
     * (see [dev.malachi.net.MalachiVpnService]): plaintext still flows, so the guard still works.
     */
    fun guardCandidates(
        guard: BypassGuard,
        networkDnsServers: List<InetAddress>,
        publicResolvers: List<InetAddress>,
        privateDnsActive: Boolean,
        privateDnsHost: String?,
    ): List<InetAddress> = when {
        guard == BypassGuard.OFF -> emptyList()
        privateDnsHost != null -> emptyList()
        else -> buildList {
            if (!privateDnsActive) addAll(networkDnsServers)
            if (guard == BypassGuard.PUBLIC_RESOLVERS) addAll(publicResolvers)
        }
    }

    /**
     * Whether a Private DNS change has made the tun's frozen routes wrong.
     *
     * The routes are baked in at `establish()` and everything else about Private DNS is read per
     * lookup, so this is the one case that has to cost a rebuild: switching Private DNS on to a
     * named server while the filter is running leaves the guard holding a route to the address
     * that server lives at, and the phone loses DNS until something else rebuilds the tunnel.
     * Rare enough to be worth the blink — it is a switch a person throws by hand, in a screen
     * this app links to — and the alternative is a device that resolves nothing.
     */
    fun guardMovedWithPrivateDns(guard: BypassGuard, previousHost: String?, nextHost: String?): Boolean =
        guard != BypassGuard.OFF && (previousHost == null) != (nextHost == null)

    /**
     * Whether the bypass guard may route [address] into the tun.
     *
     * Only a public address. The guard exists to catch a resolver an app has *hardcoded*, and an
     * app cannot hardcode an address it could only have learned from the network it happens to
     * be on — so routing the network's private resolvers catches nothing, and it costs the one
     * thing every home Wi-Fi has: the router. A router hands itself out as the DNS server, and
     * for as long as it sat in the tun everything that was not DNS to it vanished — a ping, its
     * own admin page, the app that talks to it — with nothing logged, because ICMP is routine
     * on a tun. "The Wi-Fi doesn't ping with Malachi on" was this, and a phone's own smart
     * network switching probing its gateway and concluding the Wi-Fi was dead was this too.
     *
     * The sentinels are refused for the older reason: routing the tun's own resolver upstream is
     * a loop with no exit.
     */
    fun routableByGuard(address: InetAddress, sentinels: Set<String>): Boolean {
        if (address.hostAddress.orEmpty() in sentinels) return false
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val raw = address.address
        return when (raw.size) {
            // 100.64.0.0/10, the carrier-grade NAT range: a mobile network's own.
            4 -> !(raw[0].toInt() and 0xFF == 100 && raw[1].toInt() and 0xC0 == 0x40)
            // fc00::/7, the unique local range: what a router hands out over IPv6.
            16 -> raw[0].toInt() and 0xFE != 0xFC
            else -> false
        }
    }

    /** One network the phone has, reduced to what choosing between them depends on. */
    data class Candidate<T>(
        val network: T,
        val validated: Boolean,
        val wifi: Boolean,
        val ethernet: Boolean,
        val cellular: Boolean,
    )

    /**
     * Which transport the platform says is under the tunnel, when it has said.
     *
     * With no underlying network declared, the platform derives the tunnel's own transports from
     * whatever it chose as the default network — so the tunnel's capabilities, read back, name
     * the transport the phone is actually using. That is the platform's choice and not a guess,
     * and it is readable on every Android this app runs on.
     */
    data class TransportHint(val wifi: Boolean, val ethernet: Boolean, val cellular: Boolean) {
        fun matches(candidate: Candidate<*>): Boolean =
            (wifi && candidate.wifi) || (ethernet && candidate.ethernet) || (cellular && candidate.cellular)
    }

    /**
     * The network our forwarded queries leave by, chosen from what the phone has.
     *
     * Validated first, because a network that reaches nothing is not one to pin sockets to while
     * a better one exists. Then whatever the platform said is under the tunnel, because that is
     * the same choice our protected sockets are about to follow — and it is how a phone that has
     * moved to mobile because Android judged its Wi-Fi poor is followed there, instead of every
     * lookup staying on the Wi-Fi the phone just left. Then the platform's own standing preference,
     * a wire over Wi-Fi over mobile.
     *
     * **An unvalidated network is still a network.** When nothing the phone has is validated —
     * a Wi-Fi whose captive-portal check cannot reach Google, a LAN with no way out but names of
     * its own — the platform makes it the default anyway, and every app on the phone uses it. A
     * filter that insisted on validation then held the resolvers of a network that had gone and
     * resolved nothing, which is worse than asking the only network there is.
     */
    fun <T> chooseUnderlying(candidates: List<Candidate<T>>, hint: TransportHint?): T? =
        candidates.sortedWith(
            compareByDescending<Candidate<T>> { it.validated }
                .thenByDescending { hint?.matches(it) == true }
                .thenBy { transportRank(it.wifi, it.ethernet, it.cellular) },
        ).firstOrNull()?.network

    /**
     * How a network ranks when nothing else distinguishes two of them — lowest first.
     *
     * The platform's own standing preference: a wire over Wi-Fi over mobile. Getting it wrong is
     * not fatal — the resolvers of the other network are usually reachable from this one — but
     * getting it right is what stops a phone on Wi-Fi asking a mobile network's resolvers.
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
     * same list from the same network is not worth adopting either**, because adopting closes
     * every pooled socket and forgets which resolver was answering — a re-check that fired on
     * every failed lookup would otherwise make a network outage cost more than the outage.
     *
     * The same list from a *different* network is, though: the pooled sockets are pinned to the
     * network that handed out the list, and two networks that hand out the same resolvers — every
     * emulator, and any pair of networks somebody pointed at the same public DNS — would otherwise
     * leave every lookup bound to the one that has gone, for as long as no callback came.
     */
    fun worthAdopting(current: List<InetAddress>, offered: List<InetAddress>, sameNetwork: Boolean): Boolean =
        offered.isNotEmpty() && (offered != current || !sameNetwork)

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
