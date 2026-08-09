package dev.malachi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.malachi.ui.theme.Tokens

/**
 * The app-wide card language. Every full-width card goes through here, so the whole app shares
 * one radius, one hairline border and one grouping idiom.
 *
 * Cards in the same section are *connected*: they sit a hair apart and flatten the corners
 * facing each other, so belonging to a group is visible geometry rather than mere proximity.
 */
enum class CardPosition { Single, First, Middle, Last }

private val OuterRadius = 20.dp
private val InnerRadius = 6.dp

val CardPosition.shape: RoundedCornerShape
    get() = when (this) {
        CardPosition.Single -> RoundedCornerShape(OuterRadius)
        CardPosition.First -> RoundedCornerShape(
            topStart = OuterRadius, topEnd = OuterRadius, bottomStart = InnerRadius, bottomEnd = InnerRadius,
        )
        CardPosition.Middle -> RoundedCornerShape(InnerRadius)
        CardPosition.Last -> RoundedCornerShape(
            topStart = InnerRadius, topEnd = InnerRadius, bottomStart = OuterRadius, bottomEnd = OuterRadius,
        )
    }

/** Position for the card at [index] of a connected group of [count]. */
fun cardPosition(index: Int, count: Int): CardPosition = when {
    count <= 1 -> CardPosition.Single
    index == 0 -> CardPosition.First
    index == count - 1 -> CardPosition.Last
    else -> CardPosition.Middle
}

@Composable
fun MalachiCard(
    modifier: Modifier = Modifier,
    position: CardPosition = CardPosition.Single,
    color: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Plain surface cards get the hairline; tinted cards already separate from the background by
    // colour alone and would only look noisy with one.
    val border = if (color == MaterialTheme.colorScheme.surface) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        null
    }
    val sized = modifier.fillMaxWidth()
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = position.shape,
            color = color,
            border = border,
            modifier = sized,
            content = content,
        )
    } else {
        Surface(shape = position.shape, color = color, border = border, modifier = sized, content = content)
    }
}

/** Lays out a connected card group; children pass their own [CardPosition]. */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacing.groupGap),
        content = content,
    )
}

/**
 * The one way a screen introduces a section: an eyebrow label in the accent colour, inset to
 * align with card content, with air above so the between-section gap always beats the
 * within-section one.
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, supporting: String? = null) {
    val spacing = Tokens.spacing
    Column(modifier.fillMaxWidth().padding(top = spacing.lg, start = spacing.xs, end = spacing.xs)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
