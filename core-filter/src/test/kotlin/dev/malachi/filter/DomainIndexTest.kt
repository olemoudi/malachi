package dev.malachi.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DomainIndexTest {

    private val index = DomainIndex.of(listOf("ads.example.com", "tracker.net"))

    @Test
    fun `an entry matches itself and everything under it`() {
        assertTrue(index.matches("ads.example.com"))
        assertTrue(index.matches("eu.ads.example.com"))
        assertTrue(index.matches("a.b.c.ads.example.com"))
    }

    @Test
    fun `an entry never matches its own parent`() {
        assertFalse(index.matches("example.com"))
        assertFalse(index.matches("com"))
    }

    @Test
    fun `matching is by label, not by substring`() {
        assertFalse(index.matches("nottracker.net"))
        assertFalse(index.matches("tracker.net.evil.com"))
    }

    @Test
    fun `case and a trailing dot make no difference`() {
        assertTrue(index.matches("EU.Ads.Example.COM."))
    }

    @Test
    fun `match depth counts the labels that had to be dropped`() {
        assertEquals(0, index.matchDepth("ads.example.com"))
        assertEquals(1, index.matchDepth("eu.ads.example.com"))
        assertEquals(3, index.matchDepth("a.b.c.ads.example.com"))
        assertEquals(-1, index.matchDepth("example.com"))
    }

    @Test
    fun `duplicates collapse`() {
        val built = DomainIndex.of(listOf("a.com", "a.com", "A.COM.", "b.com"))
        assertEquals(2, built.size)
    }

    @Test
    fun `a compiled index survives a round trip`() {
        val bytes = ByteArrayOutputStream().also { index.write(it) }.toByteArray()
        val restored = DomainIndex.read(ByteArrayInputStream(bytes))
        assertEquals(index.size, restored.size)
        assertTrue(restored.matches("eu.ads.example.com"))
        assertFalse(restored.matches("example.com"))
    }

    @Test
    fun `an empty index matches nothing`() {
        assertFalse(DomainIndex.EMPTY.matches("anything.com"))
        assertEquals(0, DomainIndex.EMPTY.size)
    }

    @Test
    fun `containsExact does not walk up the tree`() {
        assertTrue(index.containsExact("ads.example.com"))
        assertFalse(index.containsExact("eu.ads.example.com"))
    }

    // Normalization is the app's only guard against a malformed list line becoming a rule, so
    // what it refuses matters as much as what it accepts.

    @Test
    fun `a single-label name is refused so no list can block a whole TLD`() {
        assertNull(DomainIndex.normalizeHost("com"))
        assertNull(DomainIndex.normalizeHost("localhost"))
        assertFalse(DomainIndex.of(listOf("com")).matches("example.com"))
    }

    @Test
    fun `wildcards and trailing dots are normalized away`() {
        assertEquals("example.com", DomainIndex.normalizeHost("*.example.com"))
        assertEquals("example.com", DomainIndex.normalizeHost("Example.COM."))
    }

    @Test
    fun `anything that is not a hostname is refused`() {
        assertNull(DomainIndex.normalizeHost(""))
        assertNull(DomainIndex.normalizeHost("   "))
        assertNull(DomainIndex.normalizeHost(".example.com"))
        assertNull(DomainIndex.normalizeHost("exa..mple.com"))
        assertNull(DomainIndex.normalizeHost("example.com/path"))
        assertNull(DomainIndex.normalizeHost("http://example.com"))
        assertNull(DomainIndex.normalizeHost("example.com:53"))
        assertNull(DomainIndex.normalizeHost("a".repeat(64) + ".com"))
        assertNull(DomainIndex.normalizeHost("a".repeat(250) + ".example.com"))
    }

    @Test
    fun `underscores and digits are accepted, as real lists contain them`() {
        assertEquals("_dmarc.example4.com", DomainIndex.normalizeHost("_dmarc.example4.com"))
    }

    @Test
    fun `a large index still answers correctly`() {
        val many = (0 until 50_000).map { "host$it.example.com" }
        val big = DomainIndex.of(many)
        assertEquals(50_000, big.size)
        assertTrue(big.matches("host49999.example.com"))
        assertTrue(big.matches("cdn.host0.example.com"))
        assertFalse(big.matches("host50000.example.com"))
    }
}
