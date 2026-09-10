package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.stats.RankingOrder
import dev.malachi.stats.StatsData
import dev.malachi.stats.StatsWindow
import dev.malachi.stats.WindowStats
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.SegmentedChoice
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.theme.Tokens
import java.text.NumberFormat
import java.time.LocalDate

/**
 * Every app the statistics know about in one period, ranked: the whole list the panel's top five
 * are the head of.
 *
 * One screen with both orders rather than two destinations, because the question moves between
 * them — the app with the most refusals is rarely the app that does nothing else, and comparing
 * the two is the point of having both.
 *
 * Nothing here is new data: it is the same counts per app per day the panel reads, so it names no
 * domain.
 */
@Composable
fun AppRankingScreen(
    vm: MalachiViewModel,
    initialOrder: RankingOrder,
    initialWindow: StatsWindow,
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    // The same refresh as the panel that leads here: coming back from an app, or from another app
    // entirely, is exactly when these numbers have moved and nothing has recomposed.
    LifecycleResumeEffect(Unit) {
        vm.refreshStats()
        onPauseOrDispose { }
    }

    // Saveable, so opening an app from here and coming back finds the same order and period. The
    // destination only says where to start.
    var order by rememberSaveable { mutableStateOf(initialOrder) }
    var window by rememberSaveable { mutableStateOf(initialWindow) }
    val today = remember { LocalDate.now() }
    val computed = remember(stats, window) { stats.window(window, today) }
    val ranked = remember(computed, order) {
        when (order) {
            RankingOrder.BY_COUNT -> computed.rankedByBlocked()
            RankingOrder.BY_RATE -> computed.rankedByRate()
        }
    }
    val tooFew = remember(computed, order) {
        if (order == RankingOrder.BY_RATE) computed.tooFewForRate() else emptyList()
    }
    val mayBeMissing = remember(stats, window) { stats.mayBeMissingApps(window, today) }
    val numbers = remember { NumberFormat.getInstance() }

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.stats_ranking_title), onBack)

        // Outside the list, so changing the order or the period never means scrolling a couple of
        // hundred rows back up to reach the control.
        Column(Modifier.padding(horizontal = spacing.screen, vertical = spacing.xs)) {
            SegmentedChoice(
                options = RankingOrder.entries,
                selected = order,
                onSelect = { order = it },
                label = {
                    stringResource(
                        if (it == RankingOrder.BY_COUNT) R.string.stats_order_count else R.string.stats_order_rate,
                    )
                },
            )
            StatsWindowChips(window, onSelect = { window = it }, modifier = Modifier.padding(top = spacing.sm))
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
        ) {
            item(key = "header") {
                SectionHeader(
                    title = stringResource(
                        if (order == RankingOrder.BY_COUNT) R.string.stats_by_count else R.string.stats_by_rate,
                    ),
                    supporting = if (order == RankingOrder.BY_COUNT) {
                        stringResource(R.string.stats_by_count_hint)
                    } else {
                        stringResource(R.string.stats_by_rate_hint, WindowStats.MINIMUM_LOOKUPS_FOR_RATE)
                    },
                    modifier = Modifier.padding(bottom = spacing.sm),
                )
            }

            if (ranked.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.stats_no_apps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(spacing.md),
                    )
                }
            }

            // Keyed by package: the two sections never share an app, and the key is what lets a row
            // glide to its new place when the order changes instead of the whole list repainting.
            itemsIndexed(ranked, key = { _, stat -> stat.packageName }) { index, stat ->
                AppStatRow(
                    stat = stat,
                    value = rankingValue(order, stat, numbers),
                    position = cardPosition(index, ranked.size),
                    vm = vm,
                    onOpenApp = onOpenApp,
                    modifier = Modifier.animateItem(),
                )
            }

            if (tooFew.isNotEmpty()) {
                item(key = "too-few") {
                    SectionHeader(
                        title = stringResource(R.string.stats_too_few_title),
                        supporting = stringResource(R.string.stats_too_few_hint, WindowStats.MINIMUM_LOOKUPS_FOR_RATE),
                        modifier = Modifier.padding(bottom = spacing.sm),
                    )
                }
                itemsIndexed(tooFew, key = { _, stat -> stat.packageName }) { index, stat ->
                    AppStatRow(
                        stat = stat,
                        value = rankingValue(order, stat, numbers),
                        position = cardPosition(index, tooFew.size),
                        vm = vm,
                        onOpenApp = onOpenApp,
                        modifier = Modifier.animateItem(),
                        // Shown, because it is the number this order is about; muted, because at this
                        // sample size it is the figure on the row least worth believing.
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Last, because that is where somebody arrives who scrolled the whole list looking for an
            // app and did not find it.
            if (mayBeMissing) {
                item(key = "bound") {
                    Text(
                        stringResource(
                            R.string.stats_ranking_bound,
                            StatsData.MAX_APPS_PER_DAY,
                            StatsData.MAX_ALL_TIME_APPS,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = spacing.xs, vertical = spacing.lg),
                    )
                }
            }
        }
    }
}
