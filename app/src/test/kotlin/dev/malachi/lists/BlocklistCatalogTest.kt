package dev.malachi.lists

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
}
