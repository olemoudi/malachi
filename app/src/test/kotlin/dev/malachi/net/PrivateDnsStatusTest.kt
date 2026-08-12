package dev.malachi.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which Private DNS setting actually defeats the filter.
 *
 * Only one of the two does, and the app used to treat them as the same thing — which meant
 * telling almost every user that nothing was being filtered while everything was, because
 * *automatic* is Android's default and it is harmless here.
 *
 * Measured on a device by probing three unique domains in each mode and reading the query log:
 *
 * | Private DNS | lookups Malachi saw |
 * | --- | --- |
 * | Off | 3 of 3 |
 * | Automatic (opportunistic) | 3 of 3 |
 * | Strict (a hostname) | 0 of 3 |
 *
 * Automatic works because Android's opportunistic mode only encrypts when the resolver on that
 * network offers DNS-over-TLS, and the resolver this tunnel advertises is a sentinel address
 * that answers on port 53 and nothing else — so the probe fails and the system falls back to
 * plain DNS, straight into the filter. Strict names a resolver and goes there over TLS
 * regardless, and none of that traffic is ours to see.
 */
class PrivateDnsStatusTest {

    @Test
    fun `a named resolver is the case that breaks filtering`() {
        val strict = FilterStatus(tunnelUp = true, privateDnsActive = true, privateDnsHost = "dns.google")
        assertTrue(strict.privateDnsStrict)
        assertFalse(strict.privateDnsAutomatic)
    }

    @Test
    fun `automatic is on but names nobody, and filtering is unaffected`() {
        val automatic = FilterStatus(tunnelUp = true, privateDnsActive = true, privateDnsHost = null)
        assertFalse(automatic.privateDnsStrict, "automatic must never be reported as the fatal case")
        assertTrue(automatic.privateDnsAutomatic)
    }

    @Test
    fun `private DNS switched off is neither`() {
        val off = FilterStatus(tunnelUp = true)
        assertFalse(off.privateDnsStrict)
        assertFalse(off.privateDnsAutomatic)
    }

    @Test
    fun `a hostname without the active flag still counts as strict`() {
        // The flag means "DoT is validated and in use on this network"; the hostname means the
        // user has named a resolver. A strict setting whose server is momentarily unreachable is
        // still a phone whose lookups will never reach this filter, so it is still the red one.
        val strictButUnvalidated = FilterStatus(privateDnsActive = false, privateDnsHost = "dns.adguard.com")
        assertTrue(strictButUnvalidated.privateDnsStrict)
    }

    @Test
    fun `a lockdown warning survives the tunnel coming up`() {
        // Caught on a device: the warning appeared and vanished within the same second. Building
        // a fresh status is how a stale *problem* is cleared, but lockdown is a switch in
        // Android's settings rather than a property of this tunnel attempt — and it is the only
        // thing that explains a phone with no connection at all, so losing it is expensive.
        VpnStatus.lockdown(true)
        VpnStatus.up(upstream = "system", privateDnsActive = false, privateDnsHost = null)

        assertTrue(VpnStatus.status.value.lockdown, "the tunnel coming up erased the lockdown warning")
        assertTrue(VpnStatus.status.value.tunnelUp)

        VpnStatus.lockdown(false)
        assertFalse(VpnStatus.status.value.lockdown, "the warning outstayed the setting")
    }

    @Test
    fun `neither flavour is a problem the filter status reports as needing consent`() {
        // needsUser drives a different card entirely; Private DNS has its own, with its own action.
        val strict = FilterStatus(privateDnsActive = true, privateDnsHost = "dns.google")
        assertFalse(strict.needsUser)
    }
}
