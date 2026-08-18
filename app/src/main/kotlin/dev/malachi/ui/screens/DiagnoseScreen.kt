package dev.malachi.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.data.GuideStep
import dev.malachi.data.GuidedSearch
import dev.malachi.data.InstalledApp
import dev.malachi.filter.AppTrace
import dev.malachi.filter.AppTraceState
import dev.malachi.filter.RuleSource
import dev.malachi.filter.TraceEvent
import dev.malachi.filter.TraceOutcome
import dev.malachi.filter.TraceReason
import dev.malachi.filter.TraceSuspect
import dev.malachi.net.MalachiVpnService
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.AppIcon
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.CardPosition
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.MalachiFilterChip
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.PrimaryAction
import dev.malachi.ui.components.SecondaryAction
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.UndoBarHost
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.components.rememberUndoBar
import dev.malachi.ui.rememberRuleAnnouncer
import dev.malachi.ui.theme.MonoSmall
import dev.malachi.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which events the timeline shows. */
private enum class TraceFilter { ALL, BLOCKED, FAILED }

/** How many blocked domains the shortlist offers. Beyond this it stops being a shortlist. */
private const val SUSPECT_LIMIT = 12

/**
 * One app under a microscope: every lookup it makes, in order, and one switch per name to try
 * exempting.
 *
 * **Why this exists next to a query log that already names domains.** The log answers "what has
 * this app been resolving" as a *set* — one row per domain, latest verdict, a count — and that is
 * the right shape for the question it answers. It is the wrong shape for the one that brings
 * people here: an app that hangs, and no way to tell which name it is hanging on. Three things
 * that question needs and a set cannot hold —
 *
 * - **order, at the resolution of the eye.** "These four were refused in the two seconds after I
 *   tapped Log in" is not the same fact as four rows sorted by last-seen.
 * - **what happened to the lookups that were *allowed*.** Failing open means an allowed lookup
 *   can still leave an app waiting five seconds for a DNS server that never answers, and from the
 *   outside that is indistinguishable from a block — while the fix is the opposite one. Nothing
 *   in this app recorded it until now.
 * - **what has already been tried.** Exempting domains is trial and error by its nature. Writing
 *   each edit into the timeline turns it from a wall of events into an experiment log.
 *
 * The method the screen is built around, and the reason the shortlist has a bulk action at the
 * top of it: allow everything blocked, confirm the app works, then switch names back on until it
 * breaks again. That halves the search on the first tap and needs no understanding of DNS.
 */
@Composable
fun DiagnoseScreen(vm: MalachiViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val trace by vm.appTrace.collectAsStateWithLifecycle()

    // Whose screen this is. The setting is cleared when the window runs out — that is what stops
    // the tunnel attributing every lookup — so what the timeline belongs to has to be read from
    // the timeline itself, or a session would vanish at the moment it expired, evidence and all.
    val subject = settings.diagnoseApp.ifEmpty { trace.packageName.orEmpty() }.ifEmpty { null }
    var picking by remember { mutableStateOf(false) }

    if (subject == null || picking) {
        AppPicker(
            vm = vm,
            onBack = { if (picking) picking = false else onBack() },
            onPick = { vm.startDiagnosing(it); picking = false },
        )
    } else {
        TraceSession(
            vm = vm,
            packageName = subject,
            trace = trace,
            onBack = onBack,
            onChangeApp = { picking = true },
        )
    }
}

/**
 * Every app on the phone, with a search field, and one tap starts watching it.
 *
 * The same three rows above the list as the apps screen, and for the same reason: this screen
 * exists to find one app among two hundred, and a header block that fills half the viewport
 * pushes the first result off the screen before anybody has typed. What is deliberately *not*
 * here is the per-app switch — a tap means "watch this", not "stop filtering this", and the two
 * are one keystroke apart in intent and very far apart in consequence.
 */
