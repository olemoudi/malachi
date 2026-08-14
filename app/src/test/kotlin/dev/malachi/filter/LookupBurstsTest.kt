package dev.malachi.filter

import dev.malachi.filter.dns.DnsMessage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LookupBurstsTest {

    private val bursts = LookupBursts()

    private fun begins(key: Int, type: Int, atMs: Long) = bursts.beginsLookup(key, type, atMs)

    @Test
    fun `the AAAA that follows an A is the same lookup`() {
        // The whole bug: Android resolves one name by asking for both at once, so every row in
        // the log announced itself as "seen 2 times" the first time it was ever seen.
        assertTrue(begins(1, DnsMessage.TYPE_A, 0))
        assertFalse(begins(1, DnsMessage.TYPE_AAAA, 3))
    }

    @Test
    fun `a browser's third question is still the same lookup`() {
        assertTrue(begins(1, DnsMessage.TYPE_A, 0))
        assertFalse(begins(1, DnsMessage.TYPE_AAAA, 1))
        assertFalse(begins(1, DnsMessage.TYPE_HTTPS, 2))
    }

    @Test
    fun `asking again for the same record type is a second lookup`() {
        // This is what a retrying client looks like, and it is the signal the activity screen
        // exists to show. A burst window that swallowed it would be worse than the double count.
        assertTrue(begins(1, DnsMessage.TYPE_A, 0))
        assertFalse(begins(1, DnsMessage.TYPE_AAAA, 1))
        assertTrue(begins(1, DnsMessage.TYPE_A, 500))
        assertFalse(begins(1, DnsMessage.TYPE_AAAA, 501))
    }

    @Test
    fun `two resolutions far apart are two lookups`() {
        assertTrue(begins(1, DnsMessage.TYPE_A, 0))
        assertTrue(begins(1, DnsMessage.TYPE_AAAA, LookupBursts.WINDOW_MS + 1))
    }

    @Test
    fun `interleaved apps keep their own bursts`() {
        // Two apps resolving at the same moment is the ordinary case on a phone waking up, and
        // one slot between them would count every companion as a lookup of its own.
        assertTrue(begins(1, DnsMessage.TYPE_A, 0))
        assertTrue(begins(2, DnsMessage.TYPE_A, 1))
        assertFalse(begins(1, DnsMessage.TYPE_AAAA, 2))
        assertFalse(begins(2, DnsMessage.TYPE_AAAA, 3))
    }

    @Test
    fun `a name never occupies two slots`() {
        // Repeating one name must not push the other bursts in flight out of the ring.
        repeat(LookupBursts.SLOTS * 4) { i -> begins(1, DnsMessage.TYPE_A, i * 10L) }
        assertTrue(begins(2, DnsMessage.TYPE_A, 1000))
        assertFalse(begins(2, DnsMessage.TYPE_AAAA, 1001))
    }

    @Test
    fun `an unknown record type still pairs with itself`() {
        assertTrue(begins(1, 33, 0)) // SRV
        assertFalse(begins(1, DnsMessage.TYPE_A, 1))
        assertTrue(begins(1, 16, 2)) // TXT, which shares the "everything else" bit with SRV
    }

    @Test
    fun `clearing starts counting again`() {
        assertTrue(begins(1, DnsMessage.TYPE_A, 0))
        bursts.clear()
        assertTrue(begins(1, DnsMessage.TYPE_AAAA, 1))
    }
}
