package dev.malachi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.data.BackupPolicy
import dev.malachi.net.TunnelProblem
import dev.malachi.net.VpnController
import dev.malachi.ui.BackupMessage
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.RestoreConfirmation
import dev.malachi.ui.Screen
import dev.malachi.ui.rememberBackupActions
import dev.malachi.ui.components.ActionChoices
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.CardPosition
import dev.malachi.ui.components.shortDuration
import dev.malachi.stats.Counts
import dev.malachi.stats.StatsData
import dev.malachi.stats.StatsWindow
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.PrimaryAction
import dev.malachi.ui.components.SecondaryAction
import dev.malachi.ui.components.NavRow
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.theme.NumberCaption
import dev.malachi.ui.theme.NumberDisplay
import dev.malachi.ui.theme.Tokens
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * The one screen most people will ever see: is it on, is it working, and what has it done.
 *
 * The order is deliberate. The switch comes first because it is the only control that matters;
 * anything wrong comes second, before the statistics, because a number of blocked lookups
 * printed above an unnoticed "the filter isn't actually running" is worse than no number at all.
 */
@Composable
fun HomeScreen(
    vm: MalachiViewModel,
    onRequestVpnConsent: () -> Unit,
    onOpen: (Screen) -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val todayCounts = remember(stats) { stats.window(StatsWindow.TODAY, today).counts }

    // The statistics are read when somebody looks, never published per lookup — the tunnel must
    // not pay to keep a screen that is usually closed up to date.
    //
    // "Looking" has to mean coming back, not just arriving. A LaunchedEffect fires when the
    // screen is composed and never again: leaving for another app, generating a few lookups and
    // returning takes seconds and recomposes nothing, so the card sat there showing the count
    // from before the trip. LifecycleResumeEffect runs on every resume, and immediately if the
    // screen is composed while already resumed.
    LifecycleResumeEffect(Unit) {
        vm.refreshStats()
        onPauseOrDispose { }
    }
    // And when the filter comes up or goes down, which moves them while the screen is open.
    LaunchedEffect(status.tunnelUp) { vm.refreshStats() }
    val listedDomains by vm.listedDomains.collectAsStateWithLifecycle()
    val listProgress by vm.listProgress.collectAsStateWithLifecycle()
    val alwaysOn by vm.alwaysOn.collectAsStateWithLifecycle()
    val anotherVpn by vm.anotherVpn.collectAsStateWithLifecycle()
    val backup = rememberBackupActions(vm)
    BackupMessage(vm)
    RestoreConfirmation(vm)
    val spacing = Tokens.spacing
    var pausing by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.screen, spacing.lg, spacing.screen, spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = spacing.sm),
            )
        }

        item {
            PowerCard(
                filtering = settings.filteringEnabled,
                running = status.tunnelUp,
                pausedUntilMs = if (settings.isPaused()) settings.pausedUntilMs else 0,
                blockedPercent = todayCounts.blockedPercent,
                onToggle = { on -> if (on) onRequestVpnConsent() else vm.setFilterEnabled(false) },
                onResume = vm::resume,
                onPause = { pausing = true },
            )
        }

        // Everything that can make the switch lie, each with the one action that fixes it. Shown
        // while the user wants the filter running, and also when they don't *because* something
        // refused — a switch that sprang back with no explanation was the original bug here.
        if (!status.tunnelUp && !settings.isPaused() && (settings.filteringEnabled || status.needsUser)) {
            item {
                when (status.problem) {
                    TunnelProblem.ALWAYS_ON_ELSEWHERE -> Notice(
                        tone = Tone.Problem,
                        text = stringResource(
                            R.string.status_always_on_elsewhere_long,
                            vm.alwaysOnOtherLabel() ?: stringResource(R.string.status_another_app),
                        ),
                        action = stringResource(R.string.action_open_vpn_settings),
                        onAction = vm::openVpnSettings,
                    )
                    // Android says only "refused", so the card offers both readings: the likely
                    // one (a dialog dismissed) with a retry, and the one nobody would guess
                    // (another VPN holding the slot) with the screen that fixes it.
                    TunnelProblem.NO_CONSENT -> Notice(
                        tone = Tone.Problem,
                        text = stringResource(
                            if (anotherVpn) R.string.status_no_consent_other_vpn else R.string.status_no_consent_long,
                        ),
                        action = stringResource(R.string.action_turn_on),
                        onAction = onRequestVpnConsent,
                        secondary = stringResource(R.string.action_open_vpn_settings),
                        onSecondary = vm::openVpnSettings,
                    )
                    TunnelProblem.NO_APPS_SELECTED -> Notice(
                        tone = Tone.Problem,
                        text = stringResource(R.string.status_no_apps_selected),
                        action = stringResource(R.string.home_choose_apps),
                        onAction = { onOpen(Screen.Apps) },
                    )
                    TunnelProblem.DISPLACED -> Notice(
                        tone = Tone.Problem,
                        text = stringResource(R.string.status_displaced),
                        action = stringResource(R.string.action_open_vpn_settings),
                        onAction = vm::openVpnSettings,
                    )
                    // Coming up is not a failure and must not be painted as one.
                    TunnelProblem.STARTING, TunnelProblem.NONE -> Notice(
                        tone = Tone.Working,
                        text = stringResource(R.string.status_starting),
                    )
                    TunnelProblem.FAILED -> Notice(
                        tone = Tone.Problem,
                        text = if (status.retrying) {
                            stringResource(R.string.status_retrying)
                        } else {
                            status.detail.ifEmpty { stringResource(R.string.status_tunnel_closed) }
                        },
                    )
                }
            }
        }
        // Above everything else, because when this is on nothing else on the phone works and no
        // other screen will say why.
        if (status.lockdown) {
            item {
                Notice(
                    tone = Tone.Problem,
                    text = stringResource(R.string.warning_lockdown),
                    action = stringResource(R.string.action_open_vpn_settings),
                    onAction = vm::openVpnSettings,
                )
            }
        }

        // Strict Private DNS is the one thing that silently defeats this app: every lookup leaves
        // over TLS and none of it reaches the tunnel. Automatic is the platform default and costs
        // filtering nothing, so it gets a note rather than an alarm — painting both red told
        // almost every user that nothing was being filtered while everything was.
        if (status.privateDnsStrict && settings.filteringEnabled) {
            item {
                Notice(
                    tone = Tone.Problem,
                    text = stringResource(R.string.warning_private_dns_strict, status.privateDnsHost.orEmpty()),
                    action = stringResource(R.string.action_open_private_dns),
                    onAction = vm::openPrivateDnsSettings,
                )
            }
        } else if (status.privateDnsAutomatic && status.tunnelUp && !settings.privateDnsNoteDismissed) {
            item {
                Notice(
                    tone = Tone.Suggestion,
                    text = stringResource(R.string.note_private_dns_automatic),
                    action = stringResource(R.string.action_got_it),
                    onAction = vm::dismissPrivateDnsNote,
                    secondary = stringResource(R.string.action_open_private_dns),
                    onSecondary = vm::openPrivateDnsSettings,
                )
            }
        }
        // A fresh install fetches twenty megabytes before it can block anything, and a phone
        // that is visibly busy with no explanation is a phone somebody uninstalls. Shown while it
        // runs and gone when it finishes, which is also why the empty-lists warning below waits
        // for it: "nothing is being blocked" is alarming and, right now, merely early.
        val progress = listProgress
        if (progress != null) {
            item {
                Notice(
                    tone = Tone.Working,
                    text = stringResource(R.string.home_lists_downloading, progress.done + 1, progress.total),
                )
            }
        } else if (settings.filteringEnabled && listedDomains == 0) {
            item {
                Notice(
                    tone = Tone.Problem,
                    text = stringResource(R.string.warning_no_lists),
                    action = stringResource(R.string.home_open_lists),
                    onAction = { onOpen(Screen.Lists) },
                )
            }
        }
        // Offered only once the filter actually works: always-on is what survives a reboot and
        // stops another VPN quietly taking the tunnel, and it is the last thing to set up rather
        // than a hurdle in front of the first run. Dismissible for good, because whether it is
        // already configured is something this app is not allowed to find out.
        if (status.tunnelUp &&
            alwaysOn !is VpnController.AlwaysOn.Malachi &&
            !settings.alwaysOnTipDismissed
        ) {
            item {
                Notice(
                    tone = Tone.Suggestion,
                    text = stringResource(R.string.home_always_on_suggestion),
                    action = stringResource(R.string.action_open_vpn_settings),
                    onAction = vm::openVpnSettings,
                    secondary = stringResource(R.string.action_got_it),
                    onSecondary = vm::dismissAlwaysOnTip,
                )
            }
        }

        // The one thing in this app that cannot be rebuilt on a new phone: the rules somebody
        // wrote one broken app at a time, and the lists they settled on. Offered when there is
        // something unsaved, put off on a widening schedule, and never mentioned again once a
        // copy exists — until the decisions change. See BackupPolicy.
        if (BackupPolicy.reminderDue(settings, System.currentTimeMillis())) {
            item {
                Notice(
                    tone = Tone.Suggestion,
                    text = stringResource(R.string.home_backup_suggestion),
                    action = stringResource(R.string.action_backup_now),
                    onAction = backup.export,
                    secondary = stringResource(R.string.action_remind_later),
                    onSecondary = vm::remindBackupLater,
                    tertiary = stringResource(R.string.action_never_remind),
                    onTertiary = vm::stopBackupReminders,
                )
            }
        }

        item { SectionHeader(stringResource(R.string.home_section_today)) }
        item {
            StatsCard(
                today = todayCounts,
                allTime = stats.allTime,
                sinceEpochDay = stats.sinceEpochDay,
                listedDomains = listedDomains,
            )
        }

        item { SectionHeader(stringResource(R.string.home_section_manage)) }
        item {
            CardGroup {
                val rows = 5
                NavRow(
                    icon = Icons.Filled.Apps,
                    title = stringResource(R.string.nav_apps),
                    subtitle = scopeSummary(vm),
                    onClick = { onOpen(Screen.Apps) },
                    position = cardPosition(0, rows),
                )
                NavRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                    title = stringResource(R.string.nav_lists),
                    subtitle = pluralStringResource(
                        R.plurals.lists_domains_blocked,
                        listedDomains,
                        NumberFormat.getInstance().format(listedDomains),
                    ),
                    onClick = { onOpen(Screen.Lists) },
                    position = cardPosition(1, rows),
                )
                NavRow(
                    icon = Icons.Filled.Timeline,
                    title = stringResource(R.string.nav_activity),
                    // Naming today's worst offender rather than describing the screen: it is the
                    // app somebody is most likely to be coming to look at, and it costs nothing —
                    // these statistics are already read on every resume of this screen. The query
                    // log would answer it better and is deliberately not touched here; subscribing
                    // to it from the home screen makes every lookup build a snapshot.
                    subtitle = busiestToday(vm, stats, today)
                        ?: stringResource(R.string.nav_activity_subtitle),
                    onClick = { onOpen(Screen.Activity) },
                    position = cardPosition(2, rows),
                )
                NavRow(
                    icon = Icons.Filled.Gavel,
                    title = stringResource(R.string.nav_rules),
                    subtitle = pluralStringResource(
                        R.plurals.rules_count,
                        settings.userBlocked.size + settings.userAllowed.size + settings.appRules.size,
                        settings.userBlocked.size + settings.userAllowed.size + settings.appRules.size,
                    ),
                    onClick = { onOpen(Screen.Rules) },
                    position = cardPosition(3, rows),
                )
                NavRow(
                    icon = Icons.Filled.Settings,
                    title = stringResource(R.string.nav_settings),
                    subtitle = stringResource(R.string.nav_settings_subtitle),
                    onClick = { onOpen(Screen.Settings) },
                    position = cardPosition(4, rows),
                )
            }
        }
    }

    // Outside the list: inside an item it would exist only while that row happened to be
    // scrolled into view.
    if (pausing) {
        PauseDialog(
            onDismiss = { pausing = false },
            onPause = { minutes -> vm.pause(minutes); pausing = false },
        )
    }
}