@Composable
private fun AppPicker(vm: MalachiViewModel, onBack: () -> Unit, onPick: (String) -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }

    val visible = remember(apps, query, showSystem) {
        apps.asSequence()
            .filter { showSystem || !it.isSystem }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .toList()
    }

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.nav_diagnose), onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, spacing.sm, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Text(
                    stringResource(R.string.diagnose_pick_hint, MalachiVpnService.DIAGNOSE_APP_MINUTES),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.xs),
                )
            }
            item {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.apps_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    )
                    MalachiFilterChip(
                        selected = showSystem,
                        onClick = { showSystem = !showSystem },
                        label = { Text(stringResource(R.string.apps_show_system)) },
                        leadingIcon = if (showSystem) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
            }

            if (apps.isEmpty() || visible.isEmpty()) {
                item {
                    Text(
                        stringResource(
                            when {
                                apps.isEmpty() -> R.string.apps_loading
                                showSystem -> R.string.apps_no_matches
                                else -> R.string.apps_no_matches_try_system
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(spacing.lg),
                    )
                }
            }

            items(visible, key = { it.packageName }) { app ->
                PickerRow(app, vm) { onPick(app.packageName) }
            }
        }
    }
}

@Composable
private fun PickerRow(app: InstalledApp, vm: MalachiViewModel, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    MalachiCard(onClick = onClick) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName, vm.inventory)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    app.packageName,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TraceSession(
    vm: MalachiViewModel,
    packageName: String,
    trace: AppTraceState,
    onBack: () -> Unit,
    onChangeApp: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = Tokens.spacing
    val undo = rememberUndoBar()
    val announcer = rememberRuleAnnouncer(undo)

    val label = remember(packageName) { vm.labelFor(packageName) }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Built here rather than with stringResource, because the sentence is written from inside a
    // click handler where the count is finally known and composition is over.
    val resources = context.resources
    fun allowedMessage(count: Int) =
        resources.getQuantityString(R.plurals.diagnose_allowed_count, count, count, label)
    fun removedMessage(count: Int) =
        resources.getQuantityString(R.plurals.diagnose_removed_count, count, count, label)

    // Only this app's events: the buffer holds one app's, but a session that has just been
    // pointed at a different app is briefly still showing the last one's.
    val mine = trace.packageName == packageName
    val events = if (mine) trace.events else emptyList()

    // Saveable, unlike `scoping`: the filter is how the timeline is being read and has to
    // survive leaving the app to reproduce the fault, which is the whole method here. `scoping`
    // is a sheet and should not reopen itself.
    var filter by rememberSaveable { mutableStateOf(TraceFilter.ALL) }
    var scoping by remember { mutableStateOf<String?>(null) }

    val suspects = remember(trace, mine) {
        if (mine) trace.suspects(SUSPECT_LIMIT) else emptyList()
    }
    val visible = remember(events, filter) {
        events.filter {
            when (filter) {
                TraceFilter.ALL -> true
                TraceFilter.BLOCKED -> it.outcome == TraceOutcome.BLOCKED
                TraceFilter.FAILED -> it.outcome.stalled
            }
        }
    }
    // The exceptions this app already carries, so the switches read the truth rather than what
    // this screen last did.
    val allowed = remember(settings, packageName) {
        settings.appRulesFor(packageName).filterNot { it.block }.map { it.domain }.toSet()
    }
    // What the filter would do with each of these *now* — which is not what it did when the
    // lookup happened, and the difference is the whole feedback loop: a rule written a moment ago,
    // including one written against a parent name, has to show on the row it was written from.
    val liveBlocked = remember(engine, suspects, packageName) {
        suspects.associate { it.domain to engine.decide(it.domain, packageName).blocked }
    }
    val watching = settings.diagnosing() == packageName

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            MalachiTopBar(label, onBack) {
                IconButton(onClick = { copyTrace(context, vm.appTraceReport()) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                }
                IconButton(onClick = vm::clearAppTrace) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                item {
                    SessionCard(
                        vm = vm,
                        packageName = packageName,
                        label = label,
                        watching = watching,
                        untilMs = settings.diagnoseUntilMs,
                        onChangeApp = onChangeApp,
                    )
                }

                // The two ways this screen can be silently useless, each with the one thing that
                // fixes it. An empty timeline is otherwise read as "the app is not doing
                // anything", which is the wrong conclusion in both cases.
                if (!settings.isFiltering() || !status.tunnelUp) {
                    item { WarningCard(stringResource(R.string.diagnose_filter_off)) }
                } else if (!settings.covers(packageName)) {
                    item {
                        WarningCard(
                            text = stringResource(R.string.diagnose_out_of_scope, label),
                            action = stringResource(R.string.diagnose_bring_into_scope),
                            onAction = { vm.setAppCovered(packageName, true) },
                        )
                    }
                }

                item { SummaryRow(trace = if (mine) trace else AppTraceState()) }

                // While a guided search is running it owns the app's exceptions, flipping up to
                // ten of them per round. The manual switches below would be the user and the
                // search editing the same rules from two places at once, so only one of the two
                // is on screen at a time.
                val guide = settings.guide?.takeIf { it.packageName == packageName }
                if (guide != null) {
                    item {
                        GuideCard(
                            guide = guide,
                            appLabel = label,
                            refusedSoFar = trace.suspects(Int.MAX_VALUE).count { it.source == RuleSource.LIST },
                            onOpenApp = { vm.openApp(packageName) },
                            onAppInfo = { vm.openAppInfo(packageName) },
                            onCaptured = vm::guideCaptured,
                            onAnswered = vm::guideAnswered,
                            onRestart = vm::guideRestart,
                            onAcceptFix = vm::guideAcceptFix,
                            onKeepAll = vm::guideKeepAll,
                            onLeave = vm::guideCancel,
                        )
                    }
                } else {

                // Offered whether or not anything has been captured yet: the search's own first
                // step is "go and make it fail", so requiring a failure before it could be
                // started would make its most useful instruction unreachable.
                item { GuideOffer(onStart = { vm.startGuide(packageName) }) }

                item {
                    SectionHeader(
                        title = stringResource(R.string.diagnose_suspects_title),
                        supporting = stringResource(R.string.diagnose_suspects_hint),
                    )
                }

                if (suspects.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.diagnose_suspects_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(spacing.lg),
                        )
                    }
                } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            // The first question is never "which domain" but "is it this app at
                            // all", and answering it one switch at a time across a dozen names is
                            // enough work that people give up and exclude the whole app instead.
                            PrimaryAction(
                                text = stringResource(R.string.diagnose_allow_all),
                                onClick = {
                                    val domains = suspects.map { it.domain }
                                    vm.setAppExceptions(domains, packageName, allowed = true)?.let {
                                        undo.show(allowedMessage(domains.size), it.undo)
                                    }
                                },
                            )
                            SecondaryAction(
                                text = stringResource(R.string.diagnose_allow_none),
                                onClick = {
                                    val domains = suspects.map { it.domain }
                                    vm.setAppExceptions(domains, packageName, allowed = false)?.let {
                                        undo.show(removedMessage(domains.size), it.undo)
                                    }
                                },
                            )
                        }
                    }
                    item {
                        CardGroup {
                            suspects.forEachIndexed { index, suspect ->
                                SuspectRow(
                                    suspect = suspect,
                                    exempted = suspect.domain in allowed,
                                    liveBlocked = liveBlocked[suspect.domain] != false,
                                    position = cardPosition(index, suspects.size),
                                    onToggle = { on ->
                                        if (on) {
                                            announcer.announce(
                                                vm.setAppRule(suspect.domain, packageName, block = false),
                                                blocked = false,
                                                appLabel = label,
                                            )
                                        } else {
                                            vm.setAppExceptions(listOf(suspect.domain), packageName, allowed = false)
                                                ?.let { undo.show(removedMessage(1), it.undo) }
                                        }
                                    },
                                    onOpenScope = { scoping = suspect.domain },
                                )
                            }
                        }
                    }
                }

                } // end of the manual controls; the timeline below belongs to both

                item {
                    SectionHeader(
                        title = stringResource(R.string.diagnose_timeline_title),
                        supporting = stringResource(R.string.diagnose_timeline_hint),
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        TraceFilter.entries.forEach { option ->
                            MalachiFilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(stringResource(filterLabel(option))) },
                            )
                        }
                    }
                }

                if (visible.isEmpty()) {
                    item {
                        Text(
                            if (events.isEmpty()) {
                                stringResource(R.string.diagnose_timeline_empty, label)
                            } else {
                                stringResource(R.string.diagnose_timeline_no_matches)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(spacing.lg),
                        )
                    }
                }

                items(visible) { event -> EventRow(event, clock) }
            }
        }

        UndoBarHost(undo, Modifier.align(Alignment.BottomCenter).padding(spacing.md))
    }

    scoping?.let { domain ->
        DomainScopeDialog(
            domain = domain,
            appLabel = label,
            block = false,
            onDismiss = { scoping = null },
            onConfirm = { chosen ->
                announcer.announce(
                    vm.setAppRule(chosen, packageName, block = false),
                    blocked = false,
                    appLabel = label,
                )
                scoping = null
            },
        )
    }
}

