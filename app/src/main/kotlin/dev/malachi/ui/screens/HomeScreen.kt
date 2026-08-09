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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.net.TunnelProblem
import dev.malachi.net.VpnController
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.Screen
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.CardPosition
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.NavRow
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.theme.NumberDisplay
import dev.malachi.ui.theme.Tokens
import java.text.DateFormat
import java.text.NumberFormat
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
    val log by vm.queryLog.collectAsStateWithLifecycle()
    val listedDomains by vm.listedDomains.collectAsStateWithLifecycle()
    val alwaysOn by vm.alwaysOn.collectAsStateWithLifecycle()
    val anotherVpn by vm.anotherVpn.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

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
                blockedPercent = log.blockedPercent,
                onToggle = { on -> if (on) onRequestVpnConsent() else vm.setFilterEnabled(false) },
                onResume = vm::resume,
                onPause = { vm.pause() },
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
        if (status.privateDnsActive) {
            item {
                Notice(
                    tone = Tone.Problem,
                    text = stringResource(
                        R.string.warning_private_dns,
                        status.privateDnsHost ?: stringResource(R.string.warning_private_dns_automatic),
                    ),
                )
            }
        }
        if (settings.filteringEnabled && listedDomains == 0) {
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

        item { SectionHeader(stringResource(R.string.home_section_activity)) }
        item {
            StatsCard(
                blocked = log.blocked,
                total = log.total,
                sinceMs = log.sinceMs,
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
                    subtitle = stringResource(R.string.nav_activity_subtitle),
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
            val onHero = if (active) Color.White else MaterialTheme.colorScheme.onSurface
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = if (active) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = filtering,
                        onCheckedChange = onToggle,
                        colors = if (active) {
                            SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color.White.copy(alpha = 0.35f),
                                checkedBorderColor = Color.White.copy(alpha = 0.6f),
                            )
                        } else {
                            SwitchDefaults.colors()
                        },
                    )
                }

                if (active && blockedPercent > 0) {
                    Spacer(Modifier.height(spacing.lg))
                    Text("$blockedPercent%", style = NumberDisplay, color = Color.White)
                    Text(
                        stringResource(R.string.state_blocked_share),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }

                if (filtering) {
                    Spacer(Modifier.height(spacing.sm))
                    Row {
                        if (pausedUntilMs > 0) {
                            TextButton(onClick = onResume) { Text(stringResource(R.string.action_resume)) }
                        } else if (active) {
                            TextButton(onClick = onPause) { Text(stringResource(R.string.action_pause_15)) }
                        }
                    }
                }
            }
        }
    }
}

/** Blocked, allowed, and what the filter is working from — the three numbers worth showing. */
@Composable
private fun StatsCard(blocked: Long, total: Long, sinceMs: Long, listedDomains: Int) {
    val spacing = Tokens.spacing
    val numbers = NumberFormat.getInstance()
    MalachiCard {
        Column(Modifier.padding(spacing.lg)) {
            Row(Modifier.fillMaxWidth()) {
                Stat(numbers.format(blocked), stringResource(R.string.stat_blocked), Modifier.weight(1f))
                Stat(numbers.format(total - blocked), stringResource(R.string.stat_allowed), Modifier.weight(1f))
                Stat(numbers.format(listedDomains), stringResource(R.string.stat_on_lists), Modifier.weight(1f))
            }
            if (total > 0) {
                Spacer(Modifier.height(spacing.md))
                Text(
                    stringResource(
                        R.string.stat_since,
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sinceMs)),
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
                    TextButton(onClick = onAction) { Text(action, color = onContainer) }
                }
            }
            if (action != null && secondary != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onSecondary) { Text(secondary, color = onContainer) }
                    TextButton(onClick = onAction) { Text(action, color = onContainer) }
                }
            }
        }
    }
}
