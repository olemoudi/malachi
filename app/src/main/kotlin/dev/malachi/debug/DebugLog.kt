package dev.malachi.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** One line in the in-app debug log. Pure (no Android deps), so the buffer logic is unit-testable. */
@Serializable
data class LogEntry(val epochMillis: Long, val level: Char, val tag: String, val message: String)

/**
 * Pure buffer/format/serialization helpers, kept free of Android so they can be unit-tested.
 * [DebugLog] wires these to a StateFlow, Logcat and a capped file. The on-disk format is JSON
 * Lines (one entry per physical line), so a multi-line message — a stack trace — can't corrupt
 * parsing of the entries around it.
 */
internal object LogFormat {
    private val json = Json { ignoreUnknownKeys = true }
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    /** Appends [entry], trimming from the front so the list never exceeds [max]. */
    fun cap(entries: List<LogEntry>, entry: LogEntry, max: Int): List<LogEntry> {
        val next = entries + entry
        return if (next.size <= max) next else next.subList(next.size - max, next.size)
    }

    fun line(e: LogEntry): String =
        "${TIME.format(Instant.ofEpochMilli(e.epochMillis))} ${e.level}/${e.tag}: ${e.message}"

    fun format(entries: List<LogEntry>): String = entries.joinToString("\n") { line(it) }

    fun serialize(e: LogEntry): String = json.encodeToString(LogEntry.serializer(), e)

    fun deserialize(line: String): LogEntry? =
        runCatching { json.decodeFromString(LogEntry.serializer(), line) }.getOrNull()
}

/**
 * Process-wide, in-app debug log: the tunnel, the list downloader and the updater write here,
 * and the debug screen reads it. Mirrored to Logcat and persisted to a small capped file, so a
 * trace survives the process restart that a successful self-update triggers — which is exactly
 * the moment you most want to know what happened.
 *
 * This is *not* the query log ([dev.malachi.filter.QueryLog]): that one records which app asked
 * for which domain and never touches disk.
 */
object DebugLog {
    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 128 * 1024
    private const val TRIM_TO_BYTES = 64 * 1024

    /**
     * A single entry's ceiling. One pathological stack trace could otherwise be most of the log,
     * pushing out every line of context that made it explicable.
     */
    private const val MAX_ENTRY_CHARS = 4_000
    private const val FILE_NAME = "debug-log.txt"

    private val mutable = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = mutable

    // Serializes all file I/O off the main thread; the UI only ever reads the in-memory flow.
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "malachi-debuglog").apply { isDaemon = true }
    }

    @Volatile private var file: File? = null

    /** Loads any persisted tail into memory. Call once from [android.app.Application.onCreate]. */
    fun init(context: Context) = init(File(context.filesDir, FILE_NAME))

    /** The same, given the file directly, so the capping can be exercised without a device. */
    internal fun init(target: File) {
        file = target
        io.execute {
            val loaded = runCatching { readTail(target) }.getOrDefault(emptyList())
            if (loaded.isNotEmpty() && mutable.value.isEmpty()) mutable.value = loaded
        }
    }

    /**
     * Waits for the queued appends to reach the disk. For the tests: every write here is
     * deliberately asynchronous, so asserting on the file without this is asserting on a race.
     */
    internal fun awaitIdle(timeoutMs: Long = 5_000) {
        val done = java.util.concurrent.CountDownLatch(1)
        io.execute { done.countDown() }
        done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun i(tag: String, message: String) = add('I', tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = add('W', tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = add('E', tag, message, t)

    private fun add(level: Char, tag: String, message: String, t: Throwable?) {
        when (level) {
            'E' -> Log.e(tag, message, t)
            'W' -> Log.w(tag, message, t)
            else -> Log.i(tag, message)
        }
        val body = (if (t != null) "$message\n${Log.getStackTraceString(t)}" else message)
            .let { if (it.length > MAX_ENTRY_CHARS) it.take(MAX_ENTRY_CHARS) + "… (truncated)" else it }
        val entry = LogEntry(System.currentTimeMillis(), level, tag, body)
        // update, not an assignment: the tunnel, the forwarders and the workers all log, and a
        // read-modify-write of the whole list from several threads drops entries — usually the
        // ones written during whatever was going wrong at the time.
        mutable.update { LogFormat.cap(it, entry, MAX_ENTRIES) }
        val f = file ?: return
        io.execute { runCatching { appendCapped(f, entry) } }
    }

    /** The whole buffer as text, for copy/share. */
    fun format(): String = LogFormat.format(mutable.value)

    fun clear() {
        mutable.value = emptyList()
        val f = file ?: return
        io.execute { runCatching { f.writeText("") } }
    }

    /**
     * Appends, then trims if the file has outgrown its cap.
     *
     * Trimming targets [TRIM_TO_BYTES] rather than the cap itself, which matters more than it
     * looks: trimming back to exactly the limit means the very next line exceeds it again, and
     * the file is rewritten in full on every subsequent append for the rest of the install.
     * Dropping to half leaves headroom for thousands of lines between rewrites.
     *
     * The line count alone was not a cap at all — five hundred stack traces are megabytes — so
     * the bytes are what decide, and the count is only a ceiling on how much is kept.
     */
    private fun appendCapped(f: File, entry: LogEntry) {
        f.appendText(LogFormat.serialize(entry) + "\n")
        if (f.length() <= MAX_FILE_BYTES) return
        val kept = ArrayDeque(f.readLines().takeLast(MAX_ENTRIES))
        var bytes = kept.sumOf { it.length + 1L }
        while (kept.size > 1 && bytes > TRIM_TO_BYTES) {
            bytes -= kept.removeFirst().length + 1L
        }
        f.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    private fun readTail(f: File): List<LogEntry> {
        if (!f.exists()) return emptyList()
        return f.readLines().takeLast(MAX_ENTRIES).mapNotNull { LogFormat.deserialize(it) }
    }
}
