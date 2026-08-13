package dev.malachi.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A backup file: everything the user decided, and nothing they merely did.
 *
 * **What is in it and what is not.** The rules somebody wrote, the lists they chose, the apps
 * they excluded, where lookups go. Not the query log, not the statistics, not a single domain
 * this phone was seen asking for — those are observations rather than decisions, they are why
 * the query log lives in memory and the statistics count without naming, and a file the user
 * hands to a cloud drive is the last place they should surface. A rule someone typed is theirs
 * and is already on this disk; a name their phone looked up is not the same thing.
 *
 * **Reading a file from another version has to work, in both directions.** A backup taken today
 * must restore into next year's app, and one taken by next year's app must not make today's
 * refuse outright. That is bought with three habits and no cleverness: every field has a default
 * so an older file is missing nothing, unknown keys are ignored so a newer file is merely
 * partly understood, and **a field is never renamed or repurposed** — new meaning, new field.
 * [FORMAT] moves only when something changes that those three cannot absorb.
 */
@Serializable
data class Backup(
    /** Bumped only for a change the defaults-and-ignore-unknowns rule cannot absorb. */
    val format: Int = FORMAT,
    val exportedAtMs: Long = 0,
    /** For a human reading the file, and for a report that says "restored from what?". */
    val appVersion: String = "",

    val userBlocked: Set<String> = emptySet(),
    val userAllowed: Set<String> = emptySet(),
    val appRules: List<AppRule> = emptyList(),
    val listChoices: Map<String, Boolean> = emptyMap(),

    val scopeMode: AppScopeMode = AppScopeMode.ALL_EXCEPT,
    val excludedApps: Set<String> = emptySet(),
    val includedApps: Set<String> = emptySet(),

    val blockAnswer: BlockAnswerMode = BlockAnswerMode.NULL_ADDRESS,
    val upstream: UpstreamDns = UpstreamDns.SYSTEM,
    val customUpstream: String = "",
    val bypassGuard: BypassGuard = BypassGuard.SYSTEM_RESOLVERS,
    val bypassAllowed: Boolean = true,

    val listUpdateHours: Int = 24,
    val listUpdateWifiOnly: Boolean = true,
    val queryLogEnabled: Boolean = true,
    val updateWifiOnly: Boolean = false,
) {

    /**
     * The restored settings.
     *
     * Only what the file describes is replaced. Whether the filter is running, whether it is
     * paused, whether a diagnostics window is open, which notices have been dismissed on *this*
     * phone — none of that belongs to a backup, and restoring a paused state onto a working phone
     * would be a filter that stops for fifteen minutes with no explanation.
     *
     * The restored decisions are also the ones that were saved, so this counts as a backup taken:
     * asking somebody to export the file they have just imported is nonsense.
     */
    fun restoredInto(settings: MalachiSettings): MalachiSettings = BackupPolicy.backedUp(
        settings.copy(
            userBlocked = userBlocked,
            userAllowed = userAllowed,
            appRules = appRules,
            listChoices = listChoices,
            scopeMode = scopeMode,
            excludedApps = excludedApps,
            includedApps = includedApps,
            blockAnswer = blockAnswer,
            upstream = upstream,
            customUpstream = customUpstream,
            bypassGuard = bypassGuard,
            bypassAllowed = bypassAllowed,
            listUpdateHours = listUpdateHours,
            listUpdateWifiOnly = listUpdateWifiOnly,
            queryLogEnabled = queryLogEnabled,
            updateWifiOnly = updateWifiOnly,
        ),
    )

    /** How much a person is about to lose or gain, for the screen that asks them to confirm. */
    val ruleCount: Int get() = userBlocked.size + userAllowed.size + appRules.size
    val listCount: Int get() = listChoices.count { it.value }

    companion object {
        const val FORMAT = 1

        /** The name offered in the system's file picker. Dated, because these accumulate. */
        fun suggestedFileName(nowMs: Long): String {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(nowMs))
            return "malachi-backup-$day.json"
        }

        // Pretty-printed on purpose: the file is small, it is the user's, and being able to read
        // it in any text editor is part of it being theirs rather than ours.
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun of(settings: MalachiSettings, appVersion: String, nowMs: Long) = Backup(
            exportedAtMs = nowMs,
            appVersion = appVersion,
            userBlocked = settings.userBlocked,
            userAllowed = settings.userAllowed,
            appRules = settings.appRules,
            listChoices = settings.listChoices,
            scopeMode = settings.scopeMode,
            excludedApps = settings.excludedApps,
            includedApps = settings.includedApps,
            blockAnswer = settings.blockAnswer,
            upstream = settings.upstream,
            customUpstream = settings.customUpstream,
            bypassGuard = settings.bypassGuard,
            bypassAllowed = settings.bypassAllowed,
            listUpdateHours = settings.listUpdateHours,
            listUpdateWifiOnly = settings.listUpdateWifiOnly,
            queryLogEnabled = settings.queryLogEnabled,
            updateWifiOnly = settings.updateWifiOnly,
        )

        fun encode(backup: Backup): String = json.encodeToString(serializer(), backup)

        /**
         * Reads a file back, or explains why not.
         *
         * A file from a *newer* app is read rather than refused: unknown keys are dropped and
         * everything this version understands is restored, which is a better answer than telling
         * somebody their own backup is unreadable because they downgraded. What is refused is
         * text that is not one of our files at all.
         */
        fun decode(text: String): Result<Backup> = runCatching {
            json.decodeFromString(serializer(), text)
        }
    }
}
