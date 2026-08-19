package dev.malachi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.malachi.R
import dev.malachi.lists.BreakageRisk
import dev.malachi.ui.theme.Tokens

/**
 * How much the list that blocked a name is known to cost, marked beside the verdict naming it.
 *
 * The Lists screen already groups by [BreakageRisk] and says what each tier means — but that is
 * a decision made once, months before the day something breaks. On the day itself the user is
 * looking at a domain and a list's name, and the question is the one the catalogue already
 * answered and then never repeated: *is this a list that does this, or have I found something
 * odd?* "Blocked by HaGeZi Pro" and "blocked by AdAway" are the same sentence and very different
 * news, and nothing on these screens said so.
 *
 * The count carries the meaning and the colour only reinforces it. One mark is deliberately not
 * a warning: a glyph that read as one would put an alarm against the safest lists in the
 * catalogue, which is most of what blocks anything, and an alarm that is always on is furniture.
 */
private fun marksFor(risk: BreakageRisk): Int = when (risk) {
    BreakageRisk.SAFE -> 1
    BreakageRisk.MODERATE -> 2
    BreakageRisk.AGGRESSIVE -> 3
}

/** Muted, amber, red — and amber already means a warning in this palette. */
@Composable
private fun riskTint(risk: BreakageRisk): Color = when (risk) {
    BreakageRisk.SAFE -> MaterialTheme.colorScheme.onSurfaceVariant
    BreakageRisk.MODERATE -> MaterialTheme.colorScheme.secondary
    BreakageRisk.AGGRESSIVE -> MaterialTheme.colorScheme.error
}

internal fun riskLabel(risk: BreakageRisk) = when (risk) {
    BreakageRisk.SAFE -> R.string.lists_risk_safe
    BreakageRisk.MODERATE -> R.string.lists_risk_moderate
    BreakageRisk.AGGRESSIVE -> R.string.lists_risk_aggressive
}

/**
 * The marks themselves, filling whatever box they are given rather than a fixed size.
 *
 * Sized by the caller — an `em` placeholder inline in a sentence, a dp height in the legend — so
 * that beside text they grow with the font scale exactly as the sentence they qualify does. At
 * 200% text a hardcoded 12.dp glyph is a speck next to the words it is meant to be marking.
 */
@Composable
fun RiskMarks(risk: BreakageRisk, modifier: Modifier = Modifier) {
    val tint = riskTint(risk)
    // One description for the group rather than one per glyph: TalkBack reading "bolt, bolt"
    // says nothing, and the tier already has a sentence written for it on the Lists screen.
    val label = stringResource(riskLabel(risk))
    Row(
        modifier.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(marksFor(risk)) {
            // Square, derived from the height it was given. Without the aspect ratio an Icon
            // falls back to its own 24.dp width whatever box it is in, so three of them inside a
            // placeholder measured in `em` would be three times wider than the hole they sit in
            // — which is not a clipped glyph, it is a mark drawn over the words beside it.
            Icon(
                Icons.Filled.Bolt,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            )
        }
    }
}

/**
 * A verdict line with the blocking list's risk marked at the end of it.
 *
 * Inline rather than on a line of its own, because the marks qualify the list's name and a
 * verdict that already wraps to three lines cannot afford a fourth. A null [risk] — the user's
 * own rule caught it, or the catalogue no longer carries that list — renders the plain sentence
 * and nothing else, which is also what keeps this safe to use on every verdict everywhere.
 */
@Composable
fun VerdictLine(
    text: String,
    risk: BreakageRisk?,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    // if/else rather than an early return, and that is not style. The remembers below would
    // otherwise sit after a conditional exit from the same call site — and this condition really
    // does flip on a row that is on screen: writing an allow rule turns a list's verdict into
    // the user's own, which has no risk tier. Two branches give the two shapes their own groups.
    if (risk == null) {
        Text(text, style = style, color = color, modifier = modifier)
    } else {
        // Remembered because this sits inside scrolling list rows: without it every frame of a
        // flung list rebuilds an annotated string and a map per visible row, for a sentence that
        // has not changed.
        val marks = marksFor(risk)
        val annotated = remember(text, marks) {
            buildAnnotatedString {
                append(text)
                append(' ')
                // What a copy, or anything reading the text without drawing it, gets instead.
                appendInlineContent(RISK_SLOT, MARK_ALTERNATE.repeat(marks))
            }
        }
        val inline = remember(risk, marks) {
            mapOf(
                RISK_SLOT to InlineTextContent(
                    Placeholder(
                        width = (marks * MARK_SIDE).em,
                        height = MARK_SIDE.em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                    ),
                ) { RiskMarks(risk, Modifier.fillMaxSize()) },
            )
        }
        Text(annotated, modifier = modifier, style = style, color = color, inlineContent = inline)
    }
}

/**
 * What the marks mean, said once above the list they annotate.
 *
 * The words are the Lists screen's own — the same three sentences that named the tiers when the
 * list was chosen — so the scale cannot come to mean two things in two places.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RiskLegend(modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    Column(modifier.padding(horizontal = spacing.xs)) {
        Text(
            stringResource(R.string.risk_legend),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
            modifier = Modifier.padding(top = spacing.xs),
        ) {
            BreakageRisk.entries.forEach { risk ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RiskMarks(risk, Modifier.height(LEGEND_MARK_HEIGHT))
                    Spacer(Modifier.width(spacing.xs))
                    Text(
                        stringResource(riskLabel(risk)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val RISK_SLOT = "risk"

/** Stands in for one mark wherever the glyphs cannot be drawn. */
private const val MARK_ALTERNATE = "!"

/**
 * One mark is a square of this many `em`, so the placeholder is exactly as wide as the marks
 * that fill it. The two must stay equal — the glyphs are square by construction.
 */
private const val MARK_SIDE = 1.05f

private val LEGEND_MARK_HEIGHT = 14.dp
