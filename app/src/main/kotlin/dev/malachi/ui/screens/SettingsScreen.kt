package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.data.BackupPolicy
import dev.malachi.data.ThemeMode
import dev.malachi.net.MalachiVpnService
import dev.malachi.net.VpnController
import dev.malachi.ui.BackupMessage
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.RestoreConfirmation
import dev.malachi.ui.rememberBackupActions
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.NavRow
import dev.malachi.ui.components.SegmentedChoice
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
 * Ordered by who comes looking. Always-on, the backup and the query log are what somebody opens
 * this screen *for*; the four DNS-level dials are what somebody goes hunting for once, usually
 * because an app is misbehaving, so they live one tap away in [AdvancedSettingsScreen] with
 * their current values written on the row that leads there. They used to be the first thing
 * here, which put three radio groups about DNS semantics in front of everybody who wanted to
 * change the theme.
 */
@Composable
fun SettingsScreen(
    vm: MalachiViewModel,
    onBack: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenDebugLog: () -> Unit,
    onOpenDiagnose: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val alwaysOn by vm.alwaysOn.collectAsStateWithLifecycle()
    val backup = rememberBackupActions(vm)
    BackupMessage(vm)
    RestoreConfirmation(vm)
    val spacing = Tokens.spacing

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.nav_settings), onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
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

            item {
                SectionHeader(
                    title = stringResource(R.string.settings_backup_title),
                    supporting = stringResource(R.string.settings_backup_hint),
                )
            }
            item {
                CardGroup {
                    NavRow(
                        icon = Icons.Filled.Save,
                        title = stringResource(R.string.settings_backup_export),
                        // The state of the copy, in the row that makes one. "Everything saved"
                        // is as much the point as the button: the reminder exists because
                        // nobody can tell by looking whether their rules are anywhere else.
                        subtitle = if (BackupPolicy.isStale(settings)) {
                            stringResource(R.string.settings_backup_unsaved)
                        } else if (settings.backupFingerprint.isEmpty()) {
                            stringResource(R.string.settings_backup_never)
                        } else {
                            stringResource(R.string.settings_backup_saved)
                        },
                        onClick = backup.export,
                        position = cardPosition(0, if (settings.backupRemindersOff) 4 else 3),
                    )
                    // Next to saving rather than hidden behind it: a file in Downloads is on the
                    // phone that is about to be lost, and one sent to an inbox is not.
                    NavRow(
                        icon = Icons.Filled.Share,
                        title = stringResource(R.string.settings_backup_share),
                        subtitle = stringResource(R.string.settings_backup_share_hint),
                        onClick = vm::shareBackup,
                        position = cardPosition(1, if (settings.backupRemindersOff) 4 else 3),
                    )
                    NavRow(
                        icon = Icons.Filled.Restore,
                        title = stringResource(R.string.settings_backup_import),
                        subtitle = stringResource(R.string.settings_backup_import_hint),
                        onClick = backup.import,
                        position = cardPosition(2, if (settings.backupRemindersOff) 4 else 3),
                    )
                    // Only once it has been switched off, because a switch that is on by default
                    // and never touched is a row that explains nothing to the people who read it.
                    if (settings.backupRemindersOff) {
                        SwitchRow(
                            title = stringResource(R.string.settings_backup_reminders),
                            subtitle = stringResource(R.string.settings_backup_reminders_hint),
                            checked = false,
                            onCheckedChange = vm::setBackupReminders,
                            position = cardPosition(3, 4),
                        )
                    }
                }
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
                // One row rather than three cards. Every other setting on this screen carries a
                // sentence about what it costs, and this one costs nothing — three full-width
                // cards with a radio button apiece was most of a screenful spent on a word.
                SegmentedChoice(
                    options = ThemeMode.entries,
                    selected = theme,
                    onSelect = vm::setThemeMode,
                    label = { stringResource(themeTitle(it)) },
                )
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

            // Its own section rather than a row inside Diagnostics: these dials decide how the
            // filter behaves, which is the opposite of a diagnostic, and filing them there was
            // only ever a way of getting them out of the way.
            item {
                SectionHeader(
                    title = stringResource(R.string.settings_advanced),
                    supporting = stringResource(R.string.settings_advanced_hint),
                )
            }
            item {
                // With what they are set to on the row itself: "what is my DNS server again" is
                // then answered without opening anything.
                NavRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.settings_advanced_row),
                    subtitle = advancedSummary(settings),
                    onClick = onOpenAdvanced,
                )
            }

            item { SectionHeader(stringResource(R.string.settings_diagnostics_title)) }
            item {
                CardGroup {
                    // First, because it is the one thing on this screen somebody arrives with a
                    // concrete problem for: an app that hangs, and no way to tell which name it
                    // is hanging on. Everything below it is for reading after the fact.
                    NavRow(
                        icon = Icons.Filled.Troubleshoot,
                        title = stringResource(R.string.nav_diagnose),
                        subtitle = settings.diagnosing()?.let {
                            stringResource(R.string.diagnose_watching_app, vm.labelFor(it))
                        } ?: stringResource(R.string.diagnose_row_hint),
                        onClick = onOpenDiagnose,
                        position = cardPosition(0, 4),
                    )
                    // Its own row above the log, because it is what makes the log worth reading
                    // when an app misbehaves: without it the tunnel says almost nothing per
                    // lookup, on purpose.
                    SwitchRow(
                        title = stringResource(R.string.settings_trace),
                        subtitle = if (settings.isDiagnosing()) {
                            stringResource(
                                R.string.settings_trace_running,
                                ((settings.diagnosticsUntilMs - System.currentTimeMillis()) / 60_000L + 1).toInt(),
                            )
                        } else {
                            stringResource(R.string.settings_trace_hint, MalachiVpnService.DIAGNOSTICS_MINUTES)
                        },
                        checked = settings.isDiagnosing(),
                        onCheckedChange = vm::setDiagnostics,
                        position = cardPosition(1, 4),
                    )
                    NavRow(
                        icon = Icons.Filled.BugReport,
                        title = stringResource(R.string.settings_debug_log),
                        subtitle = stringResource(R.string.settings_debug_log_hint),
                        onClick = onOpenDebugLog,
                        position = cardPosition(2, 4),
                    )
                    NavRow(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.settings_about),
                        subtitle = stringResource(R.string.settings_about_hint, vm.versionName),
                        onClick = onOpenAbout,
                        position = cardPosition(3, 4),
                    )
                }
            }
        }
    }
}

@Composable
private fun updateSummary(state: UpdateUiState): String = when (state) {
    UpdateUiState.Idle -> stringResource(R.string.update_idle)
    UpdateUiState.Checking -> stringResource(R.string.update_checking)
    is UpdateUiState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateUiState.Downloading -> stringResource(R.string.update_downloading, state.target.versionName)
    is UpdateUiState.PendingConfirmation -> stringResource(R.string.update_pending)
    is UpdateUiState.Failed -> stringResource(R.string.update_failed, state.step)
    UpdateUiState.AlreadyChecking -> stringResource(R.string.update_already_checking)
    UpdateUiState.SkippedMetered -> stringResource(R.string.update_skipped_metered)
}

private fun themeTitle(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
