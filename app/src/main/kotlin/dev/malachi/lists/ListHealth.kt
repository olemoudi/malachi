package dev.malachi.lists

/**
 * Subscribed lists that are not doing what their switch says.
 *
 * [missing] are switched on and have never arrived — they block nothing at all. [stale] arrived
 * once and have been failing to refresh for [STALE_MS]; they still block what they had, but that
 * is drifting out of date and the error is only visible on a screen few people open. Both used to
 * be reachable only by opening each category in turn, which is how "all the lists are on" came to
 * mean something different from "all the lists are working" on a phone nobody could look at.
 */
data class ListTrouble(
    val missing: List<BlocklistSource> = emptyList(),
    val stale: List<BlocklistSource> = emptyList(),
) {
    val isEmpty: Boolean get() = missing.isEmpty() && stale.isEmpty()
}

object ListHealth {

    /**
     * How long a freshly enabled list may take to arrive before its absence is news. Long enough
     * for a slow download on a first run, short enough that somebody checking the next day is told.
     */
    const val GRACE_MS = 15 * 60 * 1000L

    /** How long a list may go on failing before the failure is said out loud. */
    const val STALE_MS = 3 * 24 * 60 * 60 * 1000L

    fun check(
        choices: Map<String, Boolean>,
        enabledAtMs: Map<String, Long>,
        states: Map<String, ListState>,
        nowMs: Long,
    ): ListTrouble {
        val enabled = BlocklistCatalog.enabled(choices)
        val missing = enabled.filter { source ->
            val state = states[source.id]
            if (state?.isDownloaded == true) return@filter false
            // A failure is news at once; a list that has merely not been tried yet is given a
            // moment. The two that ship on have no recorded moment, so for them only a failure
            // counts — the first run tries them straight away and records whatever happens.
            state?.lastError?.isNotEmpty() == true ||
                enabledAtMs[source.id]?.let { nowMs - it > GRACE_MS } == true
        }
        val stale = enabled.filter { source ->
            val state = states[source.id] ?: return@filter false
            state.isDownloaded && state.lastError.isNotEmpty() && nowMs - state.fetchedAtMs > STALE_MS
        }
        return ListTrouble(missing, stale)
    }
}
