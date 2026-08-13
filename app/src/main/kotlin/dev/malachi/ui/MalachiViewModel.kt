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
import dev.malachi.data.InstalledApp
import dev.malachi.data.MalachiSettings
import dev.malachi.data.ThemeMode
import dev.malachi.debug.DebugLog
import dev.malachi.data.UpstreamDns
import dev.malachi.filter.QueryLog
import dev.malachi.filter.QueryLogState
import dev.malachi.lists.ListUpdateWorker
import dev.malachi.net.FilterWatchdogWorker
import dev.malachi.net.MalachiVpnService
import dev.malachi.net.VpnController
import dev.malachi.net.VpnStatus
import dev.malachi.stats.StatsData
import dev.malachi.stats.StatsWindow
import dev.malachi.update.UpdateCenter
import dev.malachi.update.UpdateWorker
import dev.malachi.update.Updater
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
     * The persisted counters. Loaded when a screen asks rather than published per lookup: this
     * is history, it changes slowly, and the tunnel must not pay to keep a UI that is usually
     * closed up to date.
     */
    private val _stats = MutableStateFlow(StatsData())
    val stats: StateFlow<StatsData> = _stats.asStateFlow()

    fun refreshStats() {
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch { FilterWatchdogWorker.restoreIfNeeded(app) }
    }

    fun dismissAlwaysOnTip() = update { it.copy(alwaysOnTipDismissed = true) }

    fun markWelcomeSeen() = update { it.copy(welcomeSeen = true) }

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

    /** Returns the domain that was stored, or null when the text wasn't one. */
    fun addUserRule(raw: String, block: Boolean): String? {
        val domain = DomainInput.parse(raw) ?: return null
        update { settings ->
            // A domain is in one list or the other, never both: adding to one removes it from
            // the other, so the two can't contradict each other behind the user's back.
            settings.copy(
                userBlocked = if (block) settings.userBlocked + domain else settings.userBlocked - domain,
                userAllowed = if (block) settings.userAllowed - domain else settings.userAllowed + domain,
            )
        }
        return domain
    }

    fun removeUserRule(domain: String) = update {
        it.copy(userBlocked = it.userBlocked - domain, userAllowed = it.userAllowed - domain)
    }

    /** Adds or replaces a rule scoped to one app. */
    fun setAppRule(domain: String, packageName: String, block: Boolean): String? {
        val parsed = DomainInput.parse(domain) ?: return null
        update { settings ->
            val without = settings.appRules.filterNot { it.domain == parsed && it.packageName == packageName }
            settings.copy(appRules = without + AppRule(parsed, packageName, block))
        }
        return parsed
    }

    fun removeAppRule(domain: String, packageName: String) = update { settings ->
        settings.copy(appRules = settings.appRules.filterNot { it.domain == domain && it.packageName == packageName })
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { BackupStore(app.contentResolver).read(uri) }
            val backup = text?.let { Backup.decode(it).getOrNull() }
            if (backup == null) {
                _backupMessage.value = app.getString(R.string.backup_import_failed)
                return@launch
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
        viewModelScope.launch {
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
        viewModelScope.launch { app.themeStore.setMode(mode) }
    }

    /** The manual "check for updates" button: forced, so Wi-Fi-only doesn't silently skip it. */
    fun checkForUpdate() {
        viewModelScope.launch { runCatching { Updater(app).checkAndUpdate(force = true) } }
    }

    fun scheduleUpdateCheck() = UpdateWorker.runNow(app)

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
        viewModelScope.launch { app.settingsStore.update(transform) }
    }

    class Factory(private val app: MalachiApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MalachiViewModel(app) as T
    }

    private companion object {
        const val TAG = "MalachiUi"
    }
}
