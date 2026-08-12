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

    // ---- what stops a start ---------------------------------------------------------------

    @Test
    fun `an empty allow-list is refused rather than filtering everything`() {
        val settings = on.copy(scopeMode = AppScopeMode.ONLY_SELECTED, includedApps = emptySet())
        assertEquals(
            StartRefusal.NO_APPS_SELECTED,
            TunnelPolicy.refusal(settings, alwaysOnHeldElsewhere = false, hasConsent = true),
        )
    }

    @Test
    fun `always-on elsewhere outranks a missing consent`() {
        // Asking for consent would walk the user through a dialog Android refuses to honour.
        assertEquals(
            StartRefusal.ALWAYS_ON_ELSEWHERE,
            TunnelPolicy.refusal(on, alwaysOnHeldElsewhere = true, hasConsent = false),
        )
    }

    @Test
    fun `nothing in the way is nothing to report`() {
        assertNull(TunnelPolicy.refusal(on, alwaysOnHeldElsewhere = false, hasConsent = true))
        assertEquals(
            StartRefusal.NO_CONSENT,
            TunnelPolicy.refusal(on, alwaysOnHeldElsewhere = false, hasConsent = false),
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
            UpstreamDns.SYSTEM, "", listOf(parse("192.168.1.1")!!), sentinels, ::parse,
        )
        assertEquals(listOf("192.168.1.1"), resolved.map { it.hostAddress })
    }

    @Test
    fun `a network that hands out no resolver still leaves somewhere to ask`() {
        val resolved = TunnelPolicy.resolveUpstreams(UpstreamDns.SYSTEM, "", emptyList(), sentinels, ::parse)
        assertEquals(UpstreamDns.CLOUDFLARE.addresses, resolved.map { it.hostAddress })
    }

    @Test
    fun `a custom resolver typed wrong falls back instead of black-holing DNS`() {
        val resolved = TunnelPolicy.resolveUpstreams(UpstreamDns.CUSTOM, "not an address", emptyList(), sentinels, ::parse)
        assertEquals(UpstreamDns.CLOUDFLARE.addresses, resolved.map { it.hostAddress })
    }

    @Test
    fun `a custom resolver accepts several separators`() {
        val resolved = TunnelPolicy.resolveUpstreams(UpstreamDns.CUSTOM, "9.9.9.9, 1.1.1.1", emptyList(), sentinels, ::parse)
        assertEquals(listOf("9.9.9.9", "1.1.1.1"), resolved.map { it.hostAddress })
    }

    @Test
    fun `our own sentinel is never used as an upstream`() {
        // Forwarding to the address the tunnel itself advertises is a loop with no exit.
        val resolved = TunnelPolicy.resolveUpstreams(
            UpstreamDns.SYSTEM, "", listOf(parse("10.111.222.2")!!), sentinels, ::parse,
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
