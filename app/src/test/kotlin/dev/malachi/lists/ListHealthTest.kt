package dev.malachi.lists

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Every list is on" is not "every list is working", and only the second one filters anything.
 * Reported from a phone whose owner had switched on every ad list — nothing on the home screen
 * could say whether they had actually arrived.
 */
class ListHealthTest {

    private val hour = 60 * 60 * 1000L
    private val now = 1_000 * hour

    // Two lists that are off by default, switched on an hour ago.
    private val choices = mapOf("hagezi-pro" to true, "oisd-big" to true)
    private val enabledAt = mapOf("hagezi-pro" to now - hour, "oisd-big" to now - hour)

    private fun ids(sources: List<BlocklistSource>) = sources.map { it.id }.toSet()

    @Test
    fun `lists that arrived are not trouble`() {
        val states = BlocklistCatalog.enabled(choices).associate { it.id to ListState(it.id, entries = 10, fetchedAtMs = now) }
        assertTrue(ListHealth.check(choices, enabledAt, states, now).isEmpty)
    }

    @Test
    fun `a list switched on long ago that never arrived blocks nothing and is said so`() {
        val trouble = ListHealth.check(choices, enabledAt, emptyMap(), now)
        assertTrue(ids(trouble.missing).containsAll(setOf("hagezi-pro", "oisd-big")))
    }

    @Test
    fun `a list switched on a moment ago is given time to arrive`() {
        val justNow = mapOf("hagezi-pro" to now - 60_000, "oisd-big" to now - 60_000)
        assertTrue(ListHealth.check(choices, justNow, emptyMap(), now).missing.none { it.id in choices })
    }

    @Test
    fun `a failed first download is news at once, even for a list that ships on`() {
        // The defaults have no recorded moment, so only a failure can flag them.
        val states = mapOf("adguard-dns" to ListState("adguard-dns", lastError = "HTTP 404"))
        val trouble = ListHealth.check(emptyMap(), emptyMap(), states, now)
        assertEquals(setOf("adguard-dns"), ids(trouble.missing))
    }

    @Test
    fun `a default list nobody has tried to fetch yet is not trouble`() {
        assertTrue(ListHealth.check(emptyMap(), emptyMap(), emptyMap(), now).isEmpty)
    }

    @Test
    fun `a list that keeps failing is stale once the failure has lasted days`() {
        val failing = ListState("hagezi-pro", entries = 10, fetchedAtMs = now - 4 * 24 * hour, lastError = "timeout")
        val recent = ListState("oisd-big", entries = 10, fetchedAtMs = now - 2 * hour, lastError = "timeout")
        val trouble = ListHealth.check(choices, enabledAt, mapOf("hagezi-pro" to failing, "oisd-big" to recent), now)
        assertEquals(setOf("hagezi-pro"), ids(trouble.stale))
        assertTrue(trouble.missing.none { it.id in choices })
    }

    @Test
    fun `a list that is switched off is nobody's trouble`() {
        val off = mapOf("hagezi-pro" to false)
        val trouble = ListHealth.check(off, mapOf("hagezi-pro" to now - 10 * hour), emptyMap(), now)
        assertTrue(trouble.missing.none { it.id == "hagezi-pro" })
    }
}
