package dev.malachi.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * One choice among a few, in a single row.
 *
 * The alternative in this app is a group of [ChoiceRow] cards, which is right when each option
 * needs a sentence explaining what it costs — the DNS dials, where choosing wrong breaks
 * something a week later. It is wrong when the options are one word each and nobody needs
 * telling what "Dark" means: three full-width cards with a radio button apiece is most of a
 * screenful spent on a preference.
 *
 * Shared rather than repeated because all three uses had already copied the same colour
 * override. The default fills the selected segment with `secondaryContainer`, which in this
 * palette is amber, and amber here means a count or a warning — the same drift the filter chips
 * had.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> String,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                // No check glyph: it costs a quarter of the label's width, and the segment is
                // already filled when it is the selected one.
                icon = {},
            ) {
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
