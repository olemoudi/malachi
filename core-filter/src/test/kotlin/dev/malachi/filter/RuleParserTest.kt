package dev.malachi.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleParserTest {

    private fun parse(line: String) = RuleParser.parseLine(line)

    private fun blocks(line: String, domain: String) =
        assertEquals(listOf(Rule.Block(domain)), parse(line), "line: $line")

    private fun skips(line: String) =
        assertTrue(parse(line).isEmpty(), "expected to skip: $line")

    // --- hosts format (StevenBlack, AdAway, Dan Pollock, Peter Lowe) ---

    @Test
    fun `hosts lines yield the mapped name`() {
        blocks("0.0.0.0 ads.example.com", "ads.example.com")
        blocks("127.0.0.1 ads.example.com", "ads.example.com")
        blocks("0.0.0.0\tads.example.com", "ads.example.com")
        blocks(":: ads.example.com", "ads.example.com")
    }

    @Test
    fun `a hosts line may map several names`() {
        assertEquals(
            listOf(Rule.Block("a.example.com"), Rule.Block("b.example.com")),
            parse("0.0.0.0 a.example.com b.example.com"),
        )
    }

    @Test
    fun `hosts comments are stripped`() {
        blocks("0.0.0.0 ads.example.com # an ad network", "ads.example.com")
        skips("# just a comment")
        skips("")
        skips("   ")
    }

    @Test
    fun `the loopback bookkeeping in a hosts file is not a blocklist entry`() {
        skips("127.0.0.1 localhost")
        skips("::1 ip6-localhost")
        skips("255.255.255.255 broadcasthost")
        skips("0.0.0.0 0.0.0.0")
    }

    @Test
    fun `a hosts line pointing at a real address is left alone`() {
        // Someone's own /etc/hosts entry, not an instruction to block anything.
        skips("192.168.1.10 nas.local.example.com")
    }

    // --- plain domain lists ---

    @Test
    fun `a bare domain on its own line is a block rule`() {
        blocks("ads.example.com", "ads.example.com")
        blocks("  ads.example.com  ", "ads.example.com")
    }

    // --- Adblock syntax (AdGuard DNS filter, OISD, HaGeZi, EasyPrivacy) ---

    @Test
    fun `an anchored domain rule blocks that domain`() {
        blocks("||ads.example.com^", "ads.example.com")
        blocks("||ads.example.com", "ads.example.com")
        blocks("||ads.example.com^|", "ads.example.com")
        blocks("||*.ads.example.com^", "ads.example.com")
    }

    @Test
    fun `an exception rule allows the domain`() {
        assertEquals(listOf(Rule.Allow("cdn.example.com")), parse("@@||cdn.example.com^"))
    }

    @Test
    fun `modifiers that do not change the DNS meaning are honoured`() {
        blocks("||ads.example.com^\$important", "ads.example.com")
        blocks("||ads.example.com^\$important,all", "ads.example.com")
    }

    @Test
    fun `a rule conditional on something DNS cannot see is skipped, not guessed at`() {
        // Blocking these unconditionally would over-block silently, and the user debugging it
        // would find a rule the list never wrote.
        skips("||ads.example.com^\$domain=news.example")
        skips("||ads.example.com^\$third-party")
        skips("||ads.example.com^\$script,image")
        skips("||ads.example.com^\$dnsrewrite=1.2.3.4")
        skips("||ads.example.com^\$client=192.168.1.1")
        skips("||ads.example.com^\$badfilter")
    }

    @Test
    fun `syntax that is not about a domain name is skipped`() {
        skips("! a comment")
        skips("[Adblock Plus 2.0]")
        skips("example.com##.ad-banner")
        skips("example.com#@#.ad-banner")
        skips("example.com#%#//scriptlet('abort-on-property-read')")
        skips("/banner\\d+\\.gif/")
        skips("|http://ads.example.com/")
        skips("||example.com/ads/*")
        skips("||ads.*^")
        skips("||example.com^\$popup")
    }

    @Test
    fun `a whole TLD is never blockable through a list`() {
        skips("||com^")
        skips("0.0.0.0 com")
    }

    @Test
    fun `real header lines from the lists we subscribe to are skipped`() {
        skips("! Title: AdGuard DNS filter")
        skips("! Version: 1.0.75.46")
        skips("# This hosts file is a merged collection of hosts")
        skips("[Adblock Plus]")
    }
}
