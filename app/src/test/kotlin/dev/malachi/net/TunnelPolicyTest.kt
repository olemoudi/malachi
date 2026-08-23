package dev.malachi.net

import dev.malachi.data.AppRule
import dev.malachi.data.AppScopeMode
import dev.malachi.data.BypassGuard
import dev.malachi.data.MalachiSettings
import dev.malachi.data.UpstreamDns
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * The tunnel's decisions, tested without a tunnel.
 *
 * Simulated time throughout: a pause that runs for a quarter of an hour, a backoff that climbs
 * to five minutes and a filter left alone for a month are all just numbers here, so the whole
 * file runs in milliseconds instead of the days it describes.
 */
class TunnelPolicyTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private val on = MalachiSettings(filteringEnabled = true)

    /** Only a literal address, exactly like the Android original — never a DNS lookup. */
    private fun parse(text: String): InetAddress? = runCatching {
        if (text.isBlank()) null else InetAddress.getByAddress(text, literal(text) ?: return null)
    }.getOrNull()

    private fun literal(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        return parts.map { it.toIntOrNull() ?: return null }
            .also { if (it.any { n -> n !in 0..255 }) return null }
            .map { it.toByte() }
            .toByteArray()
    }

    private val sentinels = setOf("10.111.222.2", "fd00:6d61:6c61:6368::2")

    // ---- what a settings change asks for ------------------------------------------------

    @Test
    fun `filtering switched off stands the tunnel down`() {
        val action = TunnelPolicy.decide(on.copy(filteringEnabled = false), tunnelUp = true, currentShape = "x", nowMs = now)
        assertEquals(TunnelAction.StandDown, action)
    }

    @Test
    fun `a pause is honoured until its moment arrives and not one lookup longer`() {
        val paused = on.copy(pausedUntilMs = now + 15 * minute)

        assertEquals(TunnelAction.Pause(now + 15 * minute), TunnelPolicy.decide(paused, true, "x", now))
        // A second before: still paused.
        assertEquals(
            TunnelAction.Pause(now + 15 * minute),
            TunnelPolicy.decide(paused, true, "x", now + 15 * minute - 1_000),
        )
        // The moment it expires the answer flips, with no timer having fired.
        assertEquals(TunnelAction.Rebuild, TunnelPolicy.decide(paused, false, null, now + 15 * minute))
    }

    @Test
    fun `a pause the device slept through is simply over`() {
        // The resume timer runs on a clock that stops while the phone is suspended, so the
        // filter can be asked about a pause that expired hours ago. It has to answer "rebuild",
        // which is what makes any start — the watchdog's, a boot — enough to end it.
        val paused = on.copy(pausedUntilMs = now + 15 * minute)
        assertEquals(TunnelAction.Rebuild, TunnelPolicy.decide(paused, false, null, now + 9 * hour))
    }

    @Test
    fun `a stale pause has no time left to wait`() {
        assertEquals(0, TunnelPolicy.pauseRemainingMs(now, now + day))
        assertEquals(15 * minute, TunnelPolicy.pauseRemainingMs(now + 15 * minute, now))
    }

    @Test
    fun `no tunnel means build one`() {
        assertEquals(TunnelAction.Rebuild, TunnelPolicy.decide(on, tunnelUp = false, currentShape = null, nowMs = now))
    }

    @Test
    fun `a rule or a list change never rebuilds the tunnel`() {
        // The whole point of the shape: rules are read per query, so editing one must not cost
        // a teardown and a visible blink of unfiltered DNS.
        val shape = on.tunnelShape()
        val edited = on.copy(
            userBlocked = setOf("ads.example.com"),
            userAllowed = setOf("cdn.example.com"),
            appRules = listOf(AppRule("x.example.com", "com.example.app", block = true)),
            listChoices = mapOf("oisd-big" to true),
            blockAnswer = dev.malachi.data.BlockAnswerMode.NXDOMAIN,
            upstream = UpstreamDns.QUAD9,
            queryLogEnabled = false,
        )
        assertEquals(shape, edited.tunnelShape())
        assertEquals(TunnelAction.LeaveRunning, TunnelPolicy.decide(edited, tunnelUp = true, currentShape = shape, nowMs = now))
    }

    @Test
    fun `changing the app scope or the bypass guard does rebuild it`() {
        val shape = on.tunnelShape()
        val scoped = on.copy(excludedApps = setOf("com.bank.app"))
        val guarded = on.copy(bypassGuard = BypassGuard.PUBLIC_RESOLVERS)
        val mode = on.copy(scopeMode = AppScopeMode.ONLY_SELECTED, includedApps = setOf("com.example.app"))

        for (changed in listOf(scoped, guarded, mode)) {
            assertEquals(TunnelAction.Rebuild, TunnelPolicy.decide(changed, tunnelUp = true, currentShape = shape, nowMs = now))
        }
    }

    @Test
    fun `the same excluded apps in a different order are the same tunnel`() {
        val one = on.copy(excludedApps = setOf("com.b", "com.a"))
        val other = on.copy(excludedApps = setOf("com.a", "com.b"))
        assertEquals(one.tunnelShape(), other.tunnelShape())
    }

    @Test
    fun `changing the DNS server is noticed without rebuilding the tunnel`() {
        val system = on.copy(upstream = UpstreamDns.SYSTEM)
        val cloudflare = system.copy(upstream = UpstreamDns.CLOUDFLARE)
        val custom = system.copy(upstream = UpstreamDns.CUSTOM, customUpstream = "192.0.2.53")
        val otherCustom = custom.copy(customUpstream = "192.0.2.54")

        assertTrue(TunnelPolicy.upstreamMoved(system, cloudflare))
        assertTrue(TunnelPolicy.upstreamMoved(custom, otherCustom))
        assertFalse(TunnelPolicy.upstreamMoved(system, system))

        // And it stays out of the tunnel's shape, because noticing it must not cost a rebuild
        // and the blink of unfiltered DNS that comes with one.
        assertEquals(system.tunnelShape(), cloudflare.tunnelShape())
        assertEquals(system.tunnelShape(), otherCustom.tunnelShape())
        assertEquals(
            TunnelAction.LeaveRunning,
            TunnelPolicy.decide(cloudflare, tunnelUp = true, currentShape = system.tunnelShape(), nowMs = 0),
        )
    }

    // ---- what stops a start ---------------------------------------------------------------

    @Test
    fun `an empty allow-list is refused rather than filtering everything`() {
        val settings = on.copy(scopeMode = AppScopeMode.ONLY_SELECTED, includedApps = emptySet())
        assertEquals(
            StartRefusal.NO_APPS_SELECTED,
            TunnelPolicy.refusal(settings, alwaysOnHeldElsewhere = false, hasConsent = true, selectedAppsPresent = 0),
        )
    }

    @Test
    fun `an allow-list whose apps are all gone is refused too`() {
        // The same failure wearing different clothes, and the more dangerous of the two because
        // the screen still lists three apps. Every addAllowedApplication is refused for a package
        // that is not installed, and a builder that took none of them carries no restriction at
        // all — which Android reads as "filter every app on the phone".
        val settings = on.copy(
            scopeMode = AppScopeMode.ONLY_SELECTED,
            includedApps = setOf("com.gone.one", "com.gone.two"),
        )
        assertEquals(
            StartRefusal.NO_APPS_SELECTED,
            TunnelPolicy.refusal(settings, alwaysOnHeldElsewhere = false, hasConsent = true, selectedAppsPresent = 0),
        )
        // One survivor is a filter that does what it says, so it is not refused.
        assertNull(
            TunnelPolicy.refusal(settings, alwaysOnHeldElsewhere = false, hasConsent = true, selectedAppsPresent = 1),
        )
    }

    @Test
    fun `a scope that took none of its chosen apps is not selective`() {
        assertFalse(TunnelPolicy.scopeIsSelective(AppScopeMode.ONLY_SELECTED, applied = 0))
        assertTrue(TunnelPolicy.scopeIsSelective(AppScopeMode.ONLY_SELECTED, applied = 1))
        // "Everything except" needs nothing to have gone in: every app is in scope regardless,
        // and a refused exclusion costs that app its exemption rather than inverting the filter.
        assertTrue(TunnelPolicy.scopeIsSelective(AppScopeMode.ALL_EXCEPT, applied = 0))
    }

    @Test
    fun `always-on elsewhere outranks a missing consent`() {
        // Asking for consent would walk the user through a dialog Android refuses to honour.
        assertEquals(
            StartRefusal.ALWAYS_ON_ELSEWHERE,
            TunnelPolicy.refusal(on, alwaysOnHeldElsewhere = true, hasConsent = false, selectedAppsPresent = 0),
        )
    }

    @Test
    fun `nothing in the way is nothing to report`() {
        assertNull(
            TunnelPolicy.refusal(on, alwaysOnHeldElsewhere = false, hasConsent = true, selectedAppsPresent = 0),
        )
        assertEquals(
            StartRefusal.NO_CONSENT,
            TunnelPolicy.refusal(on, alwaysOnHeldElsewhere = false, hasConsent = false, selectedAppsPresent = 0),
        )
    }

    // ---- what a failed establish() means ---------------------------------------------------

    @Test
    fun `a withdrawn consent is reported, not retried`() {
        assertEquals(
            StartFailure.Report(TunnelProblem.NO_CONSENT),
            TunnelPolicy.diagnose(hasConsent = false, anotherVpnActive = false),
        )
    }

    @Test
    fun `another VPN holding the tunnel is retried`() {
        assertEquals(
            StartFailure.Retry(TunnelProblem.DISPLACED),
            TunnelPolicy.diagnose(hasConsent = true, anotherVpnActive = true),
        )
    }

    @Test
    fun `a failure with consent held and no VPN in sight is retried, not blamed on consent`() {
        // This is the case that used to tell the user their VPN permission was missing
        // immediately after confirming that it wasn't.
        val failure = TunnelPolicy.diagnose(hasConsent = true, anotherVpnActive = false)
        assertEquals(StartFailure.Retry(TunnelProblem.FAILED), failure)
        assertTrue(failure is StartFailure.Retry)
    }

    // ---- the backoff ------------------------------------------------------------------------

    @Test
    fun `the backoff doubles and then stops doubling`() {
        assertEquals(5_000, TunnelPolicy.retryDelayMs(0))
        assertEquals(10_000, TunnelPolicy.retryDelayMs(1))
        assertEquals(320_000, TunnelPolicy.retryDelayMs(6))
        // A filter that cannot start for a month must not grow a delay of years — or shift by
        // more than 63 and produce a negative one.
        assertEquals(320_000, TunnelPolicy.retryDelayMs(7))
        assertEquals(320_000, TunnelPolicy.retryDelayMs(1_000_000))
        assertEquals(5_000, TunnelPolicy.retryDelayMs(-3))
    }

    @Test
    fun `a month of failed retries costs a bounded number of wakeups`() {
        // Simulated: a month of backoff, counted rather than waited for.
        var elapsed = 0L
        var attempt = 0
        var wakeups = 0
        while (elapsed < 30 * day) {
            elapsed += TunnelPolicy.retryDelayMs(attempt)
            attempt++
            wakeups++
        }
        // At the capped delay that is one wakeup every 5m20s and no more.
        assertTrue(wakeups < 30 * 24 * 12, "a month of retries took $wakeups wakeups")
    }

    // ---- upstream selection -----------------------------------------------------------------

    @Test
    fun `system upstream follows the network`() {
        val resolved = TunnelPolicy.resolveUpstreams(
            UpstreamDns.SYSTEM, "", listOf(parse("192.168.1.1")!!), sentinels, parse = ::parse,
        )
        assertEquals(listOf("192.168.1.1"), resolved.map { it.hostAddress })
    }

    @Test
    fun `a network that hands out no resolver still leaves somewhere to ask`() {
        val resolved =
            TunnelPolicy.resolveUpstreams(UpstreamDns.SYSTEM, "", emptyList(), sentinels, parse = ::parse)
        assertEquals(UpstreamDns.CLOUDFLARE.addresses, resolved.map { it.hostAddress })
    }

    // ---- hotels, airports, coffee shops ------------------------------------------------------

    @Test
    fun `behind a captive portal the portal's own DNS server is the only one that can work`() {
        // The failure this prevents is total and reads as the app ignoring a setting: the portal
        // drops port 53 to anywhere but itself, so a phone set to Cloudflare resolves nothing at
        // all — including the sign-in page it is being asked to open — and the tunnel then fails
        // the platform's own validation, so every app on the phone is told there is no internet.
        val portalDns = listOf(parse("10.0.0.1")!!)
        val resolved = TunnelPolicy.resolveUpstreams(
            UpstreamDns.CLOUDFLARE, "", portalDns, sentinels, captivePortal = true, parse = ::parse,
        )
        assertEquals(listOf("10.0.0.1"), resolved.map { it.hostAddress })
    }

    @Test
    fun `signing in gives the chosen DNS server straight back`() {
        val portalDns = listOf(parse("10.0.0.1")!!)
        val resolved = TunnelPolicy.resolveUpstreams(
            UpstreamDns.CLOUDFLARE, "", portalDns, sentinels, captivePortal = false, parse = ::parse,
        )
        assertEquals(UpstreamDns.CLOUDFLARE.addresses, resolved.map { it.hostAddress })
    }

    @Test
    fun `a portal that hands out no resolver of its own is not a reason to ask nobody`() {
        val resolved = TunnelPolicy.resolveUpstreams(
            UpstreamDns.QUAD9, "", emptyList(), sentinels, captivePortal = true, parse = ::parse,
        )
        assertEquals(UpstreamDns.QUAD9.addresses, resolved.map { it.hostAddress })
    }

    // ---- what the bypass guard may route -----------------------------------------------------

    @Test
    fun `a named private DNS server stands the whole guard down`() {
        // Otherwise the guard routes 8-8-8-8 and 1-1-1-1 into a tun that answers UDP 53 and
        // nothing else — and with a hostname configured, every lookup the phone makes is DoT to
        // exactly one of those addresses, with no plaintext fallback. The phone stops resolving
        // anything at all, and the cause is us.
        val candidates = TunnelPolicy.guardCandidates(
            guard = BypassGuard.PUBLIC_RESOLVERS,
            networkDnsServers = listOf(parse("192.168.1.1")!!),
            publicResolvers = listOf(parse("8.8.8.8")!!, parse("1.1.1.1")!!),
            privateDnsActive = true,
            privateDnsHost = "dns.google",
        )
        assertTrue(candidates.isEmpty(), "the guard routed $candidates while the phone's own DNS went there")
    }

    @Test
    fun `automatic private DNS only excuses the network's own resolvers`() {
        // Automatic is opportunistic and does not defeat this filter: plaintext still flows, so
        // an app with a hardcoded resolver is still worth catching.
        val candidates = TunnelPolicy.guardCandidates(
            guard = BypassGuard.PUBLIC_RESOLVERS,
            networkDnsServers = listOf(parse("192.168.1.1")!!),
            publicResolvers = listOf(parse("8.8.8.8")!!),
            privateDnsActive = true,
            privateDnsHost = null,
        )
        assertEquals(listOf("8.8.8.8"), candidates.map { it.hostAddress })
    }

    @Test
    fun `with private DNS off the guard considers both lists`() {
        val candidates = TunnelPolicy.guardCandidates(
            guard = BypassGuard.PUBLIC_RESOLVERS,
            networkDnsServers = listOf(parse("192.168.1.1")!!),
            publicResolvers = listOf(parse("8.8.8.8")!!),
            privateDnsActive = false,
            privateDnsHost = null,
        )
        assertEquals(listOf("192.168.1.1", "8.8.8.8"), candidates.map { it.hostAddress })
        assertTrue(
            TunnelPolicy.guardCandidates(
                BypassGuard.OFF, listOf(parse("192.168.1.1")!!), listOf(parse("8.8.8.8")!!), false, null,
            ).isEmpty(),
        )
    }

    @Test
    fun `turning a named private DNS server on has to rebuild the tun`() {
        // The routes are frozen at establish(), so the guard cannot be told to let go afterwards.
        assertTrue(TunnelPolicy.guardMovedWithPrivateDns(BypassGuard.PUBLIC_RESOLVERS, null, "dns.google"))
        assertTrue(TunnelPolicy.guardMovedWithPrivateDns(BypassGuard.SYSTEM_RESOLVERS, "dns.google", null))
        // Not for a change between two named servers, and never when there is no guard to move.
        assertFalse(TunnelPolicy.guardMovedWithPrivateDns(BypassGuard.PUBLIC_RESOLVERS, "dns.google", "dns.quad9.net"))
        assertFalse(TunnelPolicy.guardMovedWithPrivateDns(BypassGuard.OFF, null, "dns.google"))
    }

    // ---- noticing that the resolvers belong to a network we have left -----------------------

    @Test
    fun `resolvers offered by the network we are on now are worth adopting`() {
        // Reported from a phone: the tunnel held a mobile network's four resolvers for eleven
        // hours after the phone joined a Wi-Fi that routed to none of them. Every lookup timed
        // out through all four, and nothing said why.
        val mobile = listOf(parse("80.58.61.250")!!, parse("80.58.61.254")!!)
        val wifi = listOf(parse("192.168.1.1")!!)
        assertTrue(TunnelPolicy.worthAdopting(mobile, wifi, sameNetwork = false))
    }

    @Test
    fun `the same resolvers from the same network are not worth adopting again`() {
        // Adopting closes every pooled socket and forgets which resolver was answering. A
        // re-check that fires whenever a lookup fails must cost nothing when nothing has changed,
        // or a network outage becomes more expensive than the outage.
        val current = listOf(parse("192.168.1.1")!!)
        assertFalse(TunnelPolicy.worthAdopting(current, listOf(parse("192.168.1.1")!!), sameNetwork = true))
    }

    @Test
    fun `the same resolvers from a different network are worth adopting`() {
        // Two networks that hand out the same addresses are two networks, and the pooled sockets
        // are pinned to the one that has gone. Every emulator does this — both its Wi-Fi and its
        // mobile network offer 10.0.2.3 — and so does any pair of networks pointed at the same
        // public resolver, which is a common thing for a router to be configured with.
        val current = listOf(parse("10.0.2.3")!!)
        assertTrue(TunnelPolicy.worthAdopting(current, listOf(parse("10.0.2.3")!!), sameNetwork = false))
    }

    @Test
    fun `nothing is never worth adopting`() {
        // LinkProperties arrive in stages. One that has no DNS servers yet would replace a
        // working list with the Cloudflare fallback — every lookup on the phone silently
        // rerouted to a resolver the user did not choose, for as long as the gap lasted.
        val current = listOf(parse("192.168.1.1")!!)
        assertFalse(TunnelPolicy.worthAdopting(current, emptyList(), sameNetwork = false))
        assertFalse(TunnelPolicy.worthAdopting(emptyList(), emptyList(), sameNetwork = true))
    }

    // ---- what the bypass guard may route ----------------------------------------------------

    @Test
    fun `the guard never routes the network's own router`() {
        // The bug behind "the Wi-Fi doesn't even ping with Malachi on". A home network hands out
        // its router as the DNS server, so the guard routed the router into a tunnel that carries
        // DNS and nothing else: a ping to it vanished, its admin page vanished, and a phone
        // probing its own gateway concluded the Wi-Fi was dead and left for mobile data.
        assertFalse(TunnelPolicy.routableByGuard(parse("192.168.1.1")!!, sentinels))
        assertFalse(TunnelPolicy.routableByGuard(parse("10.0.0.138")!!, sentinels))
        assertFalse(TunnelPolicy.routableByGuard(parse("172.16.0.1")!!, sentinels))
        // The carrier-grade NAT range, which is a mobile network's own equipment.
        assertFalse(TunnelPolicy.routableByGuard(parse("100.90.1.1")!!, sentinels))
        // And the IPv6 shapes of the same thing.
        assertFalse(TunnelPolicy.routableByGuard(InetAddress.getByName("fd00::1"), sentinels))
        assertFalse(TunnelPolicy.routableByGuard(InetAddress.getByName("fe80::1"), sentinels))
    }

    @Test
    fun `the guard still routes the addresses an app can actually have hardcoded`() {
        // Which is the whole point of it: nothing ships with 192.168.1.1 written inside it, and
        // everything that dodges a filter ships with 8.8.8.8.
        assertTrue(TunnelPolicy.routableByGuard(parse("8.8.8.8")!!, sentinels))
        assertTrue(TunnelPolicy.routableByGuard(parse("1.1.1.1")!!, sentinels))
        // An operator's own resolvers on a mobile network are public addresses and stay covered.
        assertTrue(TunnelPolicy.routableByGuard(parse("80.58.61.250")!!, sentinels))
        assertTrue(TunnelPolicy.routableByGuard(InetAddress.getByName("2001:4860:4860::8888"), sentinels))
    }

    @Test
    fun `the guard never routes the tunnel's own address`() {
        // Routing the sentinel upstream is a loop with no exit.
        assertFalse(TunnelPolicy.routableByGuard(parse("10.111.222.2")!!, sentinels))
        assertFalse(TunnelPolicy.routableByGuard(InetAddress.getByName("fd00:6d61:6c61:6368::2"), sentinels))
        assertFalse(TunnelPolicy.routableByGuard(parse("127.0.0.1")!!, sentinels))
        assertFalse(TunnelPolicy.routableByGuard(parse("0.0.0.0")!!, sentinels))
    }

    // ---- which network is under the tunnel ---------------------------------------------------

    private fun candidate(
        name: String,
        validated: Boolean = true,
        wifi: Boolean = false,
        ethernet: Boolean = false,
        cellular: Boolean = false,
    ) = TunnelPolicy.Candidate(name, validated, wifi, ethernet, cellular)

    @Test
    fun `a validated network beats an unvalidated one whatever it is carried by`() {
        val wifi = candidate("wifi", validated = false, wifi = true)
        val mobile = candidate("mobile", validated = true, cellular = true)
        assertEquals("mobile", TunnelPolicy.chooseUnderlying(listOf(wifi, mobile), hint = null))
    }

    @Test
    fun `the platform's own answer decides between two networks that both work`() {
        // The case that sends a phone's lookups to the network it just left. Android moves to
        // mobile when it judges a Wi-Fi poor — the Wi-Fi is still connected and still validated —
        // and a ranking that prefers Wi-Fi follows it back there, asks resolvers the phone is no
        // longer routed to, and tells the platform this tunnel runs on a network nothing is using.
        val wifi = candidate("wifi", wifi = true)
        val mobile = candidate("mobile", cellular = true)
        val onMobile = TunnelPolicy.TransportHint(wifi = false, ethernet = false, cellular = true)
        assertEquals("mobile", TunnelPolicy.chooseUnderlying(listOf(wifi, mobile), onMobile))
        val onWifi = TunnelPolicy.TransportHint(wifi = true, ethernet = false, cellular = false)
        assertEquals("wifi", TunnelPolicy.chooseUnderlying(listOf(wifi, mobile), onWifi))
    }

    @Test
    fun `a hint is never enough to choose a network that does not work`() {
        val wifi = candidate("wifi", validated = false, wifi = true)
        val mobile = candidate("mobile", cellular = true)
        val onWifi = TunnelPolicy.TransportHint(wifi = true, ethernet = false, cellular = false)
        assertEquals("mobile", TunnelPolicy.chooseUnderlying(listOf(wifi, mobile), onWifi))
    }

    @Test
    fun `with nothing to go on it is a wire, then Wi-Fi, then mobile`() {
        val candidates = listOf(
            candidate("mobile", cellular = true),
            candidate("wifi", wifi = true),
            candidate("wire", ethernet = true),
        )
        assertEquals("wire", TunnelPolicy.chooseUnderlying(candidates, hint = null))
        assertEquals("wifi", TunnelPolicy.chooseUnderlying(candidates.filterNot { it.network == "wire" }, null))
    }

    @Test
    fun `an unvalidated network is still better than no network at all`() {
        // Insisting on validation was right for pinning a socket and wrong for choosing whose
        // resolvers to ask. A phone whose Wi-Fi the platform has stopped vouching for — most of a
        // house with weak Wi-Fi — got no answer here at all, so the filter kept the resolvers of
        // a network it had left and every lookup on the phone timed out until something else
        // happened. The only network there is beats one that is gone.
        val struggling = candidate("wifi", validated = false, wifi = true)
        assertEquals("wifi", TunnelPolicy.chooseUnderlying(listOf(struggling), hint = null))
        assertNull(TunnelPolicy.chooseUnderlying(emptyList<TunnelPolicy.Candidate<String>>(), hint = null))
    }

    @Test
    fun `when we have to choose a network ourselves, we choose the way android does`() {
        // Only reached when the platform says the default is a VPN — ours — and will not say
        // what is underneath it. Wi-Fi over mobile is the platform's own preference, and picking
        // the other one is how a phone ends up asking the resolvers of a network it isn't using.
        val wifi = TunnelPolicy.transportRank(wifi = true, ethernet = false, cellular = false)
        val cellular = TunnelPolicy.transportRank(wifi = false, ethernet = false, cellular = true)
        val ethernet = TunnelPolicy.transportRank(wifi = false, ethernet = true, cellular = false)
        val other = TunnelPolicy.transportRank(wifi = false, ethernet = false, cellular = false)
        assertTrue(ethernet < wifi)
        assertTrue(wifi < cellular)
        assertTrue(cellular < other)
    }

    @Test
    fun `a custom resolver typed wrong falls back instead of black-holing DNS`() {
        val resolved = TunnelPolicy.resolveUpstreams(UpstreamDns.CUSTOM, "not an address", emptyList(), sentinels, parse = ::parse)
        assertEquals(UpstreamDns.CLOUDFLARE.addresses, resolved.map { it.hostAddress })
    }

    @Test
    fun `a custom resolver accepts several separators`() {
        val resolved = TunnelPolicy.resolveUpstreams(UpstreamDns.CUSTOM, "9.9.9.9, 1.1.1.1", emptyList(), sentinels, parse = ::parse)
        assertEquals(listOf("9.9.9.9", "1.1.1.1"), resolved.map { it.hostAddress })
    }

    @Test
    fun `our own sentinel is never used as an upstream`() {
        // Forwarding to the address the tunnel itself advertises is a loop with no exit.
        val resolved = TunnelPolicy.resolveUpstreams(
            UpstreamDns.SYSTEM, "", listOf(parse("10.111.222.2")!!), sentinels, parse = ::parse,
        )
        assertEquals(UpstreamDns.CLOUDFLARE.addresses, resolved.map { it.hostAddress })
        assertFalse(resolved.any { it.hostAddress in sentinels })
    }

    @Test
    fun `a query prefers a resolver of its own address family`() {
        val v4 = parse("1.1.1.1")!!
        val v6 = InetAddress.getByName("2606:4700:4700::1111")
        assertEquals(v4, TunnelPolicy.pickUpstream(listOf(v4, v6), wantsIpv6 = false))
        assertEquals(v6, TunnelPolicy.pickUpstream(listOf(v4, v6), wantsIpv6 = true))
        // And takes what there is rather than dropping the lookup.
        assertEquals(v4, TunnelPolicy.pickUpstream(listOf(v4), wantsIpv6 = true))
        assertNull(TunnelPolicy.pickUpstream(emptyList(), wantsIpv6 = false))
    }

    @Test
    fun `every resolver the network offered is worth asking, not just the first`() {
        // The bug this exists for: a Wi-Fi advertised two resolvers and the first never answered.
        // Android's own resolver moved to the second, so with the filter off the network looked
        // healthy; Malachi asked the silent one every time and dropped the lookup, and nothing on
        // the phone loaded until it was switched off or the network changed.
        val dead = parse("192.168.1.1")!!
        val good = parse("1.1.1.1")!!

        assertEquals(listOf(dead, good), TunnelPolicy.orderUpstreams(listOf(dead, good), wantsIpv6 = false))
        // Once one has answered it goes first, so a dud costs its timeout once and not forever.
        assertEquals(
            listOf(good, dead),
            TunnelPolicy.orderUpstreams(listOf(dead, good), wantsIpv6 = false, preferred = good),
        )
    }

    @Test
    fun `ordering keeps the same family in front but still offers the other`() {
        val v4 = parse("1.1.1.1")!!
        val v6 = InetAddress.getByName("2606:4700:4700::1111")

        assertEquals(listOf(v6, v4), TunnelPolicy.orderUpstreams(listOf(v4, v6), wantsIpv6 = true))
        assertEquals(listOf(v4, v6), TunnelPolicy.orderUpstreams(listOf(v4, v6), wantsIpv6 = false))
        // A preferred resolver of the other family still wins: reaching the resolver and carrying
        // the answer back are different journeys, and only the second one has to match.
        assertEquals(
            listOf(v4, v6),
            TunnelPolicy.orderUpstreams(listOf(v4, v6), wantsIpv6 = true, preferred = v4),
        )
        assertEquals(emptyList<InetAddress>(), TunnelPolicy.orderUpstreams(emptyList(), wantsIpv6 = false))
    }

    @Test
    fun `the budget is divided so the last resolver is still reachable`() {
        // Handing the whole budget to the first candidate is the same bug in another shape: the
        // working resolver underneath a silent one never gets asked at all.
        assertEquals(2_500, TunnelPolicy.attemptBudgetMs(remaining = 5_000, left = 2, floorMs = 1_200))
        assertEquals(1_666, TunnelPolicy.attemptBudgetMs(remaining = 5_000, left = 3, floorMs = 1_200))
        // The last one may have everything that is left.
        assertEquals(5_000, TunnelPolicy.attemptBudgetMs(remaining = 5_000, left = 1, floorMs = 1_200))
        // A floor, so a long list doesn't slice the budget into intervals too short to answer in.
        assertEquals(1_200, TunnelPolicy.attemptBudgetMs(remaining = 5_000, left = 8, floorMs = 1_200))
        // …but never more than there is.
        assertEquals(400, TunnelPolicy.attemptBudgetMs(remaining = 400, left = 4, floorMs = 1_200))
    }

    // ---- the cheap per-query switches --------------------------------------------------------

    @Test
    fun `attribution is skipped only when nothing needs it`() {
        assertFalse(TunnelPolicy.attributionNeeded(on.copy(queryLogEnabled = false)))
        assertTrue(TunnelPolicy.attributionNeeded(on.copy(queryLogEnabled = true)))
        assertTrue(
            TunnelPolicy.attributionNeeded(
                on.copy(queryLogEnabled = false, appRules = listOf(AppRule("a.com", "com.example", true))),
            ),
        )
        // Diagnosing one app is the case that needs it even with the log off — which is exactly
        // the configuration somebody who keeps no record of their browsing would be in.
        assertTrue(
            TunnelPolicy.attributionNeeded(on.copy(queryLogEnabled = false, diagnoseApp = "com.example.game")),
        )
    }

    @Test
    fun `switching the query log off is what forgets it`() {
        val logging = on.copy(queryLogEnabled = true)
        val quiet = on.copy(queryLogEnabled = false)
        assertTrue(TunnelPolicy.forgetsQueryLog(logging, quiet))
        assertFalse(TunnelPolicy.forgetsQueryLog(quiet, quiet))
        assertFalse(TunnelPolicy.forgetsQueryLog(quiet, logging))
        assertFalse(TunnelPolicy.forgetsQueryLog(logging, logging))
    }
}
