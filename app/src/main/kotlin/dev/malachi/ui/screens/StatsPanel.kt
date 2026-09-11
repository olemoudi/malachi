package dev.malachi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.stats.AppStat
import dev.malachi.stats.DayStats
import dev.malachi.stats.RankingOrder
import dev.malachi.stats.StatsData
import dev.malachi.stats.StatsWindow
import dev.malachi.stats.WindowStats
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.ActionChoices
import dev.malachi.ui.components.AppIcon
import dev.malachi.ui.components.SecondaryAction
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.CardPosition
import dev.malachi.ui.components.MalachiFilterChip
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.theme.NumberDisplay
import dev.malachi.ui.theme.Tokens
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the filter has done over time, which the live query log cannot answer.
 *
 * The log is a window onto the last few hundred lookups and is gone at the next restart; this is
 * the arithmetic that survives. Four things are worth knowing and each answers a different
 * question: the headline says how much is being refused, the chart says whether that is going
 * anywhere, the comparison says whether this period is unusual, and the two rankings say who is
 * responsible — *most blocked* finds the app generating the most junk, *highest rate* finds the
 * app that does almost nothing else.
 *
 * None of it names a domain. What is on disk is counts per app per day, which is enough to
 * answer "what has this been doing all month" and not enough to reconstruct anywhere anybody
 * has been.
 */
@Composable
fun StatsPanel(
    vm: MalachiViewModel,
    onOpenApp: (String) -> Unit,
    onSeeAll: (RankingOrder, StatsWindow) -> Unit,
) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    // Saveable, because both rankings lead off this screen — to an app, or to the full list — and
    // coming back to "today" from a month's ranking is the screen forgetting the question.
    // `resetting` deliberately is not: it is a dialog.
    var window by rememberSaveable { mutableStateOf(StatsWindow.TODAY) }
    var resetting by remember { mutableStateOf(false) }
    // Re-read on every resume: this panel lives in a screen that stays alive across midnight,
    // and a date taken once left "Today" describing yesterday.
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        onPauseOrDispose { }
    }
    val computed = remember(stats, window, today) { stats.window(window, today) }
    val numbers = remember { NumberFormat.getInstance() }

    Column(Modifier.fillMaxWidth()) {
        StatsWindowChips(window, onSelect = { window = it })

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
                    // How many apps are talking at all is its own answer: a number that climbs
                    // after an install is the first sign of something new on the phone.
                    Text(
                        stringResource(R.string.stats_apps_seen, computed.apps.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                    ComparisonLine(stats, window, computed, today)
                }
                SinceLine(stats, today)
            }
        }

        if (computed.counts.total > 0) {
            DailyChart(stats, window, today, numbers)

            Ranking(
                title = stringResource(R.string.stats_by_count),
                supporting = stringResource(R.string.stats_by_count_hint),
                order = RankingOrder.BY_COUNT,
                apps = computed.topByBlocked(RANK_SIZE),
                appsInWindow = computed.apps.size,
                numbers = numbers,
                vm = vm,
                onOpenApp = onOpenApp,
                onSeeAll = { onSeeAll(RankingOrder.BY_COUNT, window) },
            )
            Ranking(
                title = stringResource(R.string.stats_by_rate),
                supporting = stringResource(
                    R.string.stats_by_rate_hint,
                    WindowStats.MINIMUM_LOOKUPS_FOR_RATE,
                ),
                order = RankingOrder.BY_RATE,
                apps = computed.topByRate(RANK_SIZE),
                appsInWindow = computed.apps.size,
                numbers = numbers,
                vm = vm,
                onOpenApp = onOpenApp,
                onSeeAll = { onSeeAll(RankingOrder.BY_RATE, window) },
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // Opens a dialog rather than doing it: this sits at the bottom of a screen people
            // scroll through, and it used to throw away months of counters on one stray tap.
            SecondaryAction(text = stringResource(R.string.stats_reset), onClick = { resetting = true })
        }
    }

    if (resetting) {
        ResetDialog(
            onDismiss = { resetting = false },
            onReset = {
                vm.clearStats(it)
                resetting = false
            },
        )
    }
}