/** Who is being watched, for how much longer, and the two ways out of it. */
@Composable
private fun SessionCard(
    vm: MalachiViewModel,
    packageName: String,
    label: String,
    watching: Boolean,
    untilMs: Long,
    onChangeApp: () -> Unit,
) {
    val spacing = Tokens.spacing
    CardGroup {
        MalachiCard(position = cardPosition(0, 2)) {
            Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName, vm.inventory, size = 44.dp)
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (watching) {
                            stringResource(
                                R.string.diagnose_watching,
                                ((untilMs - System.currentTimeMillis()) / 60_000L + 1).toInt(),
                            )
                        } else {
                            stringResource(R.string.diagnose_window_closed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (watching) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                if (watching) {
                    SecondaryAction(text = stringResource(R.string.diagnose_stop), onClick = vm::stopDiagnosing)
                } else {
                    PrimaryAction(
                        text = stringResource(R.string.diagnose_watch_again),
                        onClick = { vm.startDiagnosing(packageName) },
                    )
                }
            }
        }
        MalachiCard(position = cardPosition(1, 2), onClick = onChangeApp) {
            Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.diagnose_change_app), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.diagnose_change_app_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The offer to stop reading a list of domains and be walked through it instead. */
@Composable
private fun GuideOffer(onStart: () -> Unit) {
    val spacing = Tokens.spacing
    MalachiCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                stringResource(R.string.guide_start_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.guide_start_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            PrimaryAction(
                text = stringResource(R.string.guide_start_action),
                onClick = onStart,
                onContainer = MaterialTheme.colorScheme.onPrimaryContainer,
                container = MaterialTheme.colorScheme.primaryContainer,
            )
        }
    }
}

/**
 * The one thing to do next, and the two answers to it.
 *
 * Every step of the search is the same shape on purpose — a sentence, a way to force-stop the app,
 * and *did it work?* — because the person using it is not debugging DNS, they are trying to make
 * an app work. Nothing here asks them to read a domain until the last card, which names one.
 */
@Composable
private fun GuideCard(
    guide: GuidedSearch,
    appLabel: String,
    refusedSoFar: Int,
    onOpenApp: () -> Unit,
    onAppInfo: () -> Unit,
    onCaptured: () -> Unit,
    onAnswered: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onAcceptFix: () -> Unit,
    onKeepAll: () -> Unit,
    onLeave: () -> Unit,
) {
    val spacing = Tokens.spacing
    val done = guide.step == GuideStep.CULPRIT ||
        guide.step == GuideStep.RULED_OUT ||
        guide.step == GuideStep.EXHAUSTED ||
        guide.step == GuideStep.NOTHING_REFUSED
    val container = if (done) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = if (done) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    MalachiCard(color = container) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                when (guide.step) {
                    GuideStep.CAPTURE -> stringResource(R.string.guide_capture_title)
                    GuideStep.NOTHING_REFUSED -> stringResource(R.string.guide_nothing_title)
                    GuideStep.BASELINE -> stringResource(R.string.guide_baseline_title)
                    GuideStep.TESTING -> stringResource(R.string.guide_testing_title, guide.round, guide.candidates.size)
                    GuideStep.CULPRIT -> stringResource(R.string.guide_culprit_title)
                    GuideStep.RULED_OUT -> stringResource(R.string.guide_ruled_out_title)
                    GuideStep.EXHAUSTED -> stringResource(R.string.guide_exhausted_title)
                },
                style = MaterialTheme.typography.titleMedium,
                color = onContainer,
            )
            Text(
                when (guide.step) {
                    GuideStep.CAPTURE -> stringResource(R.string.guide_capture_body, appLabel)
                    GuideStep.NOTHING_REFUSED -> stringResource(R.string.guide_nothing_body, appLabel)
                    GuideStep.BASELINE -> stringResource(R.string.guide_baseline_body, appLabel)
                    GuideStep.TESTING -> stringResource(R.string.guide_testing_body, guide.testing, appLabel)
                    GuideStep.CULPRIT -> stringResource(R.string.guide_culprit_body, guide.culprit, appLabel)
                    GuideStep.RULED_OUT -> stringResource(R.string.guide_ruled_out_body, appLabel)
                    GuideStep.EXHAUSTED -> stringResource(R.string.guide_exhausted_body, guide.candidates.size, appLabel)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer,
            )

            // The one number that says the capture is working, so nobody has to guess whether
            // leaving the app open for another minute would help.
            if (guide.step == GuideStep.CAPTURE) {
                Text(
                    pluralStringResource(R.plurals.guide_refused_so_far, refusedSoFar, refusedSoFar),
                    style = MaterialTheme.typography.labelLarge,
                    color = onContainer,
                )
            }
            // Never silent about the cap: a search that quietly tested ten of forty and then said
            // "none of them" would be reporting on names it never looked at.
            if (guide.truncated) {
                Text(
                    stringResource(R.string.guide_truncated, guide.found, guide.candidates.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer,
                )
            }
            // The method's one real weakness, said where it can be acted on rather than buried.
            if (guide.step == GuideStep.BASELINE || guide.step == GuideStep.TESTING) {
                Text(
                    stringResource(R.string.guide_cache_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer,
                )
            }

            when (guide.step) {
                GuideStep.CAPTURE -> {
                    GuideActions {
                        PrimaryAction(
                            text = stringResource(R.string.guide_action_open, appLabel),
                            onClick = onOpenApp,
                            onContainer = onContainer,
                            container = container,
                        )
                        SecondaryAction(
                            text = stringResource(R.string.guide_action_captured),
                            onClick = onCaptured,
                            onContainer = onContainer,
                        )
                    }
                }
                GuideStep.BASELINE, GuideStep.TESTING -> {
                    GuideActions {
                        SecondaryAction(
                            text = stringResource(R.string.guide_action_force_stop),
                            onClick = onAppInfo,
                            onContainer = onContainer,
                        )
                    }
                    GuideActions {
                        PrimaryAction(
                            text = stringResource(R.string.guide_answer_worked),
                            onClick = { onAnswered(true) },
                            onContainer = onContainer,
                            container = container,
                        )
                        SecondaryAction(
                            text = stringResource(R.string.guide_answer_failed),
                            onClick = { onAnswered(false) },
                            onContainer = onContainer,
                        )
                    }
                }
                GuideStep.CULPRIT -> {
                    GuideActions {
                        PrimaryAction(
                            text = stringResource(R.string.guide_culprit_action, appLabel),
                            onClick = onAcceptFix,
                            onContainer = onContainer,
                            container = container,
                        )
                    }
                }
                GuideStep.EXHAUSTED -> {
                    GuideActions {
                        PrimaryAction(
                            text = stringResource(R.string.guide_exhausted_keep),
                            onClick = onKeepAll,
                            onContainer = onContainer,
                            container = container,
                        )
                    }
                }
                GuideStep.NOTHING_REFUSED, GuideStep.RULED_OUT -> Unit
            }

            GuideActions {
                if (guide.step != GuideStep.CAPTURE && guide.step != GuideStep.NOTHING_REFUSED) {
                    SecondaryAction(
                        text = stringResource(R.string.guide_action_restart),
                        onClick = onRestart,
                        onContainer = onContainer,
                    )
                }
                SecondaryAction(
                    text = stringResource(
                        if (done) R.string.guide_action_finish else R.string.guide_action_leave,
                    ),
                    onClick = onLeave,
                    onContainer = onContainer,
                )
            }
        }
    }
}

/** A row of the guide's buttons, wrapping rather than clipping on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideActions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) { content() }
}

/** Something that makes the timeline meaningless, and — where there is one — the way out. */
@Composable
private fun WarningCard(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val spacing = Tokens.spacing
    MalachiCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (action != null && onAction != null) {
                Spacer(Modifier.width(spacing.sm))
                PrimaryAction(
                    text = action,
                    onClick = onAction,
                    onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                )
            }
        }
    }
}

