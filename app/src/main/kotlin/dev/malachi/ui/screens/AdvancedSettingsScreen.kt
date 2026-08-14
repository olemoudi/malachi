package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.data.BlockAnswerMode
import dev.malachi.data.BypassGuard
import dev.malachi.data.MalachiSettings
import dev.malachi.data.UpstreamDns
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.ChoiceRow
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.SwitchRow
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.theme.Tokens

/**
 * The four dials that decide how the filter behaves at DNS level, on a screen of their own.
 *
 * They used to be the first thing in Settings, which meant somebody opening it to change the
 * theme or make a backup scrolled past three radio groups about DNS semantics to get there.
 * These are not the settings anybody arrives for; they are the settings somebody comes looking
 * for once, deliberately, usually because an app is misbehaving — and that is exactly the shape
 * of a screen you go *into* rather than one you scroll past.
 *
 * None of them has a right answer for everyone — that is why they are settings and not
 * constants — so every one still carries its trade-off in its own subtitle rather than leaving
 * the user to discover it by having something break a week later.
 */
@Composable
fun AdvancedSettingsScreen(vm: MalachiViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    var editingUpstream by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.settings_advanced_row), onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                SectionHeader(
                    title = stringResource(R.string.settings_block_answer_title),
                    supporting = stringResource(R.string.settings_block_answer_hint),
                )
            }
            item {
                CardGroup {
                    val modes = BlockAnswerMode.entries
                    modes.forEachIndexed { index, mode ->
                        ChoiceRow(
                            title = stringResource(blockAnswerTitle(mode)),
                            subtitle = stringResource(blockAnswerHint(mode)),
                            selected = settings.blockAnswer == mode,
                            onSelect = { vm.setBlockAnswer(mode) },
                            position = cardPosition(index, modes.size),
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.settings_upstream_title),
                    supporting = stringResource(R.string.settings_upstream_hint),
                )
            }
            item {
                CardGroup {
                    val options = UpstreamDns.entries
                    options.forEachIndexed { index, option ->
                        ChoiceRow(
                            title = stringResource(upstreamTitle(option)),
                            subtitle = when (option) {
                                UpstreamDns.SYSTEM -> stringResource(R.string.upstream_system_hint)
                                UpstreamDns.CUSTOM -> settings.customUpstream.ifEmpty {
                                    stringResource(R.string.upstream_custom_hint)
                                }
                                else -> option.addresses.joinToString(", ")
                            },
                            selected = settings.upstream == option,
                            onSelect = {
                                if (option == UpstreamDns.CUSTOM) editingUpstream = true else vm.setUpstream(option)
                            },
                            position = cardPosition(index, options.size),
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.settings_bypass_title),
                    supporting = stringResource(R.string.settings_bypass_hint),
                )
            }
            item {
                CardGroup {
                    val guards = BypassGuard.entries
                    guards.forEachIndexed { index, guard ->
                        ChoiceRow(
                            title = stringResource(bypassTitle(guard)),
                            subtitle = stringResource(bypassHint(guard)),
                            selected = settings.bypassGuard == guard,
                            onSelect = { vm.setBypassGuard(guard) },
                            position = cardPosition(index, guards.size),
                        )
                    }
                }
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.settings_allow_bypass),
                    subtitle = stringResource(R.string.settings_allow_bypass_hint),
                    checked = settings.bypassAllowed,
                    onCheckedChange = vm::setBypassAllowed,
                )
            }
        }
    }

    if (editingUpstream) {
        CustomUpstreamDialog(
            initial = settings.customUpstream,
            onDismiss = { editingUpstream = false },
            onConfirm = { vm.setCustomUpstream(it); editingUpstream = false },
        )
    }
}

/**
 * What is currently set, for the row that leads here.
 *
 * A navigation row whose subtitle merely repeats its own title teaches nobody anything; this one
 * says what is actually set, which is the whole reason to look — and it means the commonest
 * errand ("what is my DNS server again") is answered without opening the screen at all.
 *
 * Two of the four, and not with the titles the choices carry. Those are written as sentences to
 * a person deciding — "Whatever the network provides", "Catch the usual ones — recommended" —
 * and three of them strung together is a paragraph in a slot that holds about six words. A
 * summary needs the short technical name, which on this screen is also the word somebody looking
 * for the setting already knows.
 */
@Composable
fun advancedSummary(settings: MalachiSettings): String = listOf(
    stringResource(blockAnswerShort(settings.blockAnswer)),
    if (settings.upstream == UpstreamDns.CUSTOM && settings.customUpstream.isNotEmpty()) {
        settings.customUpstream
    } else if (settings.upstream == UpstreamDns.SYSTEM) {
        stringResource(R.string.upstream_system_short)
    } else {
        stringResource(upstreamTitle(settings.upstream))
    },
).joinToString(" · ")

private fun blockAnswerShort(mode: BlockAnswerMode) = when (mode) {
    BlockAnswerMode.NULL_ADDRESS -> R.string.block_answer_null_short
    BlockAnswerMode.NXDOMAIN -> R.string.block_answer_nxdomain_short
    BlockAnswerMode.REFUSED -> R.string.block_answer_refused_short
}

@Composable
private fun CustomUpstreamDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upstream_custom)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.upstream_custom_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().padding(top = Tokens.spacing.sm),
                    singleLine = true,
                    label = { Text(stringResource(R.string.upstream_custom_label)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun blockAnswerTitle(mode: BlockAnswerMode) = when (mode) {
    BlockAnswerMode.NULL_ADDRESS -> R.string.block_answer_null
    BlockAnswerMode.NXDOMAIN -> R.string.block_answer_nxdomain
    BlockAnswerMode.REFUSED -> R.string.block_answer_refused
}

private fun blockAnswerHint(mode: BlockAnswerMode) = when (mode) {
    BlockAnswerMode.NULL_ADDRESS -> R.string.block_answer_null_hint
    BlockAnswerMode.NXDOMAIN -> R.string.block_answer_nxdomain_hint
    BlockAnswerMode.REFUSED -> R.string.block_answer_refused_hint
}

private fun upstreamTitle(upstream: UpstreamDns) = when (upstream) {
    UpstreamDns.SYSTEM -> R.string.upstream_system
    UpstreamDns.CLOUDFLARE -> R.string.upstream_cloudflare
    UpstreamDns.GOOGLE -> R.string.upstream_google
    UpstreamDns.QUAD9 -> R.string.upstream_quad9
    UpstreamDns.ADGUARD -> R.string.upstream_adguard
    UpstreamDns.CUSTOM -> R.string.upstream_custom
}

private fun bypassTitle(guard: BypassGuard) = when (guard) {
    BypassGuard.OFF -> R.string.bypass_off
    BypassGuard.SYSTEM_RESOLVERS -> R.string.bypass_system
    BypassGuard.PUBLIC_RESOLVERS -> R.string.bypass_public
}

private fun bypassHint(guard: BypassGuard) = when (guard) {
    BypassGuard.OFF -> R.string.bypass_off_hint
    BypassGuard.SYSTEM_RESOLVERS -> R.string.bypass_system_hint
    BypassGuard.PUBLIC_RESOLVERS -> R.string.bypass_public_hint
}