/**
 * The period a set of statistics covers, shared by the panel and the full ranking.
 *
 * Flowing, and every label on one line. Four of these do not fit a narrow phone at a large font
 * size: as a plain Row the last label wrapped inside its own chip, which made that one chip twice
 * the height of the three beside it. Scrolling them sideways fixes the height and hides a chip
 * instead; flowing to a second row hides nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsWindowChips(selected: StatsWindow, onSelect: (StatsWindow) -> Unit, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        StatsWindow.entries.forEach { option ->
            MalachiFilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(stringResource(windowLabel(option)), maxLines = 1, softWrap = false) },
            )
        }
    }
}

/** "Counting since 3 May" — what the all-time number is actually of. */
@Composable
private fun SinceLine(stats: StatsData, today: LocalDate) {
    if (stats.sinceEpochDay <= 0) return
    val date = remember(stats.sinceEpochDay) { LocalDate.ofEpochDay(stats.sinceEpochDay) }
    if (date >= today) return
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    Text(
        stringResource(R.string.stats_since, formatter.format(date)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Tokens.spacing.sm),
    )
}

/**
 * How this period compares with the one before it.
 *
 * A percentage on its own says nothing about whether anything changed — 40% blocked is either
 * normal or alarming depending on last week. Hidden entirely for all-time, which has nothing
 * before it, and when there is no previous period to compare against.
 */
