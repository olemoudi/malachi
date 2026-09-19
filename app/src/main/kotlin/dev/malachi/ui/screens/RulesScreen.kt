package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.data.BulkRules
import dev.malachi.data.DomainInput
import dev.malachi.filter.DomainCheck
import dev.malachi.filter.DomainChecker
import dev.malachi.filter.RuleSource
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.rememberRuleAnnouncer
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.UndoBarHost
import dev.malachi.ui.components.rememberUndoBar
import dev.malachi.ui.components.MalachiIcons
import dev.malachi.ui.theme.MonoSmall
import dev.malachi.ui.theme.Tokens

/**
 * The rules the user wrote, which always beat the lists.
 *
 * That precedence is the whole reason this screen exists: a downloaded list is somebody else's
 * judgement about a domain, and the only way to disagree with it is to be able to say so in a
 * way that can't be overwritten by tomorrow's refresh.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(vm: MalachiViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    // Saveable: this is text somebody typed, and every search box in the app survives a rotation.
    var draft by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    // The last question asked of the checker, kept as the text that was typed so the answer is
    // re-read from the live filter: a rule written from the same field has to show on it at once.
    var checked by rememberSaveable { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    val undo = rememberUndoBar()
    val announcer = rememberRuleAnnouncer(undo)
    val engine by vm.engine.collectAsStateWithLifecycle()
    val check = remember(checked, engine, settings) { checked?.let { DomainChecker.check(it, engine, settings) } }
    val resources = LocalContext.current.resources

    val blocked = remember(settings.userBlocked) { settings.userBlocked.sorted() }
    val allowed = remember(settings.userAllowed) { settings.userAllowed.sorted() }

    Box(Modifier.fillMaxSize()) {
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Button(
                                onClick = {
                                    val edit = vm.addUserRule(draft, block = true)
                                    announcer.announce(edit, blocked = true)
                                    if (edit == null) error = true else draft = ""
                                },
                                enabled = draft.isNotBlank(),
                            ) { Text(stringResource(R.string.action_block)) }
                            OutlinedButton(
                                onClick = {
                                    val edit = vm.addUserRule(draft, block = false)
                                    announcer.announce(edit, blocked = false)
                                    if (edit == null) error = true else draft = ""
                                },
                                enabled = draft.isNotBlank(),
                            ) { Text(stringResource(R.string.action_allow)) }
                            // Before writing a rule, the question that usually comes first: what does
                            // the filter do with this name now, and why. The field is left as it was,
                            // so the answer can be followed by a rule without typing it twice.
                            TextButton(
                                onClick = {
                                    if (DomainInput.parse(draft) == null) error = true else checked = draft
                                },
                                enabled = draft.isNotBlank(),
                            ) { Text(stringResource(R.string.action_check)) }
                        }
                        TextButton(onClick = { pasting = true }) { Text(stringResource(R.string.rules_paste_many)) }
                    }
                }

                check?.let { result ->
                    item(key = "check") {
                        CheckResultCard(result, vm, onClose = { checked = null })
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
                    RuleRow(domain, blocking = true) { announcer.announceRemoved(vm.removeUserRule(domain)) }
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.rules_allowed_title),
                        supporting = stringResource(R.string.rules_allowed_hint),
                    )
                }
                if (allowed.isEmpty()) item { EmptyNote(stringResource(R.string.rules_allowed_empty)) }
                items(allowed, key = { "a-$it" }) { domain ->
                    RuleRow(domain, blocking = false) { announcer.announceRemoved(vm.removeUserRule(domain)) }
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
                                if (rule.block) MalachiIcons.Block else Icons.Filled.CheckCircle,
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
                            IconButton(onClick = { announcer.announceRemoved(vm.removeAppRule(rule.domain, rule.packageName)) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
        }

        UndoBarHost(undo, Modifier.align(Alignment.BottomCenter).padding(spacing.md))
    }

    if (pasting) {
        PasteRulesDialog(
            onDismiss = { pasting = false },
            onConfirm = { domains, block ->
                vm.addUserRules(domains, block)?.let { edit ->
                    undo.show(
                        resources.getQuantityString(
                            if (block) R.plurals.rules_pasted_blocked else R.plurals.rules_pasted_allowed,
                            domains.size,
                            domains.size,
                        ),
                        edit.undo,
                    )
                }
                pasting = false
            },
        )
    }
}

/**
 * What the filter does with one name, and every reason it has.
 *
 * The same engine the tunnel asks, so the answer is not a description of the rules but the rules
 * themselves: a verdict, the one thing that decided it, every list with an opinion, and the per-app
 * rules that would answer differently inside a particular app.
 */
