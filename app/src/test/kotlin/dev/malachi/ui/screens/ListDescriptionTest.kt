package dev.malachi.ui.screens

import dev.malachi.R
import dev.malachi.lists.BlocklistCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Every list in the catalogue has to say what it is.
 *
 * The lookup falls back to "a subscribed list" for an id it doesn't know, which is not an error
 * anywhere — it compiles, it renders, and it tells the reader nothing. Adding a list and
 * forgetting its description is therefore invisible until somebody opens the picker and finds a
 * row that describes itself as nothing in particular. The catalogue grew from ten entries to
 * fifty-four in one change, which is exactly the size of edit where one gets missed.
 */
class ListDescriptionTest {

    @Test
    fun `every catalogued list has its own description`() {
        val undescribed = BlocklistCatalog.sources
            .map { it.id }
            .filter { listDescription(it) == R.string.list_unknown }
        assertEquals(emptyList<String>(), undescribed, "these lists have no description of their own")
    }

    @Test
    fun `no two lists share a description`() {
        // A copy-paste in the lookup points two lists at one explanation, and the picker then
        // describes a malware feed as a regional ad list. Cheap to check, invisible otherwise.
        val byResource = BlocklistCatalog.sources.groupBy { listDescription(it.id) }
        val shared = byResource.filterValues { it.size > 1 }.map { (_, sources) -> sources.map { it.id } }
        assertEquals(emptyList<List<String>>(), shared, "these lists share one description")
    }

    @Test
    fun `an id that is not in the catalogue falls back rather than throwing`() {
        assertEquals(R.string.list_unknown, listDescription("a-list-from-the-future"))
        assertNotEquals(R.string.list_unknown, listDescription("adguard-dns"))
    }

    @Test
    fun `a source line keeps the part that identifies the list`() {
        assertEquals(
            "adguardteam.github.io/…/filter_44.txt",
            shortSource("https://adguardteam.github.io/HostlistsRegistry/assets/filter_44.txt"),
        )
        assertEquals("adaway.org/hosts.txt", shortSource("https://adaway.org/hosts.txt"))
        assertEquals("nsfw.oisd.nl", shortSource("https://nsfw.oisd.nl/"))
        assertEquals("easylist.to/…/easyprivacy.txt", shortSource("https://easylist.to/easylist/easyprivacy.txt"))
    }

    @Test
    fun `two lists from the same host are still told apart by their source line`() {
        // The whole reason this is shortened from the middle: forty of these share a host, and
        // an ordinary ellipsis renders every one of them as the same string.
        val lines = BlocklistCatalog.sources.map { shortSource(it.url) }
        assertEquals(lines.size, lines.distinct().size, "two lists show an identical source line")
    }
}
