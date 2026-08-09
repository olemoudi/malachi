package dev.malachi.filter

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
                .collect { reloadLists() }
        }

        scope.launch {
            combine(settingsStore.settings, compiledLists) { settings, lists -> build(settings, lists) }
                .collect { _engine.value = it }
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
            val changed = blocklistStore.refresh(sources, force)
            // Pruning takes the whole subscribed set, never the subset just fetched.
            blocklistStore.prune(sources)
            if (changed) reloadLists() else _listStates.value = blocklistStore.states()
        } finally {
            _refreshing.value = false
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
            if (blocklistStore.refresh(missing)) reloadLists() else _listStates.value = blocklistStore.states()
        } finally {
            _refreshing.value = false
        }
    }

    /**
     * Assembly is off the main thread: the user's rules are small, but building their indexes
     * still sorts an array, and this runs on every keystroke-sized settings change.
     */
    private suspend fun build(settings: MalachiSettings, lists: List<CompiledList>): FilterEngine =
        withContext(Dispatchers.Default) {
            FilterEngine(
                userBlock = DomainIndex.of(settings.userBlocked),
                userAllow = DomainIndex.of(settings.userAllowed),
                appRules = settings.appRules.map { it.toDomainRule() },
                lists = lists,
            )
        }

    private companion object {
        const val TAG = "MalachiFilter"
    }
}
