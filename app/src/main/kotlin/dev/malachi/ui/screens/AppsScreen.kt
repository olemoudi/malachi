package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.data.AppScopeMode
import dev.malachi.data.InstalledApp
import dev.malachi.data.MalachiSettings
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.AppIcon
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.CardPosition
import dev.malachi.ui.components.ChoiceRow
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.SwitchRow
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.theme.Tokens

/**
 * Which apps are filtered, from either end.
 *
 * The two modes are the same switch read in opposite directions, and both requests are real:
 * "block ads everywhere but leave my bank alone", and "I only want this one game filtered".
 * Showing them as one choice rather than two features is what stops them from disagreeing —
 * and it makes the per-app list mean exactly one thing in each mode, which the header says.
 */
@Composable
fun AppsScreen(vm: MalachiViewModel, onBack: () -> Unit, onOpenApp: (String) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    val visible = remember(apps, query, showSystem) {
        apps.asSequence()
            .filter { showSystem || !it.isSystem }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .toList()
    }

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.nav_apps), onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item { SectionHeader(stringResource(R.string.apps_mode_title)) }
            item {
                CardGroup {
                    ChoiceRow(
                        title = stringResource(R.string.apps_mode_all_except),
                        subtitle = stringResource(R.string.apps_mode_all_except_subtitle),
                        selected = settings.scopeMode == AppScopeMode.ALL_EXCEPT,
                        onSelect = { vm.setScopeMode(AppScopeMode.ALL_EXCEPT) },
                        position = CardPosition.First,
                    )
                    ChoiceRow(
                        title = stringResource(R.string.apps_mode_only_selected),
                        subtitle = stringResource(R.string.apps_mode_only_selected_subtitle),
                        selected = settings.scopeMode == AppScopeMode.ONLY_SELECTED,
                        onSelect = { vm.setScopeMode(AppScopeMode.ONLY_SELECTED) },
                        position = CardPosition.Last,
                    )
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.apps_list_title),
                    supporting = stringResource(
                        when (settings.scopeMode) {
                            AppScopeMode.ALL_EXCEPT -> R.string.apps_list_hint_all_except
                            AppScopeMode.ONLY_SELECTED -> R.string.apps_list_hint_only_selected
                        },
                    ),
                )
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.apps_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                )
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.apps_show_system),
                    subtitle = stringResource(R.string.apps_show_system_subtitle),
                    checked = showSystem,
                    onCheckedChange = { showSystem = it },
                )
            }

            if (apps.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.apps_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(spacing.lg),
                    )
                }
            }

            items(visible, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    covered = settings.covers(app.packageName),
                    rules = settings.appRulesFor(app.packageName).size,
                    vm = vm,
                    onToggle = { vm.setAppCovered(app.packageName, it) },
                    onOpen = { onOpenApp(app.packageName) },
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    covered: Boolean,
    rules: Int,
    vm: MalachiViewModel,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val spacing = Tokens.spacing
    MalachiCard(onClick = onOpen) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName, vm.inventory)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                // Android Auto is off by default and it is not obvious why — its own error
                // blames "a VPN" without saying whose. Said here, somebody who switches it back
                // on at least knows what they are trading.
                val incompatible = app.packageName in MalachiSettings.INCOMPATIBLE_WITH_A_VPN
                Text(
                    when {
                        incompatible && !covered -> stringResource(R.string.apps_breaks_with_vpn)
                        rules > 0 -> pluralStringResource(R.plurals.apps_rule_count, rules, rules)
                        else -> app.packageName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Switch(checked = covered, onCheckedChange = onToggle)
        }
    }
}