@Composable
private fun ComparisonLine(stats: StatsData, window: StatsWindow, current: WindowStats, today: LocalDate) {
    val previous = remember(stats, window) { stats.previousWindow(window, today) } ?: return
    val periodName = stringResource(
        when (window) {
            StatsWindow.TODAY -> R.string.stats_period_yesterday
            StatsWindow.WEEK -> R.string.stats_period_last_week
            StatsWindow.MONTH -> R.string.stats_period_last_month
            StatsWindow.ALL -> return
        },
    )
    val before = previous.counts.total
    val text = when {
        before == 0L -> stringResource(R.string.stats_delta_new, periodName)
        else -> {
            val change = ((current.counts.total - before) * 100 / before).toInt()
            when {
                change > 5 -> stringResource(R.string.stats_delta_more, change, periodName)
                change < -5 -> stringResource(R.string.stats_delta_fewer, -change, periodName)
                else -> stringResource(R.string.stats_delta_same, periodName)
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Tokens.spacing.sm),
    )
}

/**
 * Refused lookups, one bar per day.
 *
 * The number at the top of the screen says how much is being blocked; this says whether that is
 * going anywhere — a tracker that arrived with last week's app update, a day the filter was off,
 * a weekend that looks nothing like the weekdays around it. The days with nothing recorded are
 * drawn as gaps rather than skipped, because a chart that closes up the empty days is a chart
 * that hides the question somebody came here with.
 */
@Composable
private fun DailyChart(stats: StatsData, window: StatsWindow, today: LocalDate, numbers: NumberFormat) {
    val spacing = Tokens.spacing
    val days = remember(stats, window) {
        val span = StatsData.chartDays(window)
        stats.dailySeries(today.minusDays(span - 1), today)
    }
    val peak = days.maxOfOrNull { it.counts.blocked } ?: 0L
    if (peak == 0L) return

    SectionHeader(
        title = stringResource(R.string.stats_trend_title),
        supporting = stringResource(R.string.stats_trend_hint),
    )
    MalachiCard {
        Column(Modifier.padding(spacing.lg)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day -> DayBar(day, peak, Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(spacing.sm))
            Row(Modifier.fillMaxWidth()) {
                DayLabel(days.first().epochDay, Modifier.weight(1f), TextAlign.Start)
                DayLabel(days.last().epochDay, Modifier.weight(1f), TextAlign.End)
            }
            days.maxByOrNull { it.counts.blocked }?.let { busiest ->
                val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
                Text(
                    stringResource(
                        R.string.stats_trend_busiest,
                        formatter.format(LocalDate.ofEpochDay(busiest.epochDay)),
                        numbers.format(busiest.counts.blocked),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }
    }
}

/** One day's bar, with a floor so a day that saw something never reads as a day that didn't. */
@Composable
private fun DayBar(day: DayStats, peak: Long, modifier: Modifier) {
    val fraction = if (peak == 0L) 0f else (day.counts.blocked.toFloat() / peak).coerceIn(0f, 1f)
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (day.counts.blocked > 0) maxOf(fraction, 0.04f) else 0.02f)
                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                .background(
                    if (day.counts.blocked > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ),
        )
    }
}

@Composable
private fun DayLabel(epochDay: Long, modifier: Modifier, align: TextAlign) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM") }
    Text(
        formatter.format(LocalDate.ofEpochDay(epochDay)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = modifier,
    )
}

/** The scope of a reset. Four answers and a way out, because the button used to have neither. */
@Composable
private fun ResetDialog(onDismiss: () -> Unit, onReset: (StatsWindow) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_reset_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.stats_reset_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Tokens.spacing.md))
                ActionChoices {
                    listOf(
                        StatsWindow.TODAY to R.string.stats_reset_scope_today,
                        StatsWindow.WEEK to R.string.stats_reset_scope_week,
                        StatsWindow.MONTH to R.string.stats_reset_scope_month,
                        StatsWindow.ALL to R.string.stats_reset_scope_all,
                    ).forEach { (scope, label) ->
                        SecondaryAction(
                            text = stringResource(label),
                            onClick = { onReset(scope) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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
    order: RankingOrder,
    apps: List<AppStat>,
    appsInWindow: Int,
    numbers: NumberFormat,
    vm: MalachiViewModel,
    onOpenApp: (String) -> Unit,
    onSeeAll: () -> Unit,
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
    }
    // Only when the full list holds something this one does not: a "see all" that opens the same
    // five rows is a dead control.
    val seeAll = appsInWindow > apps.size
    val cards = apps.size + if (seeAll) 1 else 0
    if (cards == 0) return
    CardGroup {
        apps.forEachIndexed { index, stat ->
            AppStatRow(stat, rankingValue(order, stat, numbers), cardPosition(index, cards), vm, onOpenApp)
        }
        if (seeAll) SeeAllCard(appsInWindow, cardPosition(cards - 1, cards), onSeeAll)
    }
}

/**
 * One app's line in a ranking, shared by the panel's top five and the full list so the two cannot
 * drift into saying the same thing two ways.
 *
 * [valueColor] is for a number that belongs on the row but carries a caveat — a rate taken from
 * too few lookups to mean much — and so should not shout.
 */
@Composable
internal fun AppStatRow(
    stat: AppStat,
    value: String,
    position: CardPosition,
    vm: MalachiViewModel,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.primary,
) {
    val spacing = Tokens.spacing
    // Naming an app is only half an answer: the next question is always "what is it
    // asking for", and that is the detail screen. Every card with an app on it opens it.
    MalachiCard(
        modifier = modifier,
        position = position,
        onClick = { onOpenApp(stat.packageName) },
    ) {
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
            Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
        }
    }
}

/** The number a ranking leads with: refusals by count, or their share as a percentage. */
internal fun rankingValue(order: RankingOrder, stat: AppStat, numbers: NumberFormat): String = when (order) {
    RankingOrder.BY_COUNT -> numbers.format(stat.counts.blocked)
    RankingOrder.BY_RATE -> "${stat.counts.blockedPercent}%"
}

/** The way from a ranking's first rows to all of them, as the last card of the same group. */
@Composable
private fun SeeAllCard(count: Int, position: CardPosition, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    MalachiCard(position = position, onClick = onClick) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.stats_see_all, count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
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
