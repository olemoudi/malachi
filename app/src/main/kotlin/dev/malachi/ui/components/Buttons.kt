package dev.malachi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.malachi.ui.theme.Tokens

/**
 * The two shapes an action takes in this app, and nothing else.
 *
 * Everything that can be tapped used to be a `TextButton`, which is a label with a ripple: on a
 * screen made of cards full of labels there was nothing to tell the two apart except trying. Both
 * of these carry a container or a border, so an action looks like an action before it is touched.
 *
 * Both take their colours from whatever they sit on rather than from `primary`. That is not
 * decoration — a `TextButton` draws itself in `primary`, and the pause action sat on a teal
 * gradient, so it was teal on teal at about 1:1. Passing the surface's own foreground makes the
 * contrast the same one the theme already guarantees for text on that surface.
 */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The colour of the text *around* this button; the button inverts it. */
    onContainer: Color = MaterialTheme.colorScheme.onSurface,
    container: Color = MaterialTheme.colorScheme.surface,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        // Inverted on purpose: the filled button's contrast is then exactly the container's own
        // text contrast, which the palette already holds above 4.5:1.
        colors = ButtonDefaults.buttonColors(containerColor = onContainer, contentColor = container),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(text, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/**
 * How strongly this button's border is drawn against its own text colour.
 *
 * A named constant because it is the one translucent thing left in the app that has to be
 * *seen*, and a blended colour is invisible to a contrast rule written over the palette. At
 * 0.55 it came to 2.87:1 on the hero gradient — under the 3:1 this project holds every other
 * border to, and 0.65 still left the dark palette's own gradient at 2.96:1. ThemeContrastTest
 * checks the blend at this value against every surface the button is drawn on.
 */
internal const val SECONDARY_BORDER_ALPHA = 0.7f

/** The quieter of the two: a border rather than a fill, for the action you probably don't want. */
@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onContainer: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = onContainer),
        border = BorderStroke(1.dp, onContainer.copy(alpha = SECONDARY_BORDER_ALPHA)),
    ) {
        Text(text, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/**
 * A column of choices, each one a button.
 *
 * Used where a dialog offers several things to do rather than a yes and a no. As stacked text
 * these read as a paragraph somebody had failed to format; as buttons they read as the list of
 * choices they are.
 */
@Composable
fun ActionChoices(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
        content()
    }
}
