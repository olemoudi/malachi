package dev.malachi.lists

import android.content.Context
import dev.malachi.debug.DebugLog
import dev.malachi.filter.CompiledList
import dev.malachi.filter.DomainIndex
import dev.malachi.filter.Rule
import dev.malachi.filter.RuleParser
import dev.malachi.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/** What is known about one downloaded list. Persisted beside the compiled indexes. */
@Serializable
data class ListState(
    val id: String,
    val entries: Int = 0,
    val exceptions: Int = 0,
    /** Lines the parser declined to guess at; see [RuleParser]. Shown so skipping is visible. */
    val skipped: Int = 0,
    val fetchedAtMs: Long = 0,
    val etag: String = "",
    val lastModified: String = "",
    /** Empty when the last refresh succeeded. */
    val lastError: String = "",
) {
    val isDownloaded: Boolean get() = entries > 0
}

/**
 * Downloads the subscribed lists, compiles them, and keeps the compiled form on disk.
 *
 * The download is never held in memory as a whole. A list is a quarter of a million lines of
 * text — twenty megabytes on the wire — and the compiled result is a `LongArray` a fraction of
 * that size ([DomainIndex]), so the response is consumed a line at a time straight into the
 * builder. Nothing between the socket and the index ever holds the full text.
 *
 * Refreshes are conditional: the ETag and Last-Modified of the previous fetch are sent back, and
 * a 304 costs one round trip instead of twenty megabytes of a stranger's bandwidth and the
 * user's data plan. Most of these lists rebuild hourly and change by a handful of lines.
 */
class BlocklistStore(private val context: Context) {

    private val dir = File(context.filesDir, "lists")
    private val stateFile = File(dir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val refreshLock = Mutex()

    // Longer read timeout than the shared client: some of these lists are slow to generate.
    private val client = Http.client.newBuilder()
        .callTimeout(4, java.util.concurrent.TimeUnit.MINUTES)
        .build()

    /** What is on disk right now, by source id. */
    fun states(): Map<String, ListState> = runCatching {
        if (!stateFile.exists()) return@runCatching emptyMap()
        json.decodeFromString(StatesEnvelope.serializer(), stateFile.readText()).states.associateBy { it.id }
    }.getOrElse {
        DebugLog.w(TAG, "unreadable list state; starting over", it)
        emptyMap()
    }

    fun stateOf(id: String): ListState = states()[id] ?: ListState(id)

    /**
     * Reads the compiled indexes for [sources] back into memory, skipping any that haven't been
     * downloaded yet. Order is preserved: [dev.malachi.filter.FilterEngine] reports the first
     * list that blocks a domain, and "first" should mean what the catalog says it means.
     */
    suspend fun load(sources: List<BlocklistSource>): List<CompiledList> = withContext(Dispatchers.IO) {
        sources.mapNotNull { source ->
            val block = readIndex(blockFile(source.id)) ?: return@mapNotNull null
            CompiledList(
                id = source.id,
                title = source.title,
                block = block,
                allow = readIndex(allowFile(source.id)) ?: DomainIndex.EMPTY,
            )
        }
    }

    /**
     * Fetches and recompiles every enabled source. Returns true when anything on disk changed,
     * so the caller only rebuilds the filter when there is something new to rebuild it from.
     *
     * One list failing never fails the refresh: the others still update, and the failure is
     * recorded against that source so the UI can show it next to the list it belongs to.
     */
    suspend fun refresh(sources: List<BlocklistSource>, force: Boolean = false): Boolean =
        refreshLock.withLock {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val previous = states().toMutableMap()
                var changed = false
                for (source in sources) {
                    val before = previous[source.id] ?: ListState(source.id)
                    val after = runCatching { refreshOne(source, before, force) }
                        .getOrElse { t ->
                            DebugLog.w(TAG, "refresh of ${source.id} failed", t)
                            before.copy(lastError = t.message ?: t.javaClass.simpleName)
                        }
                    if (after != before) {
                        previous[source.id] = after
                        // A 304 only moves the timestamp; that isn't a reason to recompile.
                        if (after.entries != before.entries || after.exceptions != before.exceptions) changed = true
                    }
                }
                writeStates(previous.values.toList())
                changed
            }
        }