/**
 * How long to stand down for.
 *
 * The button used to be "Pause for 15 minutes" and mean it, which is the right answer for
 * exactly one of the two reasons people pause a DNS filter: checking whether it is what broke a
 * page (a minute) and getting through something that needs it off (an afternoon). The durations
 * are written by the platform, so this costs no translated strings and reads correctly in
 * languages neither of us thought about.
 */
@Composable
private fun PauseDialog(onDismiss: () -> Unit, onPause: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_title)) },
        text = {
            ActionChoices {
                PAUSE_CHOICES.forEach { minutes ->
                    SecondaryAction(
                        text = shortDuration(minutes * 60_000L),
                        onClick = { onPause(minutes) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private val PAUSE_CHOICES = listOf(5, 15, 60, 180)

/** "Today: Instagram, 412 blocked", or null before anything has been refused today. */
@Composable
private fun busiestToday(vm: MalachiViewModel, stats: StatsData, today: LocalDate): String? {
    val worst = remember(stats, today) {
        stats.window(StatsWindow.TODAY, today).topByBlocked(1).firstOrNull()
    } ?: return null
    return stringResource(
        R.string.nav_activity_busiest,
        vm.labelFor(worst.packageName),
        NumberFormat.getInstance().format(worst.counts.blocked),
    )
}

@Composable
private fun scopeSummary(vm: MalachiViewModel): String {
    val settings by vm.settings.collectAsStateWithLifecycle()
    return when (settings.scopeMode) {
        dev.malachi.data.AppScopeMode.ALL_EXCEPT ->
            if (settings.excludedApps.isEmpty()) {
                stringResource(R.string.apps_summary_all)
            } else {
                pluralStringResource(R.plurals.apps_summary_excluded, settings.excludedApps.size, settings.excludedApps.size)
            }
        dev.malachi.data.AppScopeMode.ONLY_SELECTED ->
            pluralStringResource(R.plurals.apps_summary_only, settings.includedApps.size, settings.includedApps.size)
    }
}

/**
 * The switch, and a percentage that only appears once there is something to be a percentage of.
 * When the filter is on the card carries the app's one gradient — the single strongest signal
 * available, spent on the single most important piece of state.
 */
@Composable
private fun PowerCard(
    filtering: Boolean,
    running: Boolean,
    pausedUntilMs: Long,
    blockedPercent: Int,
    onToggle: (Boolean) -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
) {
    val spacing = Tokens.spacing
    val active = filtering && running
    val brush = Tokens.heroBrush

    MalachiCard(color = if (active) Color.Transparent else MaterialTheme.colorScheme.surface) {
        Box(
            Modifier
                .then(if (active) Modifier.background(brush) else Modifier)
                .fillMaxWidth()
                .padding(spacing.xl),
        ) {
            val onHero = if (active) Tokens.onHero else MaterialTheme.colorScheme.onSurface
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = if (active) onHero else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                pausedUntilMs > 0 -> stringResource(R.string.state_paused)
                                active -> stringResource(R.string.state_protected)
                                filtering -> stringResource(R.string.state_starting)
                                else -> stringResource(R.string.state_off)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = onHero,
                        )
                        Text(
                            when {
                                pausedUntilMs > 0 -> stringResource(
                                    R.string.state_paused_until,
                                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pausedUntilMs)),
                                )
                                active -> stringResource(R.string.state_protected_subtitle)
                                else -> stringResource(R.string.state_off_subtitle)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            // Full strength. The palette's contrast is checked against both ends
                            // of this gradient and then it was drawn at 85%, which is a dilution
                            // no test can see: 5.8:1 became 4.7:1 at 12sp, and it looked it.
                            color = if (active) onHero else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = filtering,
                        onCheckedChange = onToggle,
                        colors = if (active) {
                            SwitchDefaults.colors(
                                checkedThumbColor = onHero,
                                checkedTrackColor = onHero.copy(alpha = 0.35f),
                                checkedBorderColor = onHero.copy(alpha = 0.6f),
                            )
                        } else {
                            SwitchDefaults.colors()
                        },
                    )
                }

                if (active && blockedPercent > 0) {
                    Spacer(Modifier.height(spacing.lg))
                    Text("$blockedPercent%", style = NumberDisplay, color = onHero)
                    Text(
                        stringResource(R.string.state_blocked_share),
                        style = NumberCaption,
                        color = onHero,
                    )
                }

                if (filtering) {
                    Spacer(Modifier.height(spacing.md))
                    Row {
                        // On the gradient, so it takes the hero's own foreground. As a plain
                        // TextButton it drew itself in `primary` — teal on teal, about 1:1, which
                        // is the reason this action was hard to read at all.
                        if (pausedUntilMs > 0) {
                            PrimaryAction(
                                text = stringResource(R.string.action_resume),
                                onClick = onResume,
                                onContainer = onHero,
                                container = if (active) Tokens.heroContainer else MaterialTheme.colorScheme.surface,
                            )
                        } else if (active) {
                            SecondaryAction(
                                text = stringResource(R.string.action_pause),
                                onClick = onPause,
                                onContainer = onHero,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Today's blocked and allowed, and what the filter is working from.
 *
 * From the statistics on disk, not from the tunnel's session counters. Those are wiped whenever
 * the tunnel is rebuilt — which is a reboot, an app-scope change, a retry after another VPN took
 * over, and every time the app updates itself. This card said "since the filter started" and
 * meant it, but from the outside a number that returns to zero after an update is an app that
 * forgot. What is on disk survives all of it.
 */
@Composable
private fun StatsCard(today: Counts, allTime: Counts, sinceEpochDay: Long, listedDomains: Int) {
    val spacing = Tokens.spacing
    val numbers = NumberFormat.getInstance()
    MalachiCard {
        Column(Modifier.padding(spacing.lg)) {
            Row(Modifier.fillMaxWidth()) {
                Stat(numbers.format(today.blocked), stringResource(R.string.stat_blocked), Modifier.weight(1f))
                Stat(numbers.format(today.allowed), stringResource(R.string.stat_allowed), Modifier.weight(1f))
                Stat(numbers.format(listedDomains), stringResource(R.string.stat_on_lists), Modifier.weight(1f))
            }
            if (allTime.total > 0 && sinceEpochDay > 0) {
                Spacer(Modifier.height(spacing.md))
                Text(
                    stringResource(
                        R.string.stat_all_time,
                        numbers.format(allTime.blocked),
                        DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(Date(LocalDate.ofEpochDay(sinceEpochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * How loudly a notice speaks. The distinction earns its keep: "coming up" and "another VPN is in
 * the way" used to render identically in alarm red, which taught users to read the red card as
 * noise — exactly when it was about to carry something they needed to act on.
 */
private enum class Tone { Problem, Working, Suggestion }

/** Something worth saying, stated plainly, with its action or two beside it. */
@Composable
private fun Notice(
    tone: Tone,
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    tertiary: String? = null,
    onTertiary: () -> Unit = {},
) {
    val spacing = Tokens.spacing
    val container = when (tone) {
        Tone.Problem -> MaterialTheme.colorScheme.errorContainer
        Tone.Working -> MaterialTheme.colorScheme.surfaceContainerHigh
        Tone.Suggestion -> MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = when (tone) {
        Tone.Problem -> MaterialTheme.colorScheme.onErrorContainer
        Tone.Working -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.Suggestion -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    MalachiCard(color = container) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (tone) {
                    Tone.Working -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = onContainer,
                    )
                    else -> Icon(
                        if (tone == Tone.Problem) Icons.Filled.Warning else Icons.Filled.VerifiedUser,
                        contentDescription = null,
                        tint = onContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(spacing.md))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                    modifier = Modifier.weight(1f),
                )
                // A single action sits beside the text; a pair gets its own row below, so a long
                // explanation is never squeezed into a column two words wide.
                if (action != null && secondary == null) {
                    Spacer(Modifier.width(spacing.sm))
                    PrimaryAction(text = action, onClick = onAction, onContainer = onContainer, container = container)
                }
            }
            if (action != null && secondary != null) {
                Spacer(Modifier.height(spacing.md))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                ) {
                    SecondaryAction(text = secondary, onClick = onSecondary, onContainer = onContainer)
                    PrimaryAction(text = action, onClick = onAction, onContainer = onContainer, container = container)
                }
                // The way out of a recurring suggestion gets its own line rather than a third
                // seat in that row: three labels of ordinary length — "Don't ask again", "Later",
                // "Save a copy" — do not fit across a narrow phone, and in Spanish they fit
                // less. It also belongs apart from the other two: those are about right now, and
                // this one is a decision about every time after.
                if (tertiary != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        SecondaryAction(text = tertiary, onClick = onTertiary, onContainer = onContainer)
                    }
                }
            }
        }
    }
}
