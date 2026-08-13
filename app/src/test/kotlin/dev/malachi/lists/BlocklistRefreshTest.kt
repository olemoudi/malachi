package dev.malachi.lists

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The download half of [BlocklistStore], against a real HTTP server.
 *
 * A server rather than a mocked client, because the behaviour worth pinning down is on the
 * wire: that a refresh sends the validators it stored and a 304 costs one round trip instead of
 * twenty megabytes, and that a "successful" response which parses to nothing does not replace a
 * working filter with an empty one. Both are properties of the exchange, not of our code alone.
 */
class BlocklistRefreshTest {

    @TempDir
    lateinit var directory: File

    private lateinit var server: MockWebServer

    /** What each path serves next, and what it was asked for last time. */
    private val bodies = ConcurrentHashMap<String, String>()
    private val etags = ConcurrentHashMap<String, String>()
    private val status = ConcurrentHashMap<String, Int>()
    private val requests = ConcurrentHashMap<String, MutableList<RecordedRequest>>()

    /** Paths whose body is served slowly, so a concurrent caller is genuinely concurrent. */
    private val slowPaths = ConcurrentHashMap<String, Long>()

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = serve(request)
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun serve(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        requests.getOrPut(path) { mutableListOf() }.add(request)

        val code = status[path] ?: 200
        if (code != 200) return MockResponse().setResponseCode(code)
        slowPaths[path]?.let { millis ->
            return MockResponse().setBody(bodies[path].orEmpty())
                .setBodyDelay(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        val etag = etags[path] ?: return MockResponse().setBody(bodies[path].orEmpty())
        if (request.getHeader("If-None-Match") == etag) {
            return MockResponse().setResponseCode(304).addHeader("ETag", etag)
        }
        return MockResponse().setBody(bodies[path].orEmpty()).addHeader("ETag", etag)
    }

    private fun url(path: String) = server.url(path).toString()

    private fun source(id: String, path: String) = BlocklistSource(
        id = id,
        title = id,
        maintainer = "test",
        url = url(path),
        homepage = "https://example.com",
        category = BlocklistCategory.ADS,
        risk = BreakageRisk.SAFE,
    )

    private fun hosts(count: Int, prefix: String = "ads") =
        (0 until count).joinToString("\n") { "0.0.0.0 $prefix$it.example.com" }

    private fun requestsTo(path: String) = requests[path].orEmpty()

    // -------------------------------------------------------------------------------------

    @Test
    fun `a downloaded list is compiled into something that actually matches`() = runBlocking {
        bodies["/ads.txt"] = hosts(150)
        val store = BlocklistStore(directory)
        val list = source("ads", "/ads.txt")

        assertTrue(store.refresh(listOf(list)))

        assertEquals(150, store.states()["ads"]?.entries)
        assertEquals("", store.states()["ads"]?.lastError)
        val compiled = store.load(listOf(list)).single()
        assertTrue(compiled.block.matches("ads7.example.com"))
        assertTrue(compiled.block.matches("sub.ads7.example.com"))
        assertFalse(compiled.block.matches("example.com"))
    }

    @Test
    fun `an unchanged list is asked about conditionally and not recompiled`() = runBlocking {
        bodies["/ads.txt"] = hosts(150)
        etags["/ads.txt"] = "\"v1\""
        val store = BlocklistStore(directory)
        val list = listOf(source("ads", "/ads.txt"))

        assertTrue(store.refresh(list))
        val compiledAt = File(directory, "ads.block").lastModified()

        // Second time round: the stored validator goes back out and the server says 304.
        assertFalse(store.refresh(list), "a 304 was treated as a change")

        assertEquals("\"v1\"", requestsTo("/ads.txt").last().getHeader("If-None-Match"))
        assertEquals(compiledAt, File(directory, "ads.block").lastModified())
        assertEquals(150, store.states()["ads"]?.entries)
    }

    @Test
    fun `a forced refresh ignores the validators it stored`() = runBlocking {
        bodies["/ads.txt"] = hosts(150)
        etags["/ads.txt"] = "\"v1\""
        val store = BlocklistStore(directory)
        val list = listOf(source("ads", "/ads.txt"))
        store.refresh(list)

        bodies["/ads.txt"] = hosts(200)
        etags["/ads.txt"] = "\"v2\""
        store.refresh(list, force = true)

        assertEquals(null, requestsTo("/ads.txt").last().getHeader("If-None-Match"))
        assertEquals(200, store.states()["ads"]?.entries)
    }

    @Test
    fun `a list that suddenly parses to nothing keeps the previous copy`() = runBlocking {
        // A captive portal, an error page, a maintainer mid-migration. Replacing a working
        // filter with an empty one and reporting success is the worst available outcome.
        bodies["/ads.txt"] = hosts(150)
        val store = BlocklistStore(directory)
        val list = listOf(source("ads", "/ads.txt"))
        store.refresh(list)

        bodies["/ads.txt"] = "<html><body>Sign in to continue</body></html>"
        assertFalse(store.refresh(list))

        assertEquals(150, store.states()["ads"]?.entries)
        assertTrue(store.states()["ads"]?.lastError?.isNotEmpty() == true)
        assertTrue(store.load(list).single().block.matches("ads7.example.com"))
    }

    @Test
    fun `one list failing does not stop the others`() = runBlocking {
        bodies["/good.txt"] = hosts(150, prefix = "good")
        status["/bad.txt"] = 500
        val store = BlocklistStore(directory)
        val lists = listOf(source("good", "/good.txt"), source("bad", "/bad.txt"))

        assertTrue(store.refresh(lists))

        assertEquals(150, store.states()["good"]?.entries)
        assertTrue(store.states()["bad"]?.lastError?.contains("500") == true)
        assertTrue(store.load(lists).single().block.matches("good1.example.com"))
    }

    @Test
    fun `exceptions in a list are compiled separately and win over its blocks`() = runBlocking {
        bodies["/ads.txt"] = hosts(150) + "\n@@||ads7.example.com^\n"
        val store = BlocklistStore(directory)
        val list = listOf(source("ads", "/ads.txt"))

        store.refresh(list)

        assertEquals(1, store.states()["ads"]?.exceptions)
        val compiled = store.load(list).single()
        assertTrue(compiled.allow.matches("ads7.example.com"))
    }

    @Test
    fun `a refresh records what it could not read rather than guessing`() = runBlocking {
        // Adblock syntax scoped to a site is meaningless at the DNS layer and is skipped; the
        // count is what makes the skipping visible instead of silent over-blocking.
        bodies["/ads.txt"] = hosts(150) + "\n||tracker.example.com^\$domain=news.example\n"
        val store = BlocklistStore(directory)

        store.refresh(listOf(source("ads", "/ads.txt")))

        assertEquals(1, store.states()["ads"]?.skipped)
        assertEquals(150, store.states()["ads"]?.entries)
    }

    @Test
    fun `a list switched off is downloaded, then pruned off the disk`() = runBlocking {
        bodies["/ads.txt"] = hosts(150)
        val store = BlocklistStore(directory)
        val list = source("ads", "/ads.txt")
        store.refresh(listOf(list))
        assertTrue(File(directory, "ads.block").exists())

        store.prune(emptyList())

        assertFalse(File(directory, "ads.block").exists())
        assertFalse(File(directory, "ads.allow").exists())
        assertTrue(store.states().isEmpty())
    }

    @Test
    fun `pruning one list does not disturb another that is still subscribed`() = runBlocking {
        // The bug this is here for: pruning used to take whichever subset was being fetched, so
        // downloading one newly enabled list deleted every index already on disk.
        bodies["/a.txt"] = hosts(150, prefix = "a")
        bodies["/b.txt"] = hosts(150, prefix = "b")
        val store = BlocklistStore(directory)
        val a = source("a", "/a.txt")
        val b = source("b", "/b.txt")
        store.refresh(listOf(a, b))

        store.prune(listOf(a, b))

        assertTrue(File(directory, "a.block").exists())
        assertTrue(File(directory, "b.block").exists())
        assertEquals(2, store.load(listOf(a, b)).size)
    }

    @Test
    fun `a prune waits for a refresh already in flight`() = runBlocking {
        // These two run on their own schedules and do overlap: the periodic refresh prunes after
        // fetching, enabling a list prunes before. Unserialized, the sweep deletes the `.tmp`
        // that writeIndex is mid-rename on, and the list it was writing ends up absent while its
        // state says it downloaded — a filter that reads as on and blocks nothing.
        bodies["/slow.txt"] = hosts(150, prefix = "slow")
        slowPaths["/slow.txt"] = 1_000
        val store = BlocklistStore(directory)
        val slow = source("slow", "/slow.txt")

        var pruneTookMs = 0L
        coroutineScope {
            val refresh = launch(Dispatchers.IO) { store.refresh(listOf(slow)) }
            // Long enough that the request is on the wire and its body is still being held.
            delay(150)
            launch(Dispatchers.IO) {
                // `nanoTime`, deliberately: `measureTimeMillis` is a wall clock, and a wall clock
                // steps. This test failed with a duration of *minus* 1599ms on a machine that
                // corrected its time by NTP while the prune was blocked.
                val startedAt = System.nanoTime()
                store.prune(listOf(slow))
                pruneTookMs = (System.nanoTime() - startedAt) / 1_000_000
            }
            refresh.join()
        }

        // How long the prune itself took is the only honest measure of whether it waited:
        // sweeping a directory with two files in it is a millisecond's work, so anything near
        // the refresh's remaining second is the lock and nothing else. A slower machine makes
        // the refresh longer, which makes this margin wider rather than narrower.
        //
        // It used to compare the instants at which the two coroutines *finished*, each recorded
        // after its call returned — which is a race of its own. The prune is resumed the moment
        // the refresh releases the lock, so on a loaded machine it can record its timestamp
        // before the refresh coroutine is scheduled for its next line. Green for weeks here, red
        // on CI, and it took a release down with it.
        assertTrue(
            pruneTookMs > 400,
            "the prune took ${pruneTookMs}ms, so it did not wait for the refresh",
        )
        // And the point of all that: the list the refresh was fetching survived it.
        assertTrue(File(directory, "slow.block").exists())
        assertEquals(1, store.load(listOf(slow)).size)
        assertTrue(store.states()["slow"]?.isDownloaded == true)
    }
}
