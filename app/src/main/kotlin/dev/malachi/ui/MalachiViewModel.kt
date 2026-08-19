package dev.malachi.ui

import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.malachi.MalachiApplication
import dev.malachi.R
import dev.malachi.data.AppInventory
import dev.malachi.data.AppRule
import dev.malachi.data.AppScopeMode
import dev.malachi.data.Backup
import dev.malachi.data.BackupPolicy
import dev.malachi.data.BackupSharing
import dev.malachi.data.BackupStore
import dev.malachi.data.BlockAnswerMode
import dev.malachi.data.BypassGuard
import dev.malachi.data.DomainInput
import dev.malachi.data.GuidedSearch
import dev.malachi.data.InstalledApp
import dev.malachi.data.MalachiSettings
import dev.malachi.data.ThemeMode
import dev.malachi.data.UpdateChannel
import dev.malachi.debug.DebugLog
import dev.malachi.data.UpstreamDns
import dev.malachi.filter.AppTrace
import dev.malachi.filter.AppTraceState
import dev.malachi.filter.QueryLog
import dev.malachi.filter.QueryLogState
import dev.malachi.filter.RuleSource
import dev.malachi.filter.TraceOutcome
import dev.malachi.lists.ListUpdateWorker
import dev.malachi.net.FilterWatchdogWorker
import dev.malachi.net.MalachiVpnService
import dev.malachi.net.VpnController
import dev.malachi.net.VpnStatus
import dev.malachi.stats.StatsData
import dev.malachi.stats.StatsWindow
import dev.malachi.update.ChannelSwitch
import dev.malachi.update.UpdateCenter
import dev.malachi.update.UpdateInfo
import dev.malachi.update.UpdatePolicy
import dev.malachi.update.Updater
import dev.malachi.update.notesIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the screens read and every edit they make.
 *
 * One view model for the whole app rather than one per screen: the screens all read the same
 * settings object and the same live filter status, and splitting that across five view models
 * would mean five subscriptions to the same flow and five chances for them to disagree about
 * whether the filter is on.
 */
class MalachiViewModel(private val app: MalachiApplication) : ViewModel() {

    val inventory: AppInventory get() = app.appInventory

