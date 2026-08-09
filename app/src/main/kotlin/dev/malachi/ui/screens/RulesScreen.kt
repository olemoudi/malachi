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
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.theme.MonoSmall
import dev.malachi.ui.theme.Tokens

/**
 * The rules the user wrote, which always beat the lists.
 *
 * That precedence is the whole reason this screen exists: a downloaded list is somebody else's
 * judgement about a domain, and the only way to disagree with it is to be able to say so in a
 * way that can't be overwritten by tomorrow's refresh.
 */
@Composable
fun RulesScreen(vm: MalachiViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    val blocked = remember(settings.userBlocked) { settings.userBlocked.sorted() }
    val allowed = remember(settings.userAllowed) { settings.userAllowed.sorted() }

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.nav_rules), onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                SectionHeader(
                    title = stringResource(R.string.rules_add_title),
                    supporting = stringResource(R.string.rules_add_hint),
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
                            onClick = { if (vm.addUserRule(draft, block = true) == null) error = true else draft = "" },
                            enabled = draft.isNotBlank(),
                        ) { Text(stringResource(R.string.action_block)) }
                        OutlinedButton(
                            onClick = { if (vm.addUserRule(draft, block = false) == null) error = true else draft = "" },
                            enabled = draft.isNotBlank(),
                        ) { Text(stringResource(R.string.action_allow)) }
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.rules_blocked_title),
                    supporting = stringResource(R.string.rules_blocked_hint),
                )
            }
            if (blocked.isEmpty()) item { EmptyNote(stringResource(R.string.rules_blocked_empty)) }
            items(blocked, key = { "b-$it" }) { domain ->
                RuleRow(domain, blocking = true) { vm.removeUserRule(domain) }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.rules_allowed_title),
                    supporting = stringResource(R.string.rules_allowed_hint),
                )
            }
            if (allowed.isEmpty()) item { EmptyNote(stringResource(R.string.rules_allowed_empty)) }
            items(allowed, key = { "a-$it" }) { domain ->
                RuleRow(domain, blocking = false) { vm.removeUserRule(domain) }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.rules_per_app_title),
                    supporting = stringResource(R.string.rules_per_app_hint),
                )
            }
            if (settings.appRules.isEmpty()) item { EmptyNote(stringResource(R.string.rules_per_app_empty)) }
            items(settings.appRules, key = { it.packageName + "|" + it.domain }) { rule ->
                MalachiCard {
                    Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (rule.block) Icons.Filled.Block else Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = if (rule.block) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(rule.domain, style = MonoSmall)
                            Text(
                                vm.labelFor(rule.packageName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.removeAppRule(rule.domain, rule.packageName) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(domain: String, blocking: Boolean, onDelete: () -> Unit) {
    val spacing = Tokens.spacing
    MalachiCard {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (blocking) Icons.Filled.Block else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Text(domain, style = MonoSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Tokens.spacing.lg),
    )
}
