package dev.malachi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The custom DNS box used to accept anything, and the tunnel then quietly used Cloudflare for
 * whatever it could not parse — two screens naming one server while lookups went to another.
 */
class UpstreamInputTest {

    private fun numeric(text: String) = text.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' } && text.any { it.isDigit() }

    @Test
    fun `addresses separated by commas or spaces are all accepted`() {
        assertEquals(listOf("9.9.9.9", "1.1.1.1", "2620:fe::fe"), UpstreamInput.parse("9.9.9.9, 1.1.1.1 2620:fe::fe", ::numeric))
    }

    @Test
    fun `a name anywhere in the text refuses the whole of it`() {
        // Not "the addresses that did parse": somebody who typed a name meant that server, and
        // silently using the others is the failure this exists to end.
        assertNull(UpstreamInput.parse("dns.google", ::numeric))
        assertNull(UpstreamInput.parse("9.9.9.9, dns.google", ::numeric))
    }

    @Test
    fun `nothing at all is not a server`() {
        assertNull(UpstreamInput.parse("", ::numeric))
        assertNull(UpstreamInput.parse(" , ", ::numeric))
    }
}