@Composable
private fun CheckResultCard(result: DomainCheck, vm: MalachiViewModel, onClose: () -> Unit) {
    val spacing = Tokens.spacing
    val verdict = result.verdict
    val container = if (verdict.blocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val onContainer = if (verdict.blocked) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    MalachiCard(color = container) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (verdict.blocked) MalachiIcons.Block else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (verdict.blocked) R.string.check_blocked else R.string.check_allowed),
                        style = MaterialTheme.typography.titleMedium,
                        color = onContainer,
                    )
                    Text(result.domain, style = MonoSmall, color = onContainer)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = onContainer)
                }
            }
            Text(checkReason(result), style = MaterialTheme.typography.bodyMedium, color = onContainer)
            val coverage = result.coverage
            if (coverage.blocking.isNotEmpty()) {
                Text(
                    pluralStringResource(
                        R.plurals.check_lists_blocking,
                        coverage.blocking.size,
                        coverage.blocking.size,
                        coverage.blocking.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
            }
            if (coverage.allowing.isNotEmpty()) {
                Text(
                    stringResource(R.string.check_lists_allowing, coverage.allowing.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
            }
            if (result.appRules.isNotEmpty()) {
                Text(
                    stringResource(R.string.check_app_rules_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = onContainer,
                    modifier = Modifier.padding(top = spacing.xs),
                )
                result.appRules.forEach { rule ->
                    Text(
                        stringResource(
                            if (rule.block) R.string.check_app_rule_blocked else R.string.check_app_rule_allowed,
                            vm.labelFor(rule.packageName),
                            rule.domain,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer,
                    )
                }
            }
        }
    }
}

/** The one thing that decided the verdict, in a sentence. */
@Composable
private fun checkReason(result: DomainCheck): String {
    val verdict = result.verdict
    return when {
        verdict.source == RuleSource.USER_RULE && verdict.blocked ->
            stringResource(R.string.check_reason_your_block, result.userRule ?: result.domain)
        verdict.source == RuleSource.USER_RULE ->
            stringResource(R.string.check_reason_your_allow, result.userRule ?: result.domain)
        verdict.source == RuleSource.LIST && verdict.blocked -> stringResource(R.string.check_reason_list, verdict.detail)
        verdict.source == RuleSource.LIST -> stringResource(R.string.check_reason_exception, verdict.detail)
        result.connectivityCheck -> stringResource(R.string.check_reason_connectivity)
        else -> stringResource(R.string.check_reason_nothing)
    }
}

/**
 * Many rules at once, from a clipboard: a hosts file, a few lines of a list, a column of domains.
 *
 * The count is live and says what will happen before it does — how many domains, how many lines
 * could not become one — because a paste is the easiest thing in this app to get wrong in bulk,
 * and "0 domains" or "412 lines ignored" is the moment somebody notices they copied the wrong text.
 */
@Composable
private fun PasteRulesDialog(onDismiss: () -> Unit, onConfirm: (List<String>, Boolean) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = remember(text) { BulkRules.parse(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                Text(
                    stringResource(R.string.paste_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 8,
                    textStyle = MonoSmall,
                )
                if (text.isNotBlank()) {
                    Text(
                        buildString {
                            append(pluralStringResource(R.plurals.paste_domains, parsed.domains.size, parsed.domains.size))
                            if (parsed.skipped > 0) {
                                append(" · ")
                                append(pluralStringResource(R.plurals.paste_skipped, parsed.skipped, parsed.skipped))
                            }
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (parsed.truncated) {
                        Text(
                            stringResource(R.string.paste_truncated, BulkRules.MAX_DOMAINS),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
                TextButton(onClick = { onConfirm(parsed.domains, false) }, enabled = parsed.domains.isNotEmpty()) {
                    Text(stringResource(R.string.paste_allow_all))
                }
                TextButton(onClick = { onConfirm(parsed.domains, true) }, enabled = parsed.domains.isNotEmpty()) {
                    Text(stringResource(R.string.paste_block_all))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RuleRow(domain: String, blocking: Boolean, onDelete: () -> Unit) {
    val spacing = Tokens.spacing
    MalachiCard {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (blocking) MalachiIcons.Block else Icons.Filled.CheckCircle,
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
