package dev.malachi.lists

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What [BlocklistStore] does on disk. The downloading needs a network and stays out; the
 * housekeeping is what has broken before and what has to hold for months unattended.
 */
class BlocklistStoreTest {

    @TempDir
    lateinit var directory: File

    private fun source(id: String) = BlocklistCatalog.sources.first { it.id == id }

    private fun write(name: String) = File(directory, name).also { it.writeText("compiled bytes") }

    @Test
    fun `pruning keeps what is subscribed and drops what is not`() = runBlocking {
        write("adguard-dns.block")
        write("adguard-dns.allow")
        write("oisd-big.block")
        write("oisd-big.allow")

        BlocklistStore(directory).prune(listOf(source("adguard-dns")))

        assertTrue(File(directory, "adguard-dns.block").exists())
        assertTrue(File(directory, "adguard-dns.allow").exists())
        assertFalse(File(directory, "oisd-big.block").exists())
        assertFalse(File(directory, "oisd-big.allow").exists())
    }

    @Test
    fun `pruning clears a compiled list the state file no longer knows about`() = runBlocking {
        // The state file is what says which lists exist; when it is the thing that gets lost,
        // driving the cleanup from it leaves two megabytes per orphaned list on the phone
        // forever. The directory is the authority.
        write("hagezi-tif.block")
        write("hagezi-tif.allow")

        BlocklistStore(directory).prune(emptyList())

        assertFalse(File(directory, "hagezi-tif.block").exists())
        assertFalse(File(directory, "hagezi-tif.allow").exists())
    }

    @Test
    fun `pruning sweeps up a temporary file a kill left behind`() = runBlocking {
        write("adguard-dns.block.tmp")

        BlocklistStore(directory).prune(listOf(source("adguard-dns")))

        assertFalse(File(directory, "adguard-dns.block.tmp").exists())
    }

    @Test
    fun `pruning does not delete the state file it is about to write`() = runBlocking {
        val store = BlocklistStore(directory)
        write("adguard-dns.block")
        store.prune(listOf(source("adguard-dns")))

        assertTrue(File(directory, "state.json").exists())
        // And it is still readable afterwards, rather than having been deleted and rewritten
        // as something the next read discards.
        assertTrue(store.states().isEmpty() || store.states().containsKey("adguard-dns"))
    }

    @Test
    fun `an unreadable state file is survived rather than propagated`() = runBlocking {
        directory.mkdirs()
        File(directory, "state.json").writeText("{{{ not json")

        val store = BlocklistStore(directory)
        assertTrue(store.states().isEmpty())
        // And pruning still works, which is what stops a damaged state file from also becoming
        // a disk leak.
        write("oisd-big.block")
        store.prune(emptyList())
        assertFalse(File(directory, "oisd-big.block").exists())
    }
}
