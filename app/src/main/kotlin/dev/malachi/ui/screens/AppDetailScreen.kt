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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.AppIcon
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.SwitchRow
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
fun AppDetailScreen(vm: MalachiViewModel, packageName: String, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val log by vm.queryLog.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    val label = remember(packageName) { vm.labelFor(packageName) }
    val rules = settings.appRulesFor(packageName)
    // Ranked by how often, not by how recently: "what does this app keep asking for" is the
    // question somebody writing a rule has, and the noisiest domain is rarely the last one.
    val seen = remember(log, packageName) {
        log.records
            .filter { it.packageName == packageName }
            .sortedByDescending { it.count }
            .take(SEEN_LIMIT)
    }

    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

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
                SwitchRow(
                    title = stringResource(R.string.app_detail_filtered),
                    subtitle = stringResource(R.string.app_detail_filtered_subtitle),
                    checked = settings.covers(packageName),
                    onCheckedChange = { vm.setAppCovered(packageName, it) },
                )
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
                                if (vm.setAppRule(draft, packageName, block = true) == null) error = true else draft = ""
                            },
                            enabled = draft.isNotBlank(),
                        ) { Text(stringResource(R.string.action_block_here)) }
                        OutlinedButton(
                            onClick = {
                                if (vm.setAppRule(draft, packageName, block = false) == null) error = true else draft = ""
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

            if (seen.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.app_detail_seen_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(spacing.lg),
                    )
                }
            }

            items(seen, key = { it.domain }) { record ->
                MalachiCard {
                    Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(record.domain, style = MonoSmall)
                            Text(
                                verdictLabel(record.blocked, record.source, record.detail, record.count),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (record.blocked) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = { vm.setAppRule(record.domain, packageName, block = true) }) {
                            Icon(
                                Icons.Filled.Block,
                                contentDescription = stringResource(R.string.action_block_here),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        IconButton(onClick = { vm.setAppRule(record.domain, packageName, block = false) }) {
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
}

/** How many domains the detail screen ranks. The log holds a little more per app. */
private const val SEEN_LIMIT = 50
