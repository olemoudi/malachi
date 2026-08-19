package dev.malachi.lists

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlocklistCatalogTest {

    @Test
    fun `every source is well formed`() {
        for (source in BlocklistCatalog.sources) {
            assertTrue(source.url.startsWith("https://"), "${source.id} is not fetched over https")
            assertTrue(source.homepage.startsWith("https://"), "${source.id} has no homepage")
            assertTrue(source.title.isNotBlank(), "${source.id} has no title")
            assertTrue(source.maintainer.isNotBlank(), "${source.id} names no maintainer")
        }
    }

    @Test
    fun `ids and urls are unique`() {
        // A duplicated id would silently overwrite the other list's compiled index on disk.
        val ids = BlocklistCatalog.sources.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        val urls = BlocklistCatalog.sources.map { it.url }
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `the defaults are the conservative ones`() {
        val defaults = BlocklistCatalog.sources.filter { it.enabledByDefault }.map { it.id }
        assertEquals(setOf("adguard-dns", "adaway"), defaults.toSet())
        // Growing the catalogue must never grow what a fresh install downloads. Everything
        // outside Ads is opt-in, and the two above are the conservative pair.
        assertTrue(
            BlocklistCatalog.sources.none {
                it.enabledByDefault && it.category != BlocklistCategory.ADS
            },
        )
    }

    @Test
    fun `a collapsed list is refused and a small honest one is not`() {
        // The absolute floor this replaced was wrong in both directions, and both were seen: a
        // regional list with thirty-odd entries failed every refresh after its first, forever,
        // while a list falling from a quarter of a million entries to two hundred sailed through.
        assertNull(BlocklistStore.collapsed(entries = 34, previousEntries = 34, hadCompiledCopy = true))
        assertNull(BlocklistStore.collapsed(entries = 34, previousEntries = 0, hadCompiledCopy = false))
        assertNotNull(BlocklistStore.collapsed(entries = 200, previousEntries = 250_000, hadCompiledCopy = true))

        // Nothing usable is never right, with or without something to compare against — this is
        // the captive portal serving a login page with a 200.
        assertNotNull(BlocklistStore.collapsed(entries = 0, previousEntries = 0, hadCompiledCopy = false))
        assertNotNull(BlocklistStore.collapsed(entries = 0, previousEntries = 5_000, hadCompiledCopy = true))

        // Ordinary drift is not a collapse: lists lose entries every day.
        assertNull(BlocklistStore.collapsed(entries = 96_000, previousEntries = 100_000, hadCompiledCopy = true))
    }

    @Test
    fun `every list in the catalogue can actually yield a rule`() {
        // adguard-popups shipped and could never work: every line in it is a $dnsrewrite rule,
        // which RuleParser declines to approximate, so it downloaded and parsed to nothing on
        // every refresh forever. The catalogue was measured by counting lines that look like
        // rules rather than lines the parser accepts.
        assertNull(BlocklistCatalog.byId("adguard-popups"), "a list that yields no usable rule")
    }

    @Test
    fun `every category has something in it`() {
        // The picker shows one row per category with an "N of M" on it. An empty one is a row
        // that reads "0 of 0" and opens onto nothing.
        for (category in BlocklistCategory.entries) {
            assertTrue(
                BlocklistCatalog.inCategory(category).isNotEmpty(),
                "$category has no lists",
            )
        }
    }

    @Test
    fun `the ids an existing install may already have stored all still exist`() {
        // Choices are persisted by id. Renaming one silently orphans somebody's decision — the
        // list reverts to its default and they are never told. These shipped; they are load-bearing.
        val shipped = listOf(
            "adguard-dns", "adaway", "easyprivacy", "yoyo", "oisd-small",
            "oisd-big", "hagezi-pro", "hagezi-tif", "stevenblack", "someonewhocares",
        )
        for (id in shipped) {
            assertNotNull(BlocklistCatalog.byId(id), "$id was in a shipped release and has gone")
        }
    }

    @Test
    fun `a category is ordered safest first`() {
        // The order is the recommendation. By size alone the most dangerous list in a category
        // is the one at the top, which is the opposite of the advice the screen means to give.
        for (category in BlocklistCategory.entries) {
            val risks = BlocklistCatalog.inCategory(category).map { it.risk.ordinal }
            assertEquals(risks.sorted(), risks, "$category is not ordered safest first")
        }
    }

    @Test
    fun `the tiers of a category partition it`() {
        for (category in BlocklistCategory.entries) {
            val whole = BlocklistCatalog.inCategory(category)
            val tiers = BreakageRisk.entries.flatMap { BlocklistCatalog.inCategory(category, it) }
            assertEquals(whole, tiers, "$category loses or repeats a list when split by risk")
        }
    }

    @Test
    fun `nothing aggressive is on by default`() {
        // A first run that breaks something is uninstalled, and the user never learns it was one
        // list rather than the whole app.
        assertTrue(BlocklistCatalog.sources.none { it.enabledByDefault && it.risk != BreakageRisk.SAFE })
    }

    @Test
    fun `every source declares a size`() {
        // It is the only thing the picker can say about a list nobody has downloaded yet, and
        // "about 0 domains" reads as broken.
        for (source in BlocklistCatalog.sources) {
            assertTrue(source.approximateEntries > 0, "${source.id} claims no entries")
        }
    }

    @Test
    fun `enabledCount counts only the category asked about`() {
        val allOn = BlocklistCatalog.sources.associate { it.id to true }
        for (category in BlocklistCategory.entries) {
            assertEquals(
                BlocklistCatalog.inCategory(category).size,
                BlocklistCatalog.enabledCount(category, allOn),
                "$category miscounts when everything is on",
            )
            assertEquals(0, BlocklistCatalog.enabledCount(category, BlocklistCatalog.sources.associate { it.id to false }))
        }
    }

    @Test
    fun `an untouched source follows its own default`() {
        assertTrue(BlocklistCatalog.isEnabled("adguard-dns", emptyMap()))
        assertFalse(BlocklistCatalog.isEnabled("oisd-big", emptyMap()))
        assertEquals(
            setOf("adguard-dns", "adaway"),
            BlocklistCatalog.enabled(emptyMap()).map { it.id }.toSet(),
        )
    }

    @Test
    fun `an explicit choice beats the default in both directions`() {
        // This is what lets a later release change a default without overriding a decision.
        assertFalse(BlocklistCatalog.isEnabled("adguard-dns", mapOf("adguard-dns" to false)))
        assertTrue(BlocklistCatalog.isEnabled("oisd-big", mapOf("oisd-big" to true)))
    }

    @Test
    fun `enabled preserves catalog order`() {
        // FilterEngine reports the first list that blocks a domain, so "first" has to mean
        // what the catalog says it means rather than whatever order a map iterated in.
        val enabled = BlocklistCatalog.enabled(BlocklistCatalog.sources.associate { it.id to true })
        assertEquals(BlocklistCatalog.sources.map { it.id }, enabled.map { it.id })
    }

    @Test
    fun `a choice naming a list that no longer exists is ignored`() {
        val enabled = BlocklistCatalog.enabled(mapOf("a-list-we-removed" to true))
        assertTrue(enabled.none { it.id == "a-list-we-removed" })
        assertNotNull(BlocklistCatalog.byId("adaway"))
    }

    @Test
    fun `recently enabled answers the newest first`() {
        // The support question this exists for: an app broke, which list did I add last.
        val choices = mapOf("oisd-big" to true, "hagezi-pro" to true, "nocoin" to true)
        val when_ = mapOf("oisd-big" to 100L, "hagezi-pro" to 300L, "nocoin" to 200L)
        assertEquals(
            listOf("hagezi-pro", "nocoin", "oisd-big"),
            BlocklistCatalog.recentlyEnabled(choices, when_).map { it.id },
        )
        assertEquals(listOf("hagezi-pro"), BlocklistCatalog.recentlyEnabled(choices, when_, limit = 1).map { it.id })
    }

    @Test
    fun `every list answers for its own risk, and a stranger answers nothing`() {
        // The screens that mark a verdict have only the list's title to go on — that is what
        // FilterEngine puts in Verdict.detail — so the lookup has to answer for every title the
        // catalogue can produce.
        for (source in BlocklistCatalog.sources) {
            assertEquals(source.risk, BlocklistCatalog.riskOfTitle(source.title), source.id)
        }
        // And decline to guess. A phone can still be holding a verdict naming a list a later
        // release dropped, and marking that one would be grading something nobody assessed.
        assertNull(BlocklistCatalog.riskOfTitle("A list we have never carried"))
        assertNull(BlocklistCatalog.riskOfTitle(""))
    }

    @Test
    fun `titles are unique, or one list would wear another's risk`() {
        // The lookup is a map keyed by title, so two lists sharing one would collapse into a
        // single entry and whichever lost would be marked with the other's risk — a safe list
        // showing three marks, or worse, an aggressive one showing one.
        val titles = BlocklistCatalog.sources.map { it.title }
        assertEquals(titles.size, titles.distinct().size, titles.groupBy { it }.filterValues { it.size > 1 }.keys.toString())
    }

    @Test
    fun `only lists that are actually on, and only ones with a recorded moment`() {
        // A date left behind by a list since switched off would offer to switch off something
        // already off; a list on by default was never switched on by anybody and has no date.
        assertTrue(
            BlocklistCatalog.recentlyEnabled(
                choices = mapOf("oisd-big" to false),
                enabledAtMs = mapOf("oisd-big" to 100L),
            ).isEmpty(),
        )
        assertTrue(BlocklistCatalog.recentlyEnabled(choices = emptyMap(), enabledAtMs = emptyMap()).isEmpty())
        // And an id from a release that has since dropped the list is not a row.
        assertTrue(
            BlocklistCatalog.recentlyEnabled(
                choices = mapOf("a-list-we-removed" to true),
                enabledAtMs = mapOf("a-list-we-removed" to 100L),
            ).isEmpty(),
        )
    }
}
