package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
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
import dev.malachi.data.ThemeMode
import dev.malachi.data.UpstreamDns
import dev.malachi.net.VpnController
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.ChoiceRow
import dev.malachi.ui.components.NavRow
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.SwitchRow
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.ValueRow
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.theme.Tokens
import dev.malachi.update.UpdateUiState

/**
 * The dials, each with the sentence that says what turning it costs.
 *
 * None of these has a right answer for everyone — that is exactly why they are settings and not
 * constants — so every one carries the trade-off in its subtitle rather than making the user
 * discover it by having something break a week later.
 */
@Composable
fun SettingsScreen(
    vm: MalachiViewModel,
    onBack: () -> Unit,
    onOpenDebugLog: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val alwaysOn by vm.alwaysOn.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    var editingUpstream by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.nav_settings), onBack)
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
                SectionHeader(
                    title = stringResource(R.string.settings_connection_title),
                    supporting = stringResource(R.string.settings_always_on_hint),
                )
            }
            item {
                ValueRow(
                    title = stringResource(R.string.settings_always_on),
                    // "Unknown" is the honest answer on a current Android and is what most
                    // devices will show: the setting is readable by the system alone. Saying
                    // "not set" instead would be a guess, and a wrong one for anyone who has
                    // already turned it on.
                    subtitle = when (val state = alwaysOn) {
                        is VpnController.AlwaysOn.Malachi -> stringResource(R.string.settings_always_on_on)
                        is VpnController.AlwaysOn.Other -> stringResource(
                            R.string.settings_always_on_other,
                            vm.alwaysOnOtherLabel() ?: state.packageName,
                        )
                        is VpnController.AlwaysOn.None -> stringResource(R.string.settings_always_on_off)
                        is VpnController.AlwaysOn.Unknown -> stringResource(R.string.settings_always_on_unknown)
                    },
                    value = stringResource(R.string.action_open),
                    onClick = vm::openVpnSettings,
                )
            }

            item { SectionHeader(stringResource(R.string.settings_privacy_title)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_query_log),
                    subtitle = stringResource(R.string.settings_query_log_hint),
                    checked = settings.queryLogEnabled,
                    onCheckedChange = vm::setQueryLogEnabled,
                )
            }

            item { SectionHeader(stringResource(R.string.settings_appearance_title)) }
            item {
                CardGroup {
                    val modes = ThemeMode.entries
                    modes.forEachIndexed { index, mode ->
                        ChoiceRow(
                            title = stringResource(themeTitle(mode)),
                            selected = theme == mode,
                            onSelect = { vm.setThemeMode(mode) },
                            position = cardPosition(index, modes.size),
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.settings_updates_title),
                    supporting = stringResource(R.string.settings_updates_hint),
                )
            }
            item {
                CardGroup {
                    SwitchRow(
                        title = stringResource(R.string.settings_update_wifi_only),
                        subtitle = stringResource(R.string.settings_update_wifi_only_hint),
                        checked = settings.updateWifiOnly,
                        onCheckedChange = vm::setUpdateWifiOnly,
                        position = cardPosition(0, 2),
                    )
                    ValueRow(
                        title = stringResource(R.string.settings_check_update),
                        subtitle = updateSummary(update),
                        value = stringResource(R.string.action_check),
                        onClick = vm::checkForUpdate,
                        position = cardPosition(1, 2),
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.settings_diagnostics_title)) }
            item {
                CardGroup {
                    NavRow(
                        icon = Icons.Filled.BugReport,
                        title = stringResource(R.string.settings_debug_log),
                        subtitle = stringResource(R.string.settings_debug_log_hint),
                        onClick = onOpenDebugLog,
                        position = cardPosition(0, 2),
                    )
                    NavRow(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.settings_about),
                        subtitle = stringResource(R.string.settings_about_hint, vm.versionName),
                        onClick = onOpenAbout,
                        position = cardPosition(1, 2),
                    )
                }
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

@Composable
private fun updateSummary(state: UpdateUiState): String = when (state) {
    UpdateUiState.Idle -> stringResource(R.string.update_idle)
    UpdateUiState.Checking -> stringResource(R.string.update_checking)
    is UpdateUiState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateUiState.Downloading -> stringResource(R.string.update_downloading, state.target.versionName)
    is UpdateUiState.PendingConfirmation -> stringResource(R.string.update_pending)
    is UpdateUiState.Failed -> stringResource(R.string.update_failed, state.step)
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

private fun themeTitle(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
