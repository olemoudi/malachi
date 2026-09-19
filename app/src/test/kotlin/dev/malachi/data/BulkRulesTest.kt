package dev.malachi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BulkRulesTest {

    @Test
    fun `every shape a clipboard realistically holds becomes a domain`() {
        val text = """
            # a hosts file
            0.0.0.0 ads.example.com tracker.example.com
            127.0.0.1 localhost
            ||adblock.example.net^
            plain.example.org
            https://www.Example.COM/path?q=1
            one.example.com, two.example.com; three.example.com
        """.trimIndent()
        val parsed = BulkRules.parse(text)
        assertEquals(
            listOf(
                "ads.example.com", "tracker.example.com", "adblock.example.net", "plain.example.org",
                "www.example.com", "one.example.com", "two.example.com", "three.example.com",
            ),
            parsed.domains,
        )
        // `localhost` is a hosts file talking about itself; a comment is not a line of anything.
        assertEquals(1, parsed.skipped)
        assertFalse(parsed.truncated)
    }

    @Test
    fun `an exception line is skipped rather than turned into its opposite`() {
        val parsed = BulkRules.parse("@@||good.example.com^\nbad.example.com")
        assertEquals(listOf("bad.example.com"), parsed.domains)
        assertEquals(1, parsed.skipped)
    }

    @Test
    fun `lines that name nothing are counted, not guessed at`() {
        val parsed = BulkRules.parse("not a domain at all\ncom\n||ads.example.com^\$third-party\n!comment\n\n")
        assertTrue(parsed.domains.isEmpty())
        assertEquals(3, parsed.skipped)
    }

    @Test
    fun `a paste repeats itself without writing a rule twice`() {
        val parsed = BulkRules.parse("a.example.com\nA.EXAMPLE.COM.\n0.0.0.0 a.example.com")
        assertEquals(listOf("a.example.com"), parsed.domains)
    }

    @Test
    fun `a whole blocklist is cut at the ceiling and says so`() {
        val text = (0 until BulkRules.MAX_DOMAINS + 50).joinToString("\n") { "host$it.example.com" }
        val parsed = BulkRules.parse(text)
        assertEquals(BulkRules.MAX_DOMAINS, parsed.domains.size)
        assertTrue(parsed.truncated)
    }
}
