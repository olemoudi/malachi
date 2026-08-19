package dev.malachi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Circle
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
 * Dots rather than a glyph with a meaning of its own, because the count is the meaning and a
 * shape that argued with it would win. One dot is deliberately not a warning — it is green, the
 * same green this app already uses for "allowed" — since the safest lists in the catalogue are
 * most of what blocks anything, and an alarm that is always on is furniture.
 */
private fun marksFor(risk: BreakageRisk): Int = when (risk) {
    BreakageRisk.SAFE -> 1
    BreakageRisk.MODERATE -> 2
    BreakageRisk.AGGRESSIVE -> 3
}

/**
 * A traffic light, drawn from the palette's own roles rather than from three literals.
 *
 * `primary` is this app's deep teal and is already what "allowed, nothing to worry about" looks
 * like on these very screens — the allow icon beside each of these rows is tinted with it — so
 * the safe tier is green in the sense that matters: the same green the app already uses to say
 * fine. Amber is reserved here for a warning and red for something wrong, which is exactly the
 * two steps above it.
 */
@Composable
private fun riskTint(risk: BreakageRisk): Color = when (risk) {
    BreakageRisk.SAFE -> MaterialTheme.colorScheme.primary
    BreakageRisk.MODERATE -> MaterialTheme.colorScheme.secondary
    BreakageRisk.AGGRESSIVE -> MaterialTheme.colorScheme.error
}

/**
 * How tall the dots are when they sit beside a label rather than inside a sentence.
 *
 * One value for the legend and for the list picker's tier headings, because those two are the
 * same object seen twice — the scale explained, and the scale being chosen — and a size that
 * drifted between them would read as two different marks.
 */
val RiskMarkHeight = 14.dp

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
 * 200% text a hardcoded 12.dp dot is a speck next to the words it is meant to be marking.
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
            // A square slot, and a dot filling part of it. The slot is what makes the gaps: three
            // of Material's Circle drawn edge to edge read as one caterpillar rather than as a
            // count, and counting is the whole job. The aspect ratio is load-bearing too — an
            // Icon with no width falls back to its own 24.dp whatever box it is in, so inside a
            // placeholder measured in `em` the dots would be drawn over the words beside them.
            Box(Modifier.fillMaxHeight().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Circle,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.fillMaxSize(DOT_SCALE),
                )
            }
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
                    RiskMarks(risk, Modifier.height(RiskMarkHeight))
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

/** Stands in for one dot wherever the glyphs cannot be drawn. */
private const val MARK_ALTERNATE = "•"

/**
 * One dot occupies a square slot of this many `em`, so the placeholder is exactly as wide as the
 * slots that fill it — the width is `marks * MARK_SIDE` and the height is one of them.
 */
private const val MARK_SIDE = 0.9f

/** How much of its slot the dot itself takes; the remainder is the gap that makes it countable. */
private const val DOT_SCALE = 0.62f


