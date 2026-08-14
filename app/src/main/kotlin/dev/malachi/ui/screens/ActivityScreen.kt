package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.filter.ListCoverage
import dev.malachi.filter.QueryRecord
import dev.malachi.filter.RuleSource
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.rememberRuleAnnouncer
import dev.malachi.ui.components.ActionChoices
import dev.malachi.ui.components.AppIcon
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.PrimaryAction
import dev.malachi.ui.components.SecondaryAction
import dev.malachi.ui.components.MalachiFilterChip
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.UndoBarHost
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.components.lastSeenLabel
import dev.malachi.ui.components.relativeTime
import dev.malachi.ui.components.rememberUndoBar
import dev.malachi.ui.components.shortDuration
import dev.malachi.ui.theme.MonoSmall
import dev.malachi.ui.theme.Tokens

/** How the list is filtered. Three states, because "what got through" is its own question. */
private enum class ActivityFilter { ALL, BLOCKED, ALLOWED }

/** The two questions this screen answers, which have nothing to do with each other. */
private enum class ActivityTab { LIVE, STATS }

/** How many of the apps that spoke most recently get a shortcut of their own. */
private const val RECENT_APPS = 3

/** How many domains the session's blocked ranking names. */
private const val TOP_DOMAINS = 5

/**
 * Every lookup Malachi has seen, and what it did about it.
 *
 * This is the screen that turns a blocklist from a black box into something you can reason
 * about. An app misbehaving, a tracker no list has caught, a site broken by an over-eager rule —
 * all three look identical from outside, and all three are one tap from a fix here.
 *
 * The screen has two halves with deliberately different memories, and they are now two tabs
 * rather than one long scroll. The **live log** is a window onto the last few hundred lookups: it
 * names domains, it lives in the tunnel's process, and it is gone when the filter stops — which
 * is what makes it acceptable for an app to hold a list of the sites its owner's phone has been
 * visiting. The **statistics** survive restarts but are only arithmetic: counts per app per day,
 * never a domain, so nothing about where somebody has been can be reconstructed from what is
 * written down. Stacking them put a screenful of history in front of the question people
 * actually arrive with, which is what just happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(vm: MalachiViewModel, onBack: () -> Unit, onOpenApp: (String) -> Unit) {
    val log by vm.queryLog.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    val undo = rememberUndoBar()
    val announcer = rememberRuleAnnouncer(undo)

    // Read on every resume rather than once when the screen opens: coming back from another app
    // is exactly when these numbers have moved and nothing has recomposed.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refreshStats()
        onPauseOrDispose { }
    }

    var tab by remember { mutableStateOf(ActivityTab.LIVE) }
    var filter by remember { mutableStateOf(ActivityFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<QueryRecord?>(null) }

    // The clock the whole list is read against, taken once per snapshot rather than per row: two
    // rows of one list must not disagree about what "2 min ago" means.
    val now = remember(log) { System.currentTimeMillis() }

    val visible = remember(log, filter, query, vm) {
        log.records.asSequence()
            .filter {
                when (filter) {
                    ActivityFilter.ALL -> true
                    ActivityFilter.BLOCKED -> it.blocked
                    ActivityFilter.ALLOWED -> !it.blocked
                }
            }
            // Searching by app name as well as by domain: "whatsapp" used to match nothing at
            // all, which is not what anybody expects of a field on a screen full of app names.
            .filter {
                query.isBlank() ||
                    it.domain.contains(query, true) ||
                    vm.labelFor(it.packageName).contains(query, true)
            }
            .toList()
    }

    // The apps that spoke most recently, which is the closest thing to "the app you just used"
    // that can be known without asking for usage access. byApp() already orders them that way.
    val recent = remember(log) {
        log.byApp().filter { it.first != null }.take(RECENT_APPS)
    }
    val topBlocked = remember(log) { log.topBlockedDomains(TOP_DOMAINS) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            MalachiTopBar(stringResource(R.string.nav_activity), onBack) {
                if (tab == ActivityTab.LIVE) {
                    IconButton(onClick = vm::clearQueryLog) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screen, vertical = spacing.xs),
            ) {
                ActivityTab.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = tab == option,
                        onClick = { tab = option },
                        shape = SegmentedButtonDefaults.itemShape(index, ActivityTab.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        icon = {},
                    ) {
                        Text(
                            stringResource(
                                if (option == ActivityTab.LIVE) R.string.activity_tab_live else R.string.activity_tab_stats,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (tab == ActivityTab.STATS) {
                    // Above the persisted statistics, and from the live log rather than from
                    // disk: what is on disk is counts per app and deliberately holds no domain
                    // at all, so this is the only place the question can be asked — and it is
                    // the one ranking that names the tracker instead of whatever is hosting it.
                    val topDomains = topBlocked
                    if (topDomains.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = stringResource(R.string.stats_top_domains),
                                supporting = stringResource(R.string.stats_top_domains_hint),
                            )
                        }
                        item {
                            CardGroup {
                                topDomains.forEachIndexed { index, (domain, count) ->
                                    MalachiCard(
                                        position = cardPosition(index, topDomains.size),
                                        // Straight to who has been asking for it, which is the
                                        // next question every time.
                                        onClick = { query = domain; tab = ActivityTab.LIVE },
                                    ) {
                                        Row(
                                            Modifier.padding(spacing.md),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(domain, style = MonoSmall, modifier = Modifier.weight(1f))
                                            Spacer(Modifier.width(spacing.sm))
                                            Text(
                                                count.toString(),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { StatsPanel(vm, onOpenApp) }
                    return@LazyColumn
                }

                if (recent.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.activity_recent_title),
                            supporting = stringResource(R.string.activity_recent_hint),
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            recent.forEach { (packageName, records) ->
                                RecentApp(
                                    packageName = packageName!!,
                                    records = records,
                                    vm = vm,
                                    nowMs = now,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpenApp(packageName) },
                                )
                            }
                            // Fewer than three apps have been seen: the remaining width is spacer
                            // rather than three cards stretched across it, which would make one
                            // app look like a deliberate full-width feature.
                            repeat(RECENT_APPS - recent.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.activity_live_title),
                        supporting = stringResource(R.string.activity_live_hint),
                    )
                }

                if (!settings.queryLogEnabled) {
                    item { QueryLogOffCard(onEnable = { vm.setQueryLogEnabled(true) }) }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        MalachiFilterChip(
                            selected = filter == ActivityFilter.ALL,
                            onClick = { filter = ActivityFilter.ALL },
                            label = { Text(stringResource(R.string.activity_filter_all)) },
                        )
                        MalachiFilterChip(
                            selected = filter == ActivityFilter.BLOCKED,
                            onClick = { filter = ActivityFilter.BLOCKED },
                            label = { Text(stringResource(R.string.activity_filter_blocked)) },
                        )
                        MalachiFilterChip(
                            selected = filter == ActivityFilter.ALLOWED,
                            onClick = { filter = ActivityFilter.ALLOWED },
                            label = { Text(stringResource(R.string.activity_filter_allowed)) },
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.activity_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    )
                }

                if (visible.isEmpty()) {
                    item {
                        Text(
                            stringResource(
                                if (log.records.isEmpty()) R.string.activity_empty else R.string.activity_no_matches,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(spacing.lg),
                        )
                    }
                }

                items(visible, key = { it.packageName.orEmpty() + "|" + it.domain }) { record ->
                    QueryRow(record, vm, now) { selected = record }
                }
            }
        }

        UndoBarHost(undo, Modifier.align(Alignment.BottomCenter).padding(spacing.md))
    }

    selected?.let { record ->
        DomainActions(
            record = record,
            appLabel = vm.labelFor(record.packageName),
            coverage = remember(engine, record.domain) { engine.listsCovering(record.domain) },
            onDismiss = { selected = null },
            onBlockEverywhere = {
                announcer.announce(vm.addUserRule(record.domain, block = true), blocked = true)
                selected = null
            },
            onAllowEverywhere = {
                announcer.announce(vm.addUserRule(record.domain, block = false), blocked = false)
                selected = null
            },
            onBlockHere = {
                record.packageName?.let {
                    announcer.announce(
                        vm.setAppRule(record.domain, it, block = true),
                        blocked = true,
                        appLabel = vm.labelFor(it),
                    )
                }
                selected = null
            },
            onAllowHere = {
                record.packageName?.let {
                    announcer.announce(
                        vm.setAppRule(record.domain, it, block = false),
                        blocked = false,
                        appLabel = vm.labelFor(it),
                    )
                }
                selected = null
            },
            onOpenApp = { pkg -> selected = null; onOpenApp(pkg) },
        )
    }
}

/** The card that says nothing is being recorded, and the one button that changes it. */
@Composable
internal fun QueryLogOffCard(onEnable: () -> Unit) {
    val spacing = Tokens.spacing
    MalachiCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.activity_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(spacing.sm))
            PrimaryAction(
                text = stringResource(R.string.action_enable),
                onClick = onEnable,
                onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
                container = MaterialTheme.colorScheme.secondaryContainer,
            )
        }
    }
}

