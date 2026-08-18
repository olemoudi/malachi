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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.data.DomainInput
import dev.malachi.filter.QueryRecord
import dev.malachi.filter.RuleSource
import dev.malachi.filter.Verdict
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.rememberRuleAnnouncer
import dev.malachi.ui.components.AppIcon
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.ChoiceRow
import dev.malachi.ui.components.MalachiFilterChip
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.NavRow
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.SwitchRow
import dev.malachi.ui.components.UndoBarHost
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.components.lastSeenLabel
import dev.malachi.ui.components.rememberUndoBar
import dev.malachi.ui.components.shortDuration
import dev.malachi.ui.theme.MonoSmall
import dev.malachi.ui.theme.Tokens

/**
 * One app: whether it is filtered at all, the rules that apply only to it, and what it has
 * actually been resolving.
 *
 * The last part is the point. A per-app rule is only worth writing when you can see the domain
 * that needs it, and "this app, this domain" is a question no global blocklist can answer — it
 * is the difference between switching an entire app out of the filter to make it work again and
 * exempting the one hostname that was breaking it.
 */
@Composable
fun AppDetailScreen(
    vm: MalachiViewModel,
    packageName: String,
    onBack: () -> Unit,
    onDiagnose: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val log by vm.queryLog.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    val engine by vm.engine.collectAsStateWithLifecycle()

    val label = remember(packageName) { vm.labelFor(packageName) }
    val rules = settings.appRulesFor(packageName)

    // Saveable: this screen is where a per-app diagnosis is run from, and the errand is
    // iterative — sort by most-asked-for, go and look at something, come back. Coming back to
    // "most recent" every time is the app forgetting the question.
    var order by rememberSaveable { mutableStateOf(SeenOrder.RECENT) }
    val seen = remember(log, packageName, order) {
        log.records
            .asSequence()
            .filter { it.packageName == packageName }
            .filter { order != SeenOrder.BLOCKED || it.blocked }
            .sortedWith(
                when (order) {
                    // Most recent first, and it is the default for a reason: somebody arrives
                    // here because something broke a moment ago, and the domain that broke it was
                    // asked for once or twice — buried, under frequency, beneath forty analytics
                    // names the app has been repeating happily all day.
                    SeenOrder.RECENT -> compareByDescending { it.lastSeenMs }
                    SeenOrder.FREQUENT -> compareByDescending { it.count }
                    SeenOrder.BLOCKED -> compareByDescending { it.lastSeenMs }
                },
            )
            .take(SEEN_LIMIT)
            .toList()
    }
    val now = remember(log) { System.currentTimeMillis() }

    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingRule?>(null) }
    val undo = rememberUndoBar()
    val announcer = rememberRuleAnnouncer(undo)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            MalachiTopBar(label, onBack)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                item {
                    MalachiCard {
                        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(packageName, vm.inventory, size = 48.dp)
                            Spacer(Modifier.width(spacing.md))
                            Column {
                                Text(label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    packageName,
                                    style = MonoSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item {
                    CardGroup {
                        SwitchRow(
                            title = stringResource(R.string.app_detail_filtered),
                            subtitle = stringResource(R.string.app_detail_filtered_subtitle),
                            checked = settings.covers(packageName),
                            onCheckedChange = { vm.setAppCovered(packageName, it) },
                            position = cardPosition(0, 3),
                        )
                        // The list below says what this app has resolved; this says what happened
                        // to each lookup, in order. It is the answer to the one thing this screen
                        // cannot show — an app that hangs, where the domain that breaks it was
                        // asked for once and is buried under forty the app repeats all day.
                        NavRow(
                            icon = Icons.Filled.Troubleshoot,
                            title = stringResource(R.string.app_detail_diagnose),
                            subtitle = stringResource(R.string.app_detail_diagnose_hint),
                            onClick = onDiagnose,
                            position = cardPosition(1, 3),
                        )
                        // "Is this app eating my battery" is the question that brings people to
                        // a screen like this, and it is one Malachi cannot answer — a DNS lookup
                        // is not a measurement of power, and a number invented from one would be
                        // a confident lie. What it can do is say how much the app talks, and then
                        // point at the part of the system that really does keep the figures.
                        NavRow(
                            icon = Icons.Filled.Info,
                            title = stringResource(R.string.app_detail_system_info),
                            subtitle = stringResource(R.string.app_detail_system_info_hint),
                            onClick = { vm.openAppInfo(packageName) },
                            position = cardPosition(2, 3),
                        )
                    }
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.app_detail_rules_title),
                        supporting = stringResource(R.string.app_detail_rules_hint),
                    )
                }

                item {
                    Column {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it; error = false },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = error,
                            label = { Text(stringResource(R.string.rules_domain_label)) },
                            supportingText = if (error) {
                                { Text(stringResource(R.string.rules_domain_invalid)) }
                            } else {
                                null
                            },
                        )
                        Spacer(Modifier.padding(top = spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Button(
                                onClick = {
                                    val edit = vm.setAppRule(draft, packageName, block = true)
                                    announcer.announce(edit, blocked = true, appLabel = label)
                                    if (edit == null) error = true else draft = ""
                                },
                                enabled = draft.isNotBlank(),
                            ) { Text(stringResource(R.string.action_block_here)) }
                            OutlinedButton(
                                onClick = {
                                    val edit = vm.setAppRule(draft, packageName, block = false)
                                    announcer.announce(edit, blocked = false, appLabel = label)
                                    if (edit == null) error = true else draft = ""
                                },
                                enabled = draft.isNotBlank(),
                            ) { Text(stringResource(R.string.action_allow_here)) }
                        }
                    }
                }

                items(rules, key = { it.domain + it.block }) { rule ->
                    MalachiCard {
                        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (rule.block) Icons.Filled.Block else Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = if (rule.block) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(spacing.md))
                            Text(rule.domain, style = MonoSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.removeAppRule(rule.domain, packageName) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.app_detail_seen_title),
                        supporting = stringResource(R.string.app_detail_seen_hint),
                    )
                }

                // Before the list rather than after it: when the log is off there is nothing below
                // this to explain, and the screen used to say "nothing seen from this app yet" —
                // which is a different statement, sends the reader off to go and use the app, and is
                // not true.
                if (!settings.queryLogEnabled) {
                    item { QueryLogOffCard(onEnable = { vm.setQueryLogEnabled(true) }) }
                } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            SeenOrder.entries.forEach { option ->
                                MalachiFilterChip(
                                    selected = order == option,
                                    onClick = { order = option },
                                    label = { Text(stringResource(seenOrderLabel(option))) },
                                )
                            }
                        }
                    }
                }

                if (seen.isEmpty() && settings.queryLogEnabled) {
                    item {
                        Text(
                            stringResource(
                                if (order == SeenOrder.BLOCKED) {
                                    R.string.app_detail_seen_none_blocked
                                } else {
                                    R.string.app_detail_seen_empty
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(spacing.lg),
                        )
                    }
                }

                items(seen, key = { it.domain }) { record ->
                    // What the filter would do with this name *now*, not only what it did when the
                    // lookup happened: a rule written a moment ago must be visible on the line it was
                    // written from, including when it was written against a parent name.
                    val verdict = remember(engine, record) {
                        effectiveVerdict(record, engine.decide(record.domain, packageName))
                    }
                    MalachiCard {
                        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(record.domain, style = MonoSmall)
                                Text(
                                    liveVerdictLabel(record, verdict),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (verdict.blocked) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                if (record.retrying) {
                                    Text(
                                        stringResource(
                                            R.string.verdict_retrying,
                                            record.count,
                                            shortDuration(record.spanMs),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else {
                                    Text(
                                        lastSeenLabel(record.lastSeenMs, now),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { pending = PendingRule(record.domain, block = true) }) {
                                Icon(
                                    Icons.Filled.Block,
                                    contentDescription = stringResource(R.string.action_block_here),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            IconButton(onClick = { pending = PendingRule(record.domain, block = false) }) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = stringResource(R.string.action_allow_here),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }

        UndoBarHost(undo, Modifier.align(Alignment.BottomCenter).padding(spacing.md))
    }

    pending?.let { rule ->
        DomainScopeDialog(
            domain = rule.domain,
            appLabel = label,
            block = rule.block,
            onDismiss = { pending = null },
            onConfirm = { domain ->
                announcer.announce(
                    vm.setAppRule(domain, packageName, block = rule.block),
                    blocked = rule.block,
                    appLabel = label,
                )
                pending = null
            },
        )
    }
}

/** How the domains an app has asked for are ordered, and which of them are shown at all. */
private enum class SeenOrder { RECENT, FREQUENT, BLOCKED }

private fun seenOrderLabel(order: SeenOrder) = when (order) {
    SeenOrder.RECENT -> R.string.app_detail_order_recent
    SeenOrder.FREQUENT -> R.string.app_detail_order_frequent
    SeenOrder.BLOCKED -> R.string.app_detail_order_blocked
}

/** A rule the user has asked for and not yet chosen the reach of. */
private data class PendingRule(val domain: String, val block: Boolean)

/**
 * What the line should say.
 *
 * The query log records what happened at the time, and that is the honest thing to show — right
 * up until the user writes a rule, at which point the line they acted on would go on reporting
 * the block they came here to remove. So a verdict that comes from a rule *they* wrote replaces
 * the recorded one; a list's verdict is left alone as the history it is.
 */
internal fun effectiveVerdict(record: QueryRecord, current: Verdict): Verdict =
    if (current.source == RuleSource.APP_RULE || current.source == RuleSource.USER_RULE) {
        current
    } else {
        Verdict(record.blocked, record.source, record.detail)
    }

/**
 * The verdict as one line, naming the rule when it isn't the name on the row.
 *
 * Without that, blocking `bbva.es` turns eight lines red at once and none of them says why —
 * which reads as the app having done something of its own accord.
 */
@Composable
private fun liveVerdictLabel(record: QueryRecord, verdict: Verdict): String {
    val ownRule = verdict.source == RuleSource.APP_RULE || verdict.source == RuleSource.USER_RULE
    if (!ownRule || verdict.detail.isEmpty() || verdict.detail == record.domain) {
        return verdictLabel(verdict.blocked, verdict.source, verdict.detail, record.count)
    }
    val head = stringResource(
        if (verdict.blocked) R.string.verdict_blocked_rule_for else R.string.verdict_allowed_rule_for,
        verdict.detail,
    )
    return "$head · " + pluralStringResource(R.plurals.verdict_seen, record.count, record.count)
}

/**
 * How far a rule should reach.
 *
 * The button that opened this already said block or allow; the only question left is which name
 * it is written against. Matching is by suffix, so choosing a parent *is* the wildcard — a rule
 * for `bbva.es` catches `movil.bbva.es` and everything else under it — and each row says in
 * words what it will catch, using a name this app really asked for as the example. The field
 * underneath is for anything the parents don't cover, and is validated like any other rule.
 */
@Composable
internal fun DomainScopeDialog(
    domain: String,
    appLabel: String,
    block: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val spacing = Tokens.spacing
    val scopes = remember(domain) { DomainInput.scopes(domain) }
    var choice by remember(domain) { mutableStateOf(scopes.firstOrNull() ?: domain) }
    var invalid by remember(domain) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (block) R.string.rule_scope_title_block else R.string.rule_scope_title_allow,
                    appLabel,
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.rule_scope_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = spacing.sm))
                scopes.forEachIndexed { index, scope ->
                    ChoiceRow(
                        title = scope,
                        subtitle = if (index == 0) {
                            stringResource(R.string.rule_scope_exact)
                        } else {
                            stringResource(R.string.rule_scope_covers, domain)
                        },
                        selected = choice == scope,
                        onSelect = { choice = scope; invalid = false },
                        position = cardPosition(index, scopes.size),
                    )
                    Spacer(Modifier.padding(top = spacing.xs))
                }
                Spacer(Modifier.padding(top = spacing.xs))
                OutlinedTextField(
                    value = choice,
                    onValueChange = { choice = it; invalid = false },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = invalid,
                    label = { Text(stringResource(R.string.rules_domain_label)) },
                    supportingText = if (invalid) {
                        { Text(stringResource(R.string.rules_domain_invalid)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = DomainInput.parse(choice)
                    if (parsed == null) invalid = true else onConfirm(parsed)
                },
            ) {
                Text(stringResource(if (block) R.string.action_block else R.string.action_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** How many domains the detail screen ranks. The log holds a little more per app. */
private const val SEEN_LIMIT = 50
