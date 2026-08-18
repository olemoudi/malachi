package dev.malachi.filter

import dev.malachi.data.AppRule
import dev.malachi.data.MalachiSettings
import dev.malachi.data.SettingsStore
import dev.malachi.debug.DebugLog
import dev.malachi.lists.BlocklistCatalog
import dev.malachi.lists.BlocklistStore
import dev.malachi.lists.ListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many blocklists have been fetched out of how many, while a download is running. */
data class ListProgress(val done: Int, val total: Int)

/**
 * Everything in the settings that the filter is actually made of, and nothing else.
 *
 * Its own value because it decides when the engine is rebuilt, and getting the membership wrong
 * fails in the worst direction available: a field the engine reads but this does not name is a
 * rule the user writes, sees listed, and that the tunnel never consults. Internal so a test can
 * hold it to both halves of that — every rule field changes it, and nothing else does.
 */
internal data class EngineInputs(
    val userBlocked: Set<String>,
    val userAllowed: Set<String>,
    val appRules: List<AppRule>,
)

internal fun MalachiSettings.engineInputs() = EngineInputs(userBlocked, userAllowed, appRules)

/**
 * The live filter: the user's rules plus the compiled lists, assembled into one [FilterEngine]
 * and kept current as either side changes.
 *
 * The split matters for how the tunnel behaves. Rules and lists change on a human timescale;
 * lookups arrive dozens per second. So the assembly happens here, once per change, and the
 * tunnel only ever reads a finished, immutable engine — which is also why a rule edit takes
 * effect on the next lookup without the tunnel being torn down and re-established.
 */
class FilterRepository(
    private val settingsStore: SettingsStore,
    private val blocklistStore: BlocklistStore,
    private val scope: CoroutineScope,
) {

    private val compiledLists = MutableStateFlow<List<CompiledList>>(emptyList())

    private val _engine = MutableStateFlow(FilterEngine())
    val engine: StateFlow<FilterEngine> = _engine.asStateFlow()

    private val _listStates = MutableStateFlow<Map<String, ListState>>(emptyMap())
    val listStates: StateFlow<Map<String, ListState>> = _listStates.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * How far along a download is, or null when nothing is downloading.
     *
     * Exists for the first run. A fresh install fetches twenty megabytes before it can block
     * anything, and until this there was nothing on screen to say so: the phone was busy, the
     * counter said zero, and the only available conclusion was that the app did not work.
     */
    private val _listProgress = MutableStateFlow<ListProgress?>(null)
    val listProgress: StateFlow<ListProgress?> = _listProgress.asStateFlow()

    init {
        // Deliberately not read here and now: this runs inside Application.onCreate, and reading
        // a file on the main thread at process start is how an app earns a slow cold launch.
        scope.launch(Dispatchers.IO) { _listStates.value = blocklistStore.states() }

        // The set of subscribed lists changes far less often than the rules do, and reloading
        // one means reading megabytes off disk — so it is watched separately rather than
        // recompiling everything whenever a setting moves.
        scope.launch {
            settingsStore.settings
                .map { it.listChoices }
                .distinctUntilChanged()
                .collect {
                    runCatching { reloadLists() }
                        .onFailure { error -> DebugLog.w(TAG, "could not reload the lists", error) }
                }
        }

        // Guarded for the same reason as the tunnel's own collector, and with more at stake: an
        // exception reaching a collector ends it permanently, and this is the only thing that
        // rebuilds the engine. A throw here — a list index that read back as nonsense, a disk
        // that answered badly for one moment — and every rule the user writes from then on is
        // saved, displayed, and never consulted. Nothing about the app would look broken.
        //
        // Narrowed to the three fields the engine is made of, which is a battery fix and not
        // tidiness. The settings blob emits on *every* write, and most writes have nothing to do
        // with filtering: a pause, the diagnostics deadline being pushed back, a step of the
        // guided search, a dismissed tip, a backup reminder. Each of those used to sort the
        // user's rules into two fresh indexes and rebuild the engine — so tapping "pause" did
        // the work of a rule change, and a guided search did it nine times.
        scope.launch {
            combine(
                settingsStore.settings.map { it.engineInputs() }.distinctUntilChanged(),
                compiledLists,
            ) { inputs, lists -> inputs to lists }
                .collect { (inputs, lists) ->
                    runCatching { _engine.value = build(inputs, lists) }
                        .onFailure { DebugLog.e(TAG, "could not rebuild the filter", it) }
                }
        }
    }

    /** The verdict for one lookup. Called on the tunnel's hot path. */
    fun decide(host: String, packageName: String?): Verdict = _engine.value.decide(host, packageName)

    /** Reads the compiled indexes of the currently subscribed lists back into memory. */
    private suspend fun reloadLists() {
        val sources = BlocklistCatalog.enabled(settingsStore.current().listChoices)
        val loaded = blocklistStore.load(sources)
        DebugLog.i(TAG, "loaded ${loaded.size}/${sources.size} lists, ${loaded.sumOf { it.block.size }} domains")
        compiledLists.value = loaded
        _listStates.value = blocklistStore.states()
    }

    /**
     * Downloads the subscribed lists and, if anything actually changed, rebuilds the filter.
     * Safe to call from anywhere — the store serializes concurrent refreshes internally.
     */
    suspend fun refreshLists(force: Boolean = false) {
        _refreshing.value = true
        try {
            val sources = BlocklistCatalog.enabled(settingsStore.current().listChoices)
            val changed = blocklistStore.refresh(sources, force) { done, total ->
                _listProgress.value = ListProgress(done, total)
            }
            // Pruning takes the whole subscribed set, never the subset just fetched.
            blocklistStore.prune(sources)
            if (changed) reloadLists() else _listStates.value = blocklistStore.states()
        } finally {
            _refreshing.value = false
            _listProgress.value = null
        }
    }

    /**
     * Makes sure every subscribed list is present on disk, downloading the ones that aren't.
     *
     * This is what turns "the user just enabled OISD" into a filter that actually blocks
     * anything, and what recovers an install whose files were cleared. It never re-downloads a
     * list that is already compiled — that is the periodic refresh's job.
     */
    suspend fun downloadMissingLists() {
        val sources = BlocklistCatalog.enabled(settingsStore.current().listChoices)
        // Cheap, and the only thing that clears the files of a list that has been switched off.
        blocklistStore.prune(sources)
        val states = blocklistStore.states()
        val missing = sources.filter { states[it.id]?.isDownloaded != true }
        if (missing.isEmpty()) {
            _listStates.value = states
            return
        }
        DebugLog.i(TAG, "downloading ${missing.size} list(s) that aren't on disk yet")
        _refreshing.value = true
        try {
            val changed = blocklistStore.refresh(missing) { done, total ->
                _listProgress.value = ListProgress(done, total)
            }
            if (changed) reloadLists() else _listStates.value = blocklistStore.states()
        } finally {
            _refreshing.value = false
            _listProgress.value = null
        }
    }

    /**
     * Assembly is off the main thread: the user's rules are small, but building their indexes
     * still sorts an array, and this runs on every change to a rule.
     */
    private suspend fun build(inputs: EngineInputs, lists: List<CompiledList>): FilterEngine =
        withContext(Dispatchers.Default) {
            FilterEngine(
                userBlock = DomainIndex.of(inputs.userBlocked),
                userAllow = DomainIndex.of(inputs.userAllowed),
                appRules = inputs.appRules.map { it.toDomainRule() },
                lists = lists,
            )
        }

    private companion object {
        const val TAG = "MalachiFilter"
    }
}
