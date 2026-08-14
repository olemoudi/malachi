package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.data.AppScopeMode
import dev.malachi.data.InstalledApp
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.AppIcon
import dev.malachi.ui.components.MalachiFilterChip
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.SegmentedChoice
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * Which apps are filtered, from either end.
 *
 * The two modes are the same switch read in opposite directions, and both requests are real:
 * "block ads everywhere but leave my bank alone", and "I only want this one game filtered".
 * Showing them as one choice rather than two features is what stops them from disagreeing —
 * and it makes the per-app list mean exactly one thing in each mode, which the line under the
 * selector says.
 *
 * Everything above the list is kept to three rows on purpose. This screen exists to find one app
 * among two hundred, and a header block that fills half the viewport pushes the first result off
 * the screen before anybody has typed anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: MalachiViewModel, onBack: () -> Unit, onOpenApp: (String) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val visible = remember(apps, query, showSystem) {
        apps.asSequence()
            .filter { showSystem || !it.isSystem }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .toList()
    }

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.nav_apps), onBack)
        LazyColumn(
            // The keyboard's room is made once for the whole app, in MalachiApp.
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(spacing.screen, spacing.sm, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Column {
                    SegmentedChoice(
                        options = AppScopeMode.entries,
                        selected = settings.scopeMode,
                        onSelect = vm::setScopeMode,
                        label = { stringResource(modeLabel(it)) },
                    )
                    Text(
                        stringResource(modeHint(settings.scopeMode)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.sm, start = spacing.xs, end = spacing.xs),
                    )
                }
            }

            item {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            // Taking focus scrolls the field to the top of the list, so the
                            // whole gap between it and the keyboard is results.
                            .onFocusChanged { if (it.isFocused) scope.launch { listState.animateScrollToItem(SEARCH_ITEM) } },
                        singleLine = true,
                        label = { Text(stringResource(R.string.apps_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    )
                    // A chip rather than a switch card: one line instead of three, and the
                    // filtered-or-not state is what it has to say.
                    MalachiFilterChip(
                        selected = showSystem,
                        onClick = { showSystem = !showSystem },
                        label = { Text(stringResource(R.string.apps_show_system)) },
                        leadingIcon = if (showSystem) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
            }

            if (apps.isEmpty() || visible.isEmpty()) {
                item {
                    Text(
                        stringResource(
                            when {
                                apps.isEmpty() -> R.string.apps_loading
                                // A search that matches nothing used to render an empty column,
                                // which reads as the list having failed to load rather than as
                                // an answer. The commonest cause is the app being a system one.
                                showSystem -> R.string.apps_no_matches
                                else -> R.string.apps_no_matches_try_system
                            },
                        ),
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

/** Where the search field sits in the list; scrolled to when it takes focus. */
private const val SEARCH_ITEM = 1

private fun modeLabel(mode: AppScopeMode) = when (mode) {
    AppScopeMode.ALL_EXCEPT -> R.string.apps_mode_all_except
    AppScopeMode.ONLY_SELECTED -> R.string.apps_mode_only_selected
}

private fun modeHint(mode: AppScopeMode) = when (mode) {
    AppScopeMode.ALL_EXCEPT -> R.string.apps_list_hint_all_except
    AppScopeMode.ONLY_SELECTED -> R.string.apps_list_hint_only_selected
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
                Text(
                    when {
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