    /**
     * Deletes the compiled form of anything not in [keep], plus any temporary file a kill left
     * behind. A 2 MB index per list adds up, and a stale one is exactly the sort of thing that
     * comes back to life later and blocks something nobody can explain.
     *
     * Deliberately separate from [refresh], and taking the *whole* subscribed set rather than
     * whichever subset is being fetched. It used to be folded into refresh, which meant that
     * downloading one newly enabled list deleted the compiled indexes of every list already on
     * disk — they were then silently re-downloaded on the next periodic refresh, so the only
     * visible symptom was a filter that went briefly empty and a lot of wasted traffic.
     */
    suspend fun prune(keep: List<BlocklistSource>) = withContext(Dispatchers.IO) {
        val wanted = keep.map { it.id }.toSet()
        val states = states().toMutableMap()
        states.keys.filterNot { it in wanted }.forEach { id ->
            blockFile(id).delete()
            allowFile(id).delete()
            states.remove(id)
        }
        runCatching {
            dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        }
        writeStates(states.values.toList())
    }

    private fun refreshOne(source: BlocklistSource, previous: ListState, force: Boolean): ListState {
        val compiled = blockFile(source.id).exists()
        val request = Request.Builder().url(source.url).apply {
            // A stored validator is only usable while the file it describes is still there.
            if (compiled && !force) {
                if (previous.etag.isNotEmpty()) header("If-None-Match", previous.etag)
                if (previous.lastModified.isNotEmpty()) header("If-Modified-Since", previous.lastModified)
            }
        }.build()

        client.newCall(request).execute().use { response ->
            if (response.code == 304) {
                DebugLog.i(TAG, "${source.id}: unchanged")
                return previous.copy(fetchedAtMs = System.currentTimeMillis(), lastError = "")
            }
            require(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: throw IllegalStateException("empty body")

            val blockBuilder = DomainIndex.Builder()
            val allowBuilder = DomainIndex.Builder()
            var blocked = 0
            var allowed = 0
            var skipped = 0
            body.charStream().buffered().forEachLine { line ->
                val rules = RuleParser.parseLine(line)
                if (rules.isEmpty()) {
                    if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("!")) skipped++
                    return@forEachLine
                }
                for (rule in rules) {
                    when (rule) {
                        is Rule.Block -> if (blockBuilder.add(rule.domain)) blocked++
                        is Rule.Allow -> if (allowBuilder.add(rule.domain)) allowed++
                    }
                }
            }

            // A list that suddenly parses to almost nothing is not a list: it is a captive
            // portal, an error page, or a maintainer mid-migration. Keeping the previous
            // compiled copy is strictly better than replacing a working filter with an empty
            // one and reporting success.
            if (blocked < MINIMUM_CREDIBLE_ENTRIES && compiled) {
                throw IllegalStateException("only $blocked usable entries; keeping the previous copy")
            }

            writeIndex(blockFile(source.id), blockBuilder.build())
            writeIndex(allowFile(source.id), allowBuilder.build())
            DebugLog.i(TAG, "${source.id}: $blocked entries, $allowed exceptions, $skipped lines skipped")
            return ListState(
                id = source.id,
                entries = blocked,
                exceptions = allowed,
                skipped = skipped,
                fetchedAtMs = System.currentTimeMillis(),
                etag = response.header("ETag").orEmpty(),
                lastModified = response.header("Last-Modified").orEmpty(),
                lastError = "",
            )
        }
    }

    /** Written to a sibling first and renamed, so a kill mid-write can't leave a torn index. */
    private fun writeIndex(target: File, index: DomainIndex) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.outputStream().use { index.write(it) }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun readIndex(file: File): DomainIndex? {
        if (!file.exists()) return null
        return runCatching { file.inputStream().use { DomainIndex.read(it) } }
            .onFailure {
                DebugLog.w(TAG, "discarding a corrupt index: ${file.name}", it)
                file.delete()
            }
            .getOrNull()
    }

    private fun writeStates(states: List<ListState>) {
        runCatching {
            stateFile.writeText(json.encodeToString(StatesEnvelope.serializer(), StatesEnvelope(states)))
        }.onFailure { DebugLog.w(TAG, "could not record list state", it) }
    }

    private fun blockFile(id: String) = File(dir, "$id.block")
    private fun allowFile(id: String) = File(dir, "$id.allow")

    /** A wrapper so the file is a JSON object and can gain fields without a format change. */
    @Serializable
    private data class StatesEnvelope(val states: List<ListState> = emptyList())

    private companion object {
        const val TAG = "MalachiLists"

        /** Below this, a "successful" download is treated as a failure. See the call site. */
        const val MINIMUM_CREDIBLE_ENTRIES = 100
    }
}
