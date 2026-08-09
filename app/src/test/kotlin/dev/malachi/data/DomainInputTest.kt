package dev.malachi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * What people actually type is rarely a domain: it is something copied out of an address bar,
 * out of the query log, or out of a blocklist. Every one of those should work.
 */
class DomainInputTest {

    @Test
    fun `a plain domain passes through, lowercased`() {
        assertEquals("ads.example.com", DomainInput.parse("Ads.Example.COM"))
        assertEquals("ads.example.com", DomainInput.parse("  ads.example.com  "))
    }

    @Test
    fun `a url is reduced to its host`() {
        assertEquals("ads.example.com", DomainInput.parse("https://ads.example.com/tag?id=4"))
        assertEquals("ads.example.com", DomainInput.parse("http://ads.example.com"))
        assertEquals("ads.example.com", DomainInput.parse("https://user:pw@ads.example.com:8443/x"))
    }

    @Test
    fun `a fully qualified name from the query log loses its trailing dot`() {
        assertEquals("ads.example.com", DomainInput.parse("ads.example.com."))
    }

    @Test
    fun `adblock syntax pasted from a list is accepted`() {
        assertEquals("ads.example.com", DomainInput.parse("||ads.example.com^"))
        assertEquals("ads.example.com", DomainInput.parse("||ads.example.com^|"))
        assertEquals("ads.example.com", DomainInput.parse("*.ads.example.com"))
    }

    @Test
    fun `things that are not host names are refused rather than stored`() {
        // A rule that can never match is worse than an error message: it looks like it worked.
        assertNull(DomainInput.parse(""))
        assertNull(DomainInput.parse("   "))
        assertNull(DomainInput.parse("com"))
        assertNull(DomainInput.parse("not a domain"))
        assertNull(DomainInput.parse("[2001:db8::1]"))
        assertNull(DomainInput.parse("https://"))
        assertNull(DomainInput.parse("..example.com"))
    }

    @Test
    fun `an ipv4 literal is accepted as written`() {
        // It is syntactically a valid name, so it is stored rather than rejected with an error
        // the user can't act on. It simply never matches: no DNS question ever asks for it.
        assertEquals("1.1.1.1", DomainInput.parse("1.1.1.1"))
    }
}
