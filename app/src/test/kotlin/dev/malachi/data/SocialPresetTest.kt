package dev.malachi.data

import dev.malachi.filter.DomainIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SocialPresetTest {

    private val news = "com.example.news"

    @Test
    fun `every entry is a registrable domain the engine can hold, and none is listed twice`() {
        val all = SocialPreset.networks.flatMap { it.domains }
        assertEquals(all.size, all.toSet().size, "a domain is in two networks")
        all.forEach { domain ->
            assertEquals(domain, DomainIndex.normalizeHost(domain), domain)
            // A suffix rule, so a bare TLD would be everything, and a deep host would be a guess.
            assertEquals(1, domain.count { it == '.' }, "$domain is not a registrable domain")
        }
        assertEquals(SocialPreset.networks.size, SocialPreset.networks.map { it.id }.toSet().size)
    }

    @Test
    fun `a network is never offered inside its own app`() {
        assertTrue(SocialPreset.offeredFor("com.instagram.android").none { it.id == "meta" })
        assertTrue(SocialPreset.offeredFor("com.whatsapp").none { it.id == "meta" })
        assertTrue(SocialPreset.offeredFor("com.linkedin.android").none { it.id == "linkedin" })
        assertEquals(SocialPreset.networks.size, SocialPreset.offeredFor(news).size)
    }

    @Test
    fun `applying writes block rules for this app only, replacing what it had for those names`() {
        val before = MalachiSettings(
            appRules = listOf(
                AppRule("facebook.com", news, block = false),
                AppRule("facebook.com", "com.other.app", block = false),
            ),
        )
        val after = SocialPreset.applied(before, news, setOf("meta", "linkedin"))
        val mine = after.appRulesFor(news)
        assertTrue(mine.all { it.block })
        assertEquals(
            SocialPreset.networks.filter { it.id == "meta" || it.id == "linkedin" }.flatMap { it.domains }.toSet(),
            mine.map { it.domain }.toSet(),
        )
        // Somebody else's exception is somebody else's decision.
        assertTrue(after.appRules.contains(AppRule("facebook.com", "com.other.app", block = false)))
        assertEquals(setOf("meta", "linkedin"), SocialPreset.blockedIn(after, news))
    }

    @Test
    fun `nothing chosen writes nothing`() {
        val before = MalachiSettings()
        assertTrue(SocialPreset.applied(before, news, emptySet()) === before)
    }

    @Test
    fun `removing takes back the preset's blocks and leaves every other rule alone`() {
        val applied = SocialPreset.applied(MalachiSettings(), news, setOf("meta", "x"))
            .withAppRule("ads.example.com", news, block = true)
            .withAppRule("twimg.com", news, block = false)
        val removed = SocialPreset.removed(applied, news, setOf("meta", "x"))
        assertEquals(
            setOf(AppRule("ads.example.com", news, true), AppRule("twimg.com", news, false)),
            removed.appRulesFor(news).toSet(),
        )
        assertTrue(SocialPreset.blockedIn(removed, news).isEmpty())
    }
}