    val settings: StateFlow<MalachiSettings> = app.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MalachiSettings())

    val status = VpnStatus.status

    /**
     * The query log, rebuilt on subscribe.
     *
     * [QueryLog] deliberately publishes nothing while nobody is watching — that is what keeps a
     * lookup from allocating a snapshot for an empty room — so opening the screen has to ask for
     * one, or it would show whatever was current the last time somebody looked.
     */
    val queryLog: StateFlow<QueryLogState> = QueryLog.state
        .onStart { QueryLog.publish() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueryLogState())

    /**
     * One app's lookups, one by one. Same bargain as [queryLog]: nothing is published while the
     * screen is closed, so opening it has to ask for a snapshot.
     */
    val appTrace: StateFlow<AppTraceState> = AppTrace.state
        .onStart { AppTrace.publish() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppTraceState())

    /**
     * The persisted counters. Loaded when a screen asks rather than published per lookup: this
     * is history, it changes slowly, and the tunnel must not pay to keep a UI that is usually
     * closed up to date.
     */
    private val _stats = MutableStateFlow(StatsData())
    val stats: StateFlow<StatsData> = _stats.asStateFlow()

    fun refreshStats() {
        launchSafely("reading the statistics") {
            _stats.value = withContext(Dispatchers.IO) { app.statsStore.snapshot() }
        }
    }

    /** Forgets one window of statistics; see [dev.malachi.stats.StatsStore.clear]. */
    fun clearStats(window: StatsWindow) {
        app.statsStore.clear(window)
        refreshStats()
    }

    val listStates = app.filterRepository.listStates
    val refreshingLists = app.filterRepository.refreshing

    /** How far along a blocklist download is; see [dev.malachi.filter.FilterRepository.listProgress]. */
    val listProgress = app.filterRepository.listProgress
    val updateState = UpdateCenter.state
    val themeMode: StateFlow<ThemeMode> = app.themeStore.mode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    /** Total domains across the compiled lists, for the home screen's one honest number. */
    val listedDomains: StateFlow<Int> = app.filterRepository.engine
        .map { it.listedDomains }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * The live filter, so a screen can ask what *would* happen to a domain right now rather than
     * only what happened when it was last looked up. Free to read: it is the same immutable
     * engine the tunnel holds, rebuilt on a rule change, never per query.
     */
    val engine = app.filterRepository.engine

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** The installed apps, loaded once — enumerating them takes a noticeable moment. */
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    val versionName: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    val versionCode: Int = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    init {
        launchSafely("listing the installed apps") {
            _apps.value = withContext(Dispatchers.IO) { app.appInventory.networkApps() }
        }
    }

    /** Who holds the always-on VPN slot, as far as the platform lets us know. */
    private val _alwaysOn = MutableStateFlow<VpnController.AlwaysOn>(VpnController.AlwaysOn.Unknown)
    val alwaysOn: StateFlow<VpnController.AlwaysOn> = _alwaysOn.asStateFlow()

    /** True when something other than us currently holds a VPN — this one really is observable. */
    private val _anotherVpn = MutableStateFlow(false)
    val anotherVpn: StateFlow<Boolean> = _anotherVpn.asStateFlow()

    /**
     * Re-reads what we can about the VPN situation. Called on every resume: the only way to
     * change any of it is to leave for the system's settings and come back, so that return is
     * exactly when what we are showing has gone stale.
     */
    fun refreshVpnEnvironment() {
        _alwaysOn.value = VpnController.alwaysOn(app)
        _anotherVpn.value = !VpnStatus.status.value.tunnelUp && VpnController.anotherVpnActive(app)
    }

    fun openVpnSettings() {
        VpnController.openVpnSettings(app)
    }

    /** The system screen where Private DNS is turned off; see [VpnController.openPrivateDnsSettings]. */
    fun openPrivateDnsSettings() {
        VpnController.openPrivateDnsSettings(app)
    }

    /** Android's own page for an app, which is where its battery and data figures live. */
    fun openAppInfo(packageName: String) {
        VpnController.openAppInfo(app, packageName)
    }

    /** Launches another app — the guided search's "go and make it fail" step. */
    fun openApp(packageName: String) {
        VpnController.openApp(app, packageName)
    }

    /**
     * Starts the filter if the settings say it should be running and it isn't.
     *
     * The home screen says "starting the filter…" whenever it is switched on with no tunnel up,
     * and until this existed nothing in the app made that true: recovery had to come from a
     * broadcast, a fresh process, or a half-hourly worker the system is free to defer. Somebody
     * looking at the spinner is the strongest signal there is that a filter is wanted now, and it
     * is also the one moment a service start is certain to be allowed.
     *
     * Everything it needs to decide already lives in the watchdog's check, which returns at once
     * when the filter is running, paused, off, or waiting on consent.
     */
    fun ensureFilterRunning() {
        launchSafely("starting the filter") { FilterWatchdogWorker.restoreIfNeeded(app) }
    }

    fun dismissAlwaysOnTip() = update { it.copy(alwaysOnTipDismissed = true) }

    /**
     * The welcome screen is done with, and with it the only launch that has no "before".
     *
     * The notes for whatever is installed right now are marked as read at the same moment,
     * because a fresh install has no previous version and "what changed" is then a dialog about
     * a change nobody experienced — landing, on the one screen that has to go well, directly
     * behind Android's own VPN consent dialog. It is done here rather than by having the updater
     * decline to keep them, so a build somebody sideloaded by hand over an older one still
     * explains itself at its next launch.
     */
    fun markWelcomeSeen() = update {
        it.copy(welcomeSeen = true, notesShownForVersionCode = versionCode)
    }

    fun dismissPrivateDnsNote() = update { it.copy(privateDnsNoteDismissed = true) }

    /**
     * Opens or closes the diagnostics window. A moment in the future rather than a flag, so it
     * shuts itself even if the app is never opened again — see [MalachiSettings.diagnosticsUntilMs].
     */
    fun setDiagnostics(on: Boolean) = update {
        it.copy(
            diagnosticsUntilMs = if (on) System.currentTimeMillis() + MalachiVpnService.DIAGNOSTICS_MILLIS else 0,
        )
    }

    // ---- diagnosing one app --------------------------------------------------------------

    /**
     * Starts (or re-arms) the per-app timeline.
     *
     * Re-arming the same app deliberately keeps what has already been recorded: somebody who let
     * the window lapse halfway through reproducing a bug is in the middle of an experiment, and
     * throwing the first half away because a clock ran out would be the app taking their work.
     */
    fun startDiagnosing(packageName: String) = update {
        it.copy(
            diagnoseApp = packageName,
            diagnoseUntilMs = System.currentTimeMillis() + MalachiVpnService.DIAGNOSE_APP_MILLIS,
        )
    }

    fun stopDiagnosing() = update { it.copy(diagnoseApp = "", diagnoseUntilMs = 0) }

    fun clearAppTrace() = AppTrace.clear()

    // ---- the guided search ----------------------------------------------------------------

    /**
     * Starts the step-by-step search for the blocked name that breaks an app.
     *
     * The trace is cleared and its window re-armed: the first step is a capture, and a capture
     * that began with the last half hour's lookups already in it would offer names the app asked
     * for long before anybody was watching.
     *
     * None of the search's own rule edits are written into the timeline, deliberately. A round
     * exempts up to ten names at once, so noting each would put a hundred rows of Malachi's own
     * bookkeeping in front of the lookups the timeline exists to show — and the card above it
     * already says exactly which step is running and what is refused.
     */
    fun startGuide(packageName: String) {
        AppTrace.clear()
        update { settings ->
            settings.copy(
                appRules = settings.guide?.cleared(settings.appRules) ?: settings.appRules,
                guide = GuidedSearch(packageName = packageName),
                diagnoseApp = packageName,
                diagnoseUntilMs = watchUntil(),
            )
        }
    }

    /** The user says they have reproduced the failure; whatever was refused becomes the shortlist. */
    fun guideCaptured() {
        // Asked for rather than read: nothing is published while the screen is away, and the
        // capture step is precisely the one the user spends outside this app.
        AppTrace.publish()
        val refused = AppTrace.state.value.suspects(Int.MAX_VALUE)
            .filter { it.source == RuleSource.LIST }
            .map { it.domain }
        update { settings ->
            val guide = settings.guide?.captured(refused, GuidedSearch.MAX_CANDIDATES) ?: return@update settings
            settings.copy(appRules = guide.applied(settings.appRules), guide = guide, diagnoseUntilMs = watchUntil())
        }
    }

    /** The answer to the only question the search ever asks: did it work this time? */
    fun guideAnswered(worked: Boolean) = update { settings ->
        val guide = settings.guide?.answered(worked) ?: return@update settings
        settings.copy(appRules = guide.applied(settings.appRules), guide = guide, diagnoseUntilMs = watchUntil())
    }

    /** Back to the baseline, keeping the shortlist. For a round whose answer is not to be trusted. */
    fun guideRestart() = update { settings ->
        val guide = settings.guide?.restarted() ?: return@update settings
        settings.copy(appRules = guide.applied(settings.appRules), guide = guide, diagnoseUntilMs = watchUntil())
    }

    /**
     * Takes the fix: the name that was found stays allowed in this app, and every other name the
     * search exempted goes back to being refused, because each of them was shown not to matter.
     */
    fun guideAcceptFix() {
        val guide = settings.value.guide ?: return
        val culprit = guide.culprit
        if (culprit.isEmpty()) return
        update {
            it.copy(
                appRules = guide.cleared(it.appRules) + AppRule(culprit, guide.packageName, block = false),
                guide = null,
            )
        }
        noteInTrace(culprit, guide.packageName, TraceOutcome.RULE_ALLOWED)
    }

    /**
     * No single name explained it, so all of them are kept allowed in this app.
     *
     * The honest outcome when an app needs two of them at once: it is not the answer the search
     * was looking for, but it is a working app, and it is still narrower than putting the whole
     * app outside the filter — which is what somebody does next if this offers them nothing.
     */
    fun guideKeepAll() {
        val guide = settings.value.guide ?: return
        update {
            it.copy(
                appRules = guide.cleared(it.appRules) +
                    guide.candidates.map { domain -> AppRule(domain, guide.packageName, block = false) },
                guide = null,
            )
        }
    }

    /** Leaves the search, putting every rule it wrote back the way it found it. */
    fun guideCancel() = update { settings ->
        val guide = settings.guide ?: return@update settings
        settings.copy(appRules = guide.cleared(settings.appRules), guide = null)
    }

    /**
     * Pushes the watching window back to its full length.
     *
     * Answering a step is the strongest evidence there is that somebody is still working, and a
     * window that lapsed at step four of nine would take the recording away in the middle of the
     * one errand it exists for. It still expires the moment they stop.
     */
    private fun watchUntil(): Long = System.currentTimeMillis() + MalachiVpnService.DIAGNOSE_APP_MILLIS

    /**
     * The whole session as text, for the one thing this app cannot do for somebody: read their
     * phone. Deliberately hand-built rather than a dump of the data class — a paste into a
     * message has to be legible on its own, with the app it is about at the top of it.
     */
    fun appTraceReport(): String {
        val trace = AppTrace.state.value
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        return buildString {
            appendLine("Malachi $versionName — ${labelFor(trace.packageName)} (${trace.packageName.orEmpty()})")
            appendLine("${trace.blocked} blocked, ${trace.answered} answered, ${trace.stalled} with no answer")
            // Oldest first here, unlike the screen: read as a report this is a sequence of
            // events, and a sequence is read forwards.
            trace.events.asReversed().forEach { event ->
                append(time.format(java.util.Date(event.atMs)))
                append(' ')
                append(event.outcome.name.lowercase())
                append(' ')
                append(event.domain)
                if (event.type != 0) append(" ${AppTrace.typeLabel(event.type)}")
                if (event.attempt > 1) append(" #${event.attempt}")
                if (event.elapsedMs >= 0) append(" ${event.elapsedMs}ms")
                if (event.detail.isNotEmpty()) append(" [${event.detail}]")
                if (event.reason != dev.malachi.filter.TraceReason.NONE) append(" (${event.reason.name.lowercase()})")
                appendLine()
            }
        }
    }

    /** A display label for the app named in [VpnController.AlwaysOn.Other]. */
    fun alwaysOnOtherLabel(): String? =
        (_alwaysOn.value as? VpnController.AlwaysOn.Other)?.let { labelFor(it.packageName) }

    // ---- the filter itself -------------------------------------------------------------

    /**
     * Turning it *off* is immediate. Turning it on is not committed here: the activity has to
     * obtain VPN consent first, and writing `true` before that would leave the switch claiming
     * a filter that never started. See [confirmFilterEnabled].
     */
    fun setFilterEnabled(enabled: Boolean) = update {
        it.copy(filteringEnabled = enabled, pausedUntilMs = 0)
    }

    /** Called once the system's VPN dialog has been accepted (or was never needed). */
    fun confirmFilterEnabled() {
        VpnStatus.starting()
        update { it.copy(filteringEnabled = true, pausedUntilMs = 0) }
        // The observer in MalachiApplication only reacts to a *change*, and starting from the
        // screen the user just touched is also the one context where a foreground service is
        // guaranteed to be allowed to start.
        runCatching { VpnController.start(app) }
    }

    /**
     * The consent dialog came back refused, or was never worth showing.
     *
     * The switch goes back off — it would be a lie otherwise — but the reason is recorded so the
     * screen can say what happened. Silently reverting was the whole bug: from the outside it
     * was indistinguishable from a dead button.
     */
    fun filterConsentRefused() {
        VpnStatus.consentRefused()
        setFilterEnabled(false)
    }

    /** Refused before we even asked, because another app demonstrably owns the always-on slot. */
    fun filterBlockedByAlwaysOn() {
        VpnStatus.alwaysOnElsewhere()
        setFilterEnabled(false)
        refreshVpnEnvironment()
    }

    fun pause(minutes: Int = (MalachiVpnService.PAUSE_MILLIS / 60_000L).toInt()) = update {
        it.copy(pausedUntilMs = System.currentTimeMillis() + minutes * 60_000L)
    }

    fun resume() = update { it.copy(pausedUntilMs = 0) }

    // ---- scope: which apps are filtered ------------------------------------------------

    fun setScopeMode(mode: AppScopeMode) = update { it.copy(scopeMode = mode) }

    /** Sets whether [packageName] is filtered, in whichever direction the current mode means. */
    fun setAppCovered(packageName: String, covered: Boolean) = update { settings ->
        when (settings.scopeMode) {
            AppScopeMode.ALL_EXCEPT -> settings.copy(
                excludedApps = if (covered) settings.excludedApps - packageName else settings.excludedApps + packageName,
            )
            AppScopeMode.ONLY_SELECTED -> settings.copy(
                includedApps = if (covered) settings.includedApps + packageName else settings.includedApps - packageName,
            )
        }
    }

    // ---- rules -------------------------------------------------------------------------

    /**
     * A rule that was just written, and the way back.
     *
     * Writing a rule is one tap from a list of domains, which makes it one tap from the wrong
     * domain — and until this existed nothing said it had happened at all, let alone offered to
     * take it back. [undo] restores exactly what was there before rather than merely deleting
     * what was added: blocking a domain the user had previously allowed is a replacement, and an
     * undo that left it in neither list would be a second silent edit.
     */
    data class RuleEdit(val domain: String, val undo: () -> Unit)

    /** Returns what was stored and how to unstore it, or null when the text wasn't a domain. */
    fun addUserRule(raw: String, block: Boolean): RuleEdit? {
        val domain = DomainInput.parse(raw) ?: return null
        val before = settings.value
        update { it.withUserRule(domain, block) }
        return RuleEdit(domain) { update { it.withUserRuleFrom(before, domain) } }
    }

    fun removeUserRule(domain: String) = update {
        it.copy(userBlocked = it.userBlocked - domain, userAllowed = it.userAllowed - domain)
    }

    /** Adds or replaces a rule scoped to one app. */
    fun setAppRule(domain: String, packageName: String, block: Boolean): RuleEdit? {
        val parsed = DomainInput.parse(domain) ?: return null
        val before = settings.value
        update { it.withAppRule(parsed, packageName, block) }
        noteInTrace(parsed, packageName, if (block) TraceOutcome.RULE_BLOCKED else TraceOutcome.RULE_ALLOWED)
        return RuleEdit(parsed) {
            update { it.withAppRuleFrom(before, parsed, packageName) }
            noteInTrace(parsed, packageName, TraceOutcome.RULE_REMOVED)
        }
    }

    fun removeAppRule(domain: String, packageName: String) {
        update { settings ->
            settings.copy(appRules = settings.appRules.filterNot { it.domain == domain && it.packageName == packageName })
        }
        noteInTrace(domain, packageName, TraceOutcome.RULE_REMOVED)
    }

    /**
     * Exempts every domain in [domains] for one app, or takes all those exemptions back.
     *
     * The first question of any such investigation is not "which domain" but "is it this app at
     * all", and answering it one switch at a time across a dozen names is enough work that people
     * give up and exclude the whole app instead — which turns filtering off for it permanently.
     * One tap answers it; from there the switches narrow it down.
     *
     * Written as a single settings update rather than a loop of them: a dozen writes is a dozen
     * filter rebuilds and a dozen emissions to every screen.
     */
    fun setAppExceptions(domains: List<String>, packageName: String, allowed: Boolean): RuleEdit? {
        val parsed = domains.mapNotNull { DomainInput.parse(it) }.distinct()
        if (parsed.isEmpty()) return null
        val before = settings.value
        update { settings ->
            val others = settings.appRules.filterNot { it.packageName == packageName && it.domain in parsed }
            settings.copy(
                appRules = if (allowed) {
                    others + parsed.map { AppRule(it, packageName, block = false) }
                } else {
                    others
                },
            )
        }
        parsed.forEach {
            noteInTrace(it, packageName, if (allowed) TraceOutcome.RULE_ALLOWED else TraceOutcome.RULE_REMOVED)
        }
        return RuleEdit(parsed.first()) {
            update { it.copy(appRules = before.appRules) }
            // Each domain put back as it was, and said as it was: a bulk undo that announced
            // "removed" for a domain the user had deliberately blocked would be describing an
            // edit that did not happen.
            parsed.forEach { domain ->
                val restored = before.appRules.firstOrNull { it.packageName == packageName && it.domain == domain }
                noteInTrace(
                    domain,
                    packageName,
                    when {
                        restored == null -> TraceOutcome.RULE_REMOVED
                        restored.block -> TraceOutcome.RULE_BLOCKED
                        else -> TraceOutcome.RULE_ALLOWED
                    },
                )
            }
        }
    }

    /**
     * Writes an edit into the timeline of the app being diagnosed, if it is that app's.
     *
     * Here rather than in the screen, so a rule written from the app's own detail screen — or
     * undone from the bar at the bottom of any of them — still shows up in the experiment the
     * user is running. A timeline that only recorded edits made from one screen would quietly
     * lie about what had been tried.
     */
    private fun noteInTrace(domain: String, packageName: String, outcome: TraceOutcome) {
        if (AppTrace.owns(packageName)) AppTrace.rule(domain, outcome)
    }

    // ---- lists -------------------------------------------------------------------------

    fun setListEnabled(id: String, enabled: Boolean) = update { it.withListEnabled(id, enabled) }

    fun setListUpdateHours(hours: Int) = update { it.copy(listUpdateHours = hours) }

    fun setListUpdateWifiOnly(wifiOnly: Boolean) = update { it.copy(listUpdateWifiOnly = wifiOnly) }

    fun refreshLists() = ListUpdateWorker.runNow(app, force = true)

    // ---- resolution --------------------------------------------------------------------

    fun setBlockAnswer(mode: BlockAnswerMode) = update { it.copy(blockAnswer = mode) }

    fun setUpstream(upstream: UpstreamDns) = update { it.copy(upstream = upstream) }

    fun setCustomUpstream(value: String) = update {
        it.copy(upstream = UpstreamDns.CUSTOM, customUpstream = value.trim())
    }

    fun setBypassGuard(guard: BypassGuard) = update { it.copy(bypassGuard = guard) }

    fun setBypassAllowed(allowed: Boolean) = update { it.copy(bypassAllowed = allowed) }

    // ---- the rest ----------------------------------------------------------------------

    fun setQueryLogEnabled(enabled: Boolean) = update { it.copy(queryLogEnabled = enabled) }

    fun clearQueryLog() = QueryLog.clearRecords()

    fun setUpdateWifiOnly(wifiOnly: Boolean) = update { it.copy(updateWifiOnly = wifiOnly) }

    // ---- which stream of builds this phone follows ---------------------------------------

    /** What the chosen channel last said it had; null until a manifest has been read. */
    val channelOffer = UpdateCenter.channelOffer

    /**
     * Moves this phone to [channel] and asks straight away.
     *
     * The check is forced rather than left to the twelve-hourly one, because a person who has
     * just chosen a channel is owed the answer now — and because in the one direction where
     * something *can* happen immediately, it should.
     */
    fun setUpdateChannel(channel: UpdateChannel) {
        if (settings.value.updateChannel == channel) return
        // What is on screen describes the channel being left, and would otherwise sit there
        // being wrong until the check comes back.
        UpdateCenter.forgetChannelOffer()
        launchSafely("moving to the ${channel.name.lowercase()} channel") {
            // One coroutine, and the write is awaited before the check. These were two, and the
            // check routinely won the race: choosing a channel then asked the channel being
            // *left* what it had, reported that, and left the screen describing the wrong one
            // until something else triggered a check. Caught on a device, where the log said
            // "checking the stable channel" a moment after testing had been chosen.
            app.settingsStore.update { it.copy(updateChannel = channel) }
            Updater(app).checkAndUpdate(force = true)
        }
    }

    /**
     * What choosing [channel] would do, for the sentence the screen shows before and after.
     *
     * Pure, and shared by the confirmation dialog and the standing notice underneath the row, so
     * the warning and the explanation cannot come to disagree about what is going to happen.
     */
    fun channelSwitch(offer: UpdateInfo?): ChannelSwitch = UpdatePolicy.switching(
        installedVersionCode = versionCode,
        channelVersionCode = offer?.versionCode ?: 0,
        channelVersionName = offer?.versionName.orEmpty(),
    )

    // ---- what changed in the version that just installed itself ---------------------------

    /**
     * The notes for the running build, if they have not been read yet.
     *
     * Only ever the *installed* version: notes held for something not yet installed describe a
     * future that may never arrive, and showing them would be announcing a change that has not
     * happened.
     */
    fun releaseNotes(settings: MalachiSettings, language: String): String? {
        if (settings.pendingNotesVersionCode != versionCode) return null
        if (settings.notesShownForVersionCode == versionCode) return null
        return settings.pendingNotes.notesIn(language).takeIf { it.isNotBlank() }
    }

    fun markReleaseNotesSeen() = update { it.copy(notesShownForVersionCode = versionCode) }

    // ---- backup -------------------------------------------------------------------------

    /**
     * The outcome of the last export or import, for the screen to show once and forget.
     *
     * Built here rather than in the composable because it counts what was actually written or
     * read, and "restored 47 rules and 6 lists" is the sentence that tells somebody the file they
     * picked was the right one — which is the only feedback that matters when the alternative is
     * silently overwriting a year of work with an empty file.
     */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun clearBackupMessage() { _backupMessage.value = null }

    fun exportBackup(uri: Uri) {
        launchSafely("writing the backup") {
            val settings = app.settingsStore.current()
            val backup = Backup.of(settings, versionName, System.currentTimeMillis())
            val written = withContext(Dispatchers.IO) {
                BackupStore(app.contentResolver).write(uri, Backup.encode(backup))
            }
            if (written) {
                // Only now: a fingerprint stored for a file that failed to write is a reminder
                // switched off for a backup that does not exist.
                update { BackupPolicy.backedUp(it) }
                _backupMessage.value = app.getString(R.string.backup_exported, backup.ruleCount, backup.listCount)
            } else {
                _backupMessage.value = app.getString(R.string.backup_export_failed)
            }
        }
    }

    /**
     * A file that has been read and understood, waiting for the user to say yes.
     *
     * Restoring is the only thing in this app that cannot be undone — it replaces work that took
     * months to accumulate with the contents of a file picked from a list of filenames — so it
     * asks first, and it asks with numbers: what is in the file, and what is about to be replaced.
     * Picking the wrong file is an ordinary mistake, and "0 rules" on the confirmation is the only
     * moment anybody would catch it.
     */
    private val _pendingRestore = MutableStateFlow<Backup?>(null)
    val pendingRestore: StateFlow<Backup?> = _pendingRestore.asStateFlow()

    fun importBackup(uri: Uri) {
        launchSafely("reading the backup") {
            val text = withContext(Dispatchers.IO) { BackupStore(app.contentResolver).read(uri) }
            val backup = text?.let { Backup.decode(it).getOrNull() }
            if (backup == null) {
                _backupMessage.value = app.getString(R.string.backup_import_failed)
                return@launchSafely
            }
            _pendingRestore.value = backup
        }
    }

    fun cancelRestore() { _pendingRestore.value = null }

    fun confirmRestore() {
        val backup = _pendingRestore.value ?: return
        _pendingRestore.value = null
        DebugLog.i(TAG, "restoring a backup written by ${backup.appVersion.ifEmpty { "an unknown version" }} (format ${backup.format})")
        update { backup.restoredInto(it) }
        _backupMessage.value = app.getString(R.string.backup_imported, backup.ruleCount, backup.listCount)
    }

    /**
     * Hands the backup to another app. Whether it counts as saved is decided by the share sheet
     * reporting back, not here — see [dev.malachi.data.BackupSharedReceiver].
     */
    fun shareBackup() {
        launchSafely("sharing the backup") {
            val backup = Backup.of(app.settingsStore.current(), versionName, System.currentTimeMillis())
            val opened = withContext(Dispatchers.IO) { BackupSharing.share(app, backup) }
            if (!opened) _backupMessage.value = app.getString(R.string.backup_export_failed)
        }
    }

    fun remindBackupLater() = update { BackupPolicy.laterFrom(it, System.currentTimeMillis()) }

    fun stopBackupReminders() = update { BackupPolicy.silenced(it) }

    fun setBackupReminders(on: Boolean) = update {
        if (on) BackupPolicy.unsilenced(it) else BackupPolicy.silenced(it)
    }

    fun setThemeMode(mode: ThemeMode) {
        launchSafely("storing the theme") { app.themeStore.setMode(mode) }
    }

    /** The manual "check for updates" button: forced, so Wi-Fi-only doesn't silently skip it. */
    fun checkForUpdate() {
        launchSafely("checking for an update") { Updater(app).checkAndUpdate(force = true) }
    }

    /**
     * Display label for a package, falling back to the package name itself.
     *
     * Memoised, and not as a micro-optimisation: this is called from inside composition, once
     * per visible row and once per ranked app, and the fallback path is a synchronous
     * PackageManager call — a binder round trip. Recomposing a screen full of rows was firing
     * dozens of those on the main thread, which is enough to hold it long enough for the system
     * to declare the app unresponsive. A label never changes while the app is installed, so one
     * lookup per package for the life of the view model is the correct number.
     */
    private val labelCache = ConcurrentHashMap<String, String>()

    fun labelFor(packageName: String?): String {
        if (packageName == null) return ""
        labelCache[packageName]?.let { return it }
        val label = apps.value.firstOrNull { it.packageName == packageName }?.label
            ?: app.appInventory.label(packageName)
            ?: packageName
        labelCache[packageName] = label
        return label
    }

    private fun update(transform: (MalachiSettings) -> MalachiSettings) {
        launchSafely("saving a setting") { app.settingsStore.update(transform) }
    }

    /**
     * Every errand this view model runs in the background, with a floor under it.
     *
     * `viewModelScope` carries no exception handler, so an uncaught throw in any of these reaches
     * the thread's default handler and takes the process down. That would be a fair price for a
     * bug; the trouble is that the likeliest throw here is not a bug at all. DataStore reports a
     * write it could not make — a full disk, a filesystem an OEM "cleaner" has been through, an
     * app-data directory that was removed underneath us — as an ordinary `IOException` from
     * `edit`, and **every tap that changes a setting comes through here**. So the one class of
     * device least able to recover from it was the one that crashed on the next tap, whichever
     * tap it was.
     *
     * Logged rather than surfaced: there is no screen on which "your phone could not write a
     * boolean" is actionable, and the setting simply stays as it was, which is what the switch
     * will show on the next emission. Cancellation is rethrown — swallowing it leaves a coroutine
     * ignoring the scope that is being torn down around it.
     */
    private fun launchSafely(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                DebugLog.e(TAG, "$what failed", t)
            }
        }
    }

    class Factory(private val app: MalachiApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MalachiViewModel(app) as T
    }

    private companion object {
        const val TAG = "MalachiUi"
    }
}
