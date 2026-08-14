package dev.malachi.ui.components

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The one filter chip in this app.
 *
 * Material's selected chip fills itself with `secondaryContainer`, which in this palette is
 * **amber** — and amber here means a count or a warning, nothing else. A row of chips choosing
 * which lookups to show was therefore drawn in the colour reserved for "something is wrong",
 * beside segmented buttons that had already been moved to the accent for exactly this reason.
 * Centralised so the next screen with chips on it cannot drift back.
 */
@Composable
fun MalachiFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        leadingIcon = leadingIcon,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}