/**
 * The three numbers that decide what kind of problem this is.
 *
 * "Nothing blocked, three with no answer" and "forty blocked, nothing failing" are different
 * investigations, and the difference is legible here before any row is read.
 */
@Composable
private fun SummaryRow(trace: AppTraceState) {
    val spacing = Tokens.spacing
    MalachiCard {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Tally(trace.blocked, stringResource(R.string.diagnose_count_blocked), MaterialTheme.colorScheme.error, Modifier.weight(1f))
            Tally(trace.answered, stringResource(R.string.diagnose_count_answered), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            Tally(trace.stalled, stringResource(R.string.diagnose_count_stalled), MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tally(value: Int, label: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = if (value == 0) MaterialTheme.colorScheme.onSurfaceVariant else color,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One blocked domain and the switch that exempts it here.
 *
 * A switch rather than the block/allow pair the other screens offer, and it is the whole point of
 * the screen: narrowing down which name breaks an app means turning exceptions on and off
 * repeatedly, and a dialog per attempt turns a two-minute experiment into a chore people abandon.
 * Tapping the row itself opens the same scope dialog as everywhere else, for the case where the
 * app needs a whole subdomain tree rather than the one name it happened to ask for.
 */
@Composable
private fun SuspectRow(
    suspect: TraceSuspect,
    exempted: Boolean,
    liveBlocked: Boolean,
    position: CardPosition,
    onToggle: (Boolean) -> Unit,
    onOpenScope: () -> Unit,
) {
    val spacing = Tokens.spacing
    MalachiCard(position = position, onClick = onOpenScope) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    suspect.domain,
                    style = MonoSmall,
                    // No longer blocked reads as no longer blocked, whether that came from this
                    // switch or from a rule written against a parent name.
                    color = if (liveBlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                )
                Text(
                    buildString {
                        append(pluralStringResource(R.plurals.diagnose_queries, suspect.queries, suspect.queries))
                        if (suspect.detail.isNotEmpty() && suspect.source == RuleSource.LIST) {
                            append(" · ")
                            append(stringResource(R.string.verdict_blocked_by_list, suspect.detail))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Switch(checked = exempted, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun EventRow(event: TraceEvent, clock: SimpleDateFormat) {
    val spacing = Tokens.spacing
    val tint = when {
        !event.outcome.isLookup -> MaterialTheme.colorScheme.primary
        event.outcome == TraceOutcome.BLOCKED -> MaterialTheme.colorScheme.error
        event.outcome.stalled -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    MalachiCard {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    !event.outcome.isLookup -> Icons.Filled.Edit
                    event.outcome == TraceOutcome.BLOCKED -> Icons.Filled.Block
                    event.outcome.stalled -> Icons.Filled.HourglassEmpty
                    else -> Icons.Filled.CheckCircle
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        clock.format(Date(event.atMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Text(event.domain, style = MonoSmall, modifier = Modifier.weight(1f))
                }
                Text(outcomeLabel(event), style = MaterialTheme.typography.bodySmall, color = tint)
            }
        }
    }
}

/** One line saying what became of this query, in the terms the user can act on. */
@Composable
private fun outcomeLabel(event: TraceEvent): String {
    val what = when (event.outcome) {
        TraceOutcome.BLOCKED -> when (event.source) {
            RuleSource.LIST -> stringResource(R.string.verdict_blocked_by_list, event.detail)
            RuleSource.APP_RULE -> stringResource(R.string.verdict_blocked_app_rule)
            else -> stringResource(R.string.verdict_blocked_your_rule)
        }
        TraceOutcome.ANSWERED -> stringResource(R.string.trace_answered, event.detail, event.elapsedMs)
        TraceOutcome.UNANSWERED -> stringResource(R.string.trace_unanswered, event.elapsedMs)
        TraceOutcome.DROPPED -> when (event.reason) {
            TraceReason.NETWORK_CHANGED -> stringResource(R.string.trace_dropped_network)
            TraceReason.BUSY -> stringResource(R.string.trace_dropped_busy)
            else -> stringResource(R.string.trace_dropped)
        }
        TraceOutcome.RULE_ALLOWED -> return stringResource(R.string.trace_rule_allowed)
        TraceOutcome.RULE_BLOCKED -> return stringResource(R.string.trace_rule_blocked)
        TraceOutcome.RULE_REMOVED -> return stringResource(R.string.trace_rule_removed)
    }
    return buildString {
        append(what)
        val type = AppTrace.typeLabel(event.type)
        if (type.isNotEmpty()) {
            append(" · ")
            append(type)
        }
        // Only once it means something. "#1" on every row is noise; "#14" is the whole diagnosis.
        if (event.attempt > 1) {
            append(" · #")
            append(event.attempt)
        }
    }
}

private fun filterLabel(filter: TraceFilter) = when (filter) {
    TraceFilter.ALL -> R.string.diagnose_filter_all
    TraceFilter.BLOCKED -> R.string.diagnose_filter_blocked
    TraceFilter.FAILED -> R.string.diagnose_filter_failed
}

/**
 * The session as text on the clipboard.
 *
 * The one thing this app cannot do for somebody is read their phone, and a sideloaded app has no
 * support channel — so being able to paste the whole experiment into a message is what makes a
 * report about a broken app answerable at a distance. Clipboard, at the user's own request; it
 * still never reaches disk.
 */
private fun copyTrace(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("malachi-trace", text))
}
