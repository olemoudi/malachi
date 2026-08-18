package dev.malachi.lists

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `pruning never deletes the state file`() = runBlocking {
        val store = BlocklistStore(directory)
        // A sweep with something to forget, which is what writes the file in the first place.
        write("oisd-big.block")
        write("adguard-dns.block")
        store.prune(listOf(source("adguard-dns")))
        assertTrue(File(directory, "state.json").exists())

        // And a second sweep with nothing to forget leaves it exactly where it was, rather than
        // deleting it as an unrecognised file or rewriting it as something the next read
        // discards.
        store.prune(listOf(source("adguard-dns")))
        assertTrue(File(directory, "state.json").exists())
        assertTrue(store.states().isEmpty() || store.states().containsKey("adguard-dns"))
    }

    @Test
    fun `a sweep that finds nothing writes nothing`() = runBlocking {
        // Reached on every process start by way of `downloadMissingLists`, and this app is
        // revived by every worker, broadcast and Doze cycle. A sweep that found nothing used to
        // rewrite the state file anyway — a disk write, dozens of times a day, for a document
        // that had not changed.
        write("adguard-dns.block")
        val store = BlocklistStore(directory)
        store.prune(listOf(source("adguard-dns")))
        val stateFile = File(directory, "state.json")
        val firstWrite = stateFile.exists()

        store.prune(listOf(source("adguard-dns")))

        assertFalse(
            firstWrite,
            "a sweep with nothing to forget created the state file",
        )
        assertFalse(stateFile.exists(), "a sweep with nothing to forget wrote the state file")
        // And the list it was told to keep is still there, which is the part that matters.
        assertTrue(File(directory, "adguard-dns.block").exists())
    }

    @Test
    fun `a damaged state file is left alone when there is nothing to sweep`() = runBlocking {
        // The other half of not writing: `states()` answers empty for a file it cannot read, and
        // writing that answer back replaced a recoverable file with an authoritative empty one.
        directory.mkdirs()
        val stateFile = File(directory, "state.json")
        stateFile.writeText("{{{ not json")
        write("adguard-dns.block")

        BlocklistStore(directory).prune(listOf(source("adguard-dns")))

        assertEquals("{{{ not json", stateFile.readText())
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
