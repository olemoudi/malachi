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
        // Nothing in the "broader lists" bucket may be on out of the box: those are the ones
        // that break things, and a first run that breaks something is uninstalled.
        assertTrue(BlocklistCatalog.sources.none { it.enabledByDefault && it.category == BlocklistCategory.EXTRAS })
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
