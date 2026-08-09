package dev.malachi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.stats.AppStat
import dev.malachi.stats.StatsWindow
import dev.malachi.stats.WindowStats
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.AppIcon
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.theme.NumberDisplay
import dev.malachi.ui.theme.Tokens
import java.text.NumberFormat
import java.time.LocalDate

/**
 * What the filter has done over time, which the live query log cannot answer.
 *
 * The log is a window onto the last few hundred lookups and is gone at the next restart; this is
 * the arithmetic that survives. The two rankings answer genuinely different questions and both
 * are worth having: *most blocked* finds the app generating the most junk, while *highest rate*
 * finds the app that does almost nothing else — a game with one noisy ad SDK ranks first on
 * count, a tracker-only background service ranks first on proportion.
 */
@Composable
fun StatsPanel(vm: MalachiViewModel) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    var window by remember { mutableStateOf(StatsWindow.TODAY) }
    val today = remember { LocalDate.now() }
    val computed = remember(stats, window) { stats.window(window, today) }
    val numbers = remember { NumberFormat.getInstance() }

    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            StatsWindow.entries.forEach { option ->
                FilterChip(
                    selected = window == option,
                    onClick = { window = option },
                    label = { Text(stringResource(windowLabel(option))) },
                )
            }
        }

        Spacer(Modifier.height(spacing.sm))

        MalachiCard {
            Column(Modifier.padding(spacing.lg)) {
                Text(
                    "${computed.counts.blockedPercent}%",
                    style = NumberDisplay,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (computed.counts.total == 0L) {
                        stringResource(R.string.stats_none_yet)
                    } else {
                        stringResource(
                            R.string.stats_of_lookups,
                            numbers.format(computed.counts.blocked),
                            numbers.format(computed.counts.total),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (computed.counts.total > 0) {
                    Spacer(Modifier.height(spacing.md))
                    ProportionBar(computed.counts.blockedPercent)
                }
            }
        }

        if (computed.counts.total > 0) {
            Ranking(
                title = stringResource(R.string.stats_by_count),
                supporting = stringResource(R.string.stats_by_count_hint),
                apps = computed.topByBlocked(RANK_SIZE),
                vm = vm,
                valueOf = { numbers.format(it.counts.blocked) },
            )
            Ranking(
                title = stringResource(R.string.stats_by_rate),
                supporting = stringResource(
                    R.string.stats_by_rate_hint,
                    WindowStats.MINIMUM_LOOKUPS_FOR_RATE,
                ),
                apps = computed.topByRate(RANK_SIZE),
                vm = vm,
                valueOf = { "${it.counts.blockedPercent}%" },
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = vm::clearStats) { Text(stringResource(R.string.stats_reset)) }
        }
    }
}

/** A single bar: blocked against everything seen. One glance, no axis, no legend. */
@Composable
private fun ProportionBar(percent: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(percent / 100f)
                .height(8.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun Ranking(
    title: String,
    supporting: String,
    apps: List<AppStat>,
    vm: MalachiViewModel,
    valueOf: (AppStat) -> String,
) {
    val spacing = Tokens.spacing
    SectionHeader(title = title, supporting = supporting)
    if (apps.isEmpty()) {
        Text(
            stringResource(R.string.stats_no_apps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(spacing.md),
        )
        return
    }
    CardGroup {
        apps.forEachIndexed { index, stat ->
            MalachiCard(position = cardPosition(index, apps.size)) {
                Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(stat.packageName, vm.inventory, size = 32.dp)
                    Spacer(Modifier.width(spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(vm.labelFor(stat.packageName), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                R.string.stats_app_detail,
                                stat.counts.blocked,
                                stat.counts.total,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(spacing.sm))
                    Text(
                        valueOf(stat),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private const val RANK_SIZE = 5

private fun windowLabel(window: StatsWindow) = when (window) {
    StatsWindow.TODAY -> R.string.stats_window_today
    StatsWindow.WEEK -> R.string.stats_window_week
    StatsWindow.MONTH -> R.string.stats_window_month
    StatsWindow.ALL -> R.string.stats_window_all
}
