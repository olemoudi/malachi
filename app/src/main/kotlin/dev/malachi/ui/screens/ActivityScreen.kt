package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.filter.QueryRecord
import dev.malachi.filter.RuleSource
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.AppIcon
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.theme.MonoSmall
import dev.malachi.ui.theme.Tokens

/** How the list is filtered. Three states, because "what got through" is its own question. */
private enum class ActivityFilter { ALL, BLOCKED, ALLOWED }

/**
 * Every lookup Malachi has seen, and what it did about it.
 *
 * This is the screen that turns a blocklist from a black box into something you can reason
 * about. An app misbehaving, a tracker no list has caught, a site broken by an over-eager rule —
 * all three look identical from outside, and all three are one tap from a fix here.
 *
 * Nothing on this screen is stored anywhere. The records live in the tunnel's process and are
 * gone when it stops, which is what makes it acceptable for an app to keep a list of the
 * domains its owner's phone has been visiting.
 */
@Composable
fun ActivityScreen(vm: MalachiViewModel, onBack: () -> Unit) {
    val log by vm.queryLog.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    var filter by remember { mutableStateOf(ActivityFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<QueryRecord?>(null) }

    val visible = remember(log, filter, query) {
        log.records.asSequence()
            .filter {
                when (filter) {
                    ActivityFilter.ALL -> true
                    ActivityFilter.BLOCKED -> it.blocked
                    ActivityFilter.ALLOWED -> !it.blocked
                }
            }
            .filter { query.isBlank() || it.domain.contains(query, true) }
            .toList()
    }

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.nav_activity), onBack) {
            IconButton(onClick = vm::clearQueryLog) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (!settings.queryLogEnabled) {
                item {
                    MalachiCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.activity_disabled),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.setQueryLogEnabled(true) }) {
                                Text(stringResource(R.string.action_enable))
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    FilterChip(
                        selected = filter == ActivityFilter.ALL,
                        onClick = { filter = ActivityFilter.ALL },
                        label = { Text(stringResource(R.string.activity_filter_all)) },
                    )
                    FilterChip(
                        selected = filter == ActivityFilter.BLOCKED,
                        onClick = { filter = ActivityFilter.BLOCKED },
                        label = { Text(stringResource(R.string.activity_filter_blocked)) },
                    )
                    FilterChip(
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
                QueryRow(record, vm) { selected = record }
            }
        }
    }

    selected?.let { record ->
        DomainActions(
            record = record,
            appLabel = vm.labelFor(record.packageName),
            onDismiss = { selected = null },
            onBlockEverywhere = { vm.addUserRule(record.domain, block = true); selected = null },
            onAllowEverywhere = { vm.addUserRule(record.domain, block = false); selected = null },
            onBlockHere = {
                record.packageName?.let { vm.setAppRule(record.domain, it, block = true) }
                selected = null
            },
            onAllowHere = {
                record.packageName?.let { vm.setAppRule(record.domain, it, block = false) }
                selected = null
            },
        )
    }
}

@Composable
private fun QueryRow(record: QueryRecord, vm: MalachiViewModel, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    MalachiCard(onClick = onClick) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            if (record.packageName != null) {
                AppIcon(record.packageName, vm.inventory, size = 32.dp)
            } else {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surfaceVariant,
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
            }
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

/** The four things worth doing about a domain, two of which no global list can express. */
@Composable
private fun DomainActions(
    record: QueryRecord,
    appLabel: String,
    onDismiss: () -> Unit,
    onBlockEverywhere: () -> Unit,
    onAllowEverywhere: () -> Unit,
    onBlockHere: () -> Unit,
    onAllowHere: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.domain, style = MonoSmall) },
        text = {
            Column {
                TextButton(onClick = onBlockEverywhere, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_block_everywhere), Modifier.fillMaxWidth())
                }
                TextButton(onClick = onAllowEverywhere, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_allow_everywhere), Modifier.fillMaxWidth())
                }
                if (record.packageName != null) {
                    TextButton(onClick = onBlockHere, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_block_in_app, appLabel), Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onAllowHere, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_allow_in_app, appLabel), Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
