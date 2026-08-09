package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.lists.BlocklistCatalog
import dev.malachi.lists.BlocklistCategory
import dev.malachi.lists.ListState
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.SwitchRow
import dev.malachi.ui.components.ValueRow
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.theme.Tokens
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * The subscribed lists, what each one is for, and when it was last fetched.
 *
 * Every list here says who maintains it and roughly what it will cost you, because "block more"
 * and "break less" are the same dial and the user is the only one who knows which end they want
 * to be at. The two that ship on are the conservative ones; everything below them is ordered
 * roughly by how likely it is to break something.
 */
@Composable
fun ListsScreen(vm: MalachiViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val states by vm.listStates.collectAsStateWithLifecycle()
    val refreshing by vm.refreshingLists.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.nav_lists), onBack) {
            if (refreshing) {
                CircularProgressIndicator(Modifier.size(20.dp).padding(end = spacing.sm), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = vm::refreshLists) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item { SectionHeader(stringResource(R.string.lists_schedule_title)) }
            item {
                CardGroup {
                    ValueRow(
                        title = stringResource(R.string.lists_schedule_every),
                        subtitle = stringResource(R.string.lists_schedule_every_subtitle),
                        value = stringResource(R.string.lists_schedule_hours, settings.listUpdateHours),
                        onClick = { vm.setListUpdateHours(nextInterval(settings.listUpdateHours)) },
                        position = cardPosition(0, 2),
                    )
                    SwitchRow(
                        title = stringResource(R.string.lists_wifi_only),
                        subtitle = stringResource(R.string.lists_wifi_only_subtitle),
                        checked = settings.listUpdateWifiOnly,
                        onCheckedChange = vm::setListUpdateWifiOnly,
                        position = cardPosition(1, 2),
                    )
                }
            }

            BlocklistCategory.entries.forEach { category ->
                val sources = BlocklistCatalog.sources.filter { it.category == category }
                if (sources.isEmpty()) return@forEach

                item(key = "header-$category") {
                    SectionHeader(
                        title = stringResource(categoryTitle(category)),
                        supporting = stringResource(categoryHint(category)),
                    )
                }
                item(key = "group-$category") {
                    CardGroup {
                        sources.forEachIndexed { index, source ->
                            ListRow(
                                title = source.title,
                                maintainer = source.maintainer,
                                description = stringResource(listDescription(source.id)),
                                state = states[source.id],
                                approximateEntries = source.approximateEntries,
                                enabled = BlocklistCatalog.isEnabled(source.id, settings.listChoices),
                                onToggle = { vm.setListEnabled(source.id, it) },
                                position = cardPosition(index, sources.size),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRow(
    title: String,
    maintainer: String,
    description: String,
    state: ListState?,
    approximateEntries: Int,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    position: dev.malachi.ui.components.CardPosition,
) {
    val spacing = Tokens.spacing
    val numbers = NumberFormat.getInstance()
    MalachiCard(onClick = { onToggle(!enabled) }, position = position) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    when {
                        state?.lastError?.isNotEmpty() == true ->
                            stringResource(R.string.lists_last_error, state.lastError)
                        state != null && state.isDownloaded -> stringResource(
                            R.string.lists_entries_updated,
                            numbers.format(state.entries),
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(state.fetchedAtMs)),
                        )
                        enabled -> stringResource(R.string.lists_pending)
                        else -> stringResource(R.string.lists_about, maintainer, numbers.format(approximateEntries))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state?.lastError?.isNotEmpty() == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.width(spacing.md))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/** Cycles the refresh interval through the handful of values anyone actually wants. */
private fun nextInterval(current: Int): Int = when (current) {
    in 0..1 -> 6
    in 2..6 -> 12
    in 7..12 -> 24
    in 13..24 -> 72
    else -> 1
}

private fun categoryTitle(category: BlocklistCategory) = when (category) {
    BlocklistCategory.ADS -> R.string.lists_category_ads
    BlocklistCategory.PRIVACY -> R.string.lists_category_privacy
    BlocklistCategory.SECURITY -> R.string.lists_category_security
    BlocklistCategory.EXTRAS -> R.string.lists_category_extras
}

private fun categoryHint(category: BlocklistCategory) = when (category) {
    BlocklistCategory.ADS -> R.string.lists_category_ads_hint
    BlocklistCategory.PRIVACY -> R.string.lists_category_privacy_hint
    BlocklistCategory.SECURITY -> R.string.lists_category_security_hint
    BlocklistCategory.EXTRAS -> R.string.lists_category_extras_hint
}

/**
 * The human explanation of each source. Kept here rather than in the catalog so the catalog
 * stays a plain data object with no Android dependency, and so the text can be translated.
 */
private fun listDescription(id: String) = when (id) {
    "adguard-dns" -> R.string.list_adguard_dns
    "adaway" -> R.string.list_adaway
    "easyprivacy" -> R.string.list_easyprivacy
    "yoyo" -> R.string.list_yoyo
    "oisd-small" -> R.string.list_oisd_small
    "oisd-big" -> R.string.list_oisd_big
    "hagezi-pro" -> R.string.list_hagezi_pro
    "hagezi-tif" -> R.string.list_hagezi_tif
    "stevenblack" -> R.string.list_stevenblack
    "someonewhocares" -> R.string.list_someonewhocares
    else -> R.string.list_unknown
}