/**
 * One of the apps that has just been resolving something, as a shortcut to its own screen.
 *
 * This is the whole answer to the commonest errand in the app: something broke in the app you
 * were using a moment ago, and you want to see what it asked for. There is no way to know which
 * app a person just *looked at* — that needs usage access, which is a large permission for a
 * guess — but which apps just *spoke* is already in the log, and for this errand they are the
 * same three apps.
 */
@Composable
private fun RecentApp(
    packageName: String,
    records: List<QueryRecord>,
    vm: MalachiViewModel,
    nowMs: Long,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val spacing = Tokens.spacing
    val blocked = remember(records) { records.filter { it.blocked }.sumOf { it.count } }
    val total = remember(records) { records.sumOf { it.count } }
    val lastSeen = remember(records) { records.maxOf { it.lastSeenMs } }
    MalachiCard(modifier = modifier, onClick = onClick) {
        Column(
            Modifier.padding(spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            AppIcon(packageName, vm.inventory, size = 36.dp)
            Text(
                vm.labelFor(packageName),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.stats_app_detail, blocked, total),
                style = MaterialTheme.typography.labelSmall,
                color = if (blocked > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            // Which of these three is *the* app you just used, in the only terms that can answer
            // it: one of them spoke eight seconds ago and the others four minutes ago.
            Text(
                relativeTime(lastSeen, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QueryRow(record: QueryRecord, vm: MalachiViewModel, nowMs: Long, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    MalachiCard(onClick = onClick) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            if (record.packageName != null) {
                AppIcon(record.packageName, vm.inventory, size = 32.dp)
            } else {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    // Was tinted `surfaceVariant`, which against a white card is 1.18:1 — the
                    // glyph marking a lookup nobody could attribute was, in practice, not drawn.
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(record.domain, style = MonoSmall)
                Text(
                    buildString {
                        append(record.packageName?.let { vm.labelFor(it) } ?: stringResource(R.string.activity_system))
                        append(" · ")
                        append(verdictLabel(record.blocked, record.source, record.detail, record.count))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Its own line rather than a corner of the row: "last seen 10 sec. ago" is the
                // difference between the lookup that just broke something and the forty before
                // it, and it does not fit beside a verdict that already names a list.
                //
                // A domain being hammered says that instead. It is the more urgent of the two —
                // "when" is answered by "18 times in 40 s" as well — and it is the only line on
                // this screen that has ever been able to tell a retry loop from a busy app.
                if (record.retrying) {
                    Text(
                        stringResource(R.string.verdict_retrying, record.count, shortDuration(record.spanMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        lastSeenLabel(record.lastSeenMs, nowMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(spacing.sm))
            Icon(
                if (record.blocked) Icons.Filled.Block else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (record.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** "Blocked by AdGuard DNS filter · seen 12 times", in one line the user can act on. */
@Composable
internal fun verdictLabel(blocked: Boolean, source: RuleSource, detail: String, count: Int): String {
    val verdict = when {
        blocked && source == RuleSource.LIST -> stringResource(R.string.verdict_blocked_by_list, detail)
        blocked && source == RuleSource.APP_RULE -> stringResource(R.string.verdict_blocked_app_rule)
        blocked -> stringResource(R.string.verdict_blocked_your_rule)
        source == RuleSource.APP_RULE -> stringResource(R.string.verdict_allowed_app_rule)
        source == RuleSource.USER_RULE -> stringResource(R.string.verdict_allowed_your_rule)
        source == RuleSource.LIST -> stringResource(R.string.verdict_allowed_exception, detail)
        else -> stringResource(R.string.verdict_allowed)
    }
    return "$verdict · " + pluralStringResource(R.plurals.verdict_seen, count, count)
}

/** The things worth doing about a domain, two of which no global list can express. */
@Composable
private fun DomainActions(
    record: QueryRecord,
    appLabel: String,
    coverage: ListCoverage,
    onDismiss: () -> Unit,
    onBlockEverywhere: () -> Unit,
    onAllowEverywhere: () -> Unit,
    onBlockHere: () -> Unit,
    onAllowHere: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.domain, style = MonoSmall) },
        text = {
            // Four things to do, each one a button. Stacked as text they read as a paragraph
            // somebody forgot to format rather than as the list of choices they are.
            ActionChoices {
                // What the lists actually say about this name, before the buttons that overrule
                // them. A domain four maintainers independently call a tracker is a different
                // decision from one a single list flagged — and the verdict line can only ever
                // name the one list that happened to answer first.
                if (coverage.blocking.isNotEmpty() || coverage.allowing.isNotEmpty()) {
                    Text(
                        buildString {
                            if (coverage.blocking.isNotEmpty()) {
                                append(
                                    pluralStringResource(
                                        R.plurals.domain_on_lists,
                                        coverage.blocking.size,
                                        coverage.blocking.size,
                                    ),
                                )
                                append(": ")
                                append(coverage.blocking.joinToString(", "))
                            }
                            if (coverage.allowing.isNotEmpty()) {
                                if (isNotEmpty()) append("\n")
                                append(stringResource(R.string.domain_excepted_by))
                                append(": ")
                                append(coverage.allowing.joinToString(", "))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SecondaryAction(
                    text = stringResource(R.string.action_block_everywhere),
                    onClick = onBlockEverywhere,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryAction(
                    text = stringResource(R.string.action_allow_everywhere),
                    onClick = onAllowEverywhere,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (record.packageName != null) {
                    SecondaryAction(
                        text = stringResource(R.string.action_block_in_app, appLabel),
                        onClick = onBlockHere,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryAction(
                        text = stringResource(R.string.action_allow_in_app, appLabel),
                        onClick = onAllowHere,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // The other question a row about an app raises, and the only one that was
                    // unreachable from here: everything this app has been resolving, and the
                    // rules that already apply to it.
                    SecondaryAction(
                        text = stringResource(R.string.action_open_app_detail, appLabel),
                        onClick = { onOpenApp(record.packageName) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
