package dev.malachi.ui.theme

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether the app can actually be read.
 *
 * Contrast is one of the few things about a design that is not a matter of taste: WCAG defines
 * it as a ratio between two relative luminances, and either a pair clears the threshold or it
 * does not. Leaving it to the eye is how this palette shipped a card border at 1.26:1, a glyph
 * at 1.18:1 and section headings at 4.47:1 — the first two invisible, the third the one somebody
 * actually complained about.
 *
 * The thresholds are WCAG 2.2 AA: 4.5:1 for text, 3:1 for text at 18.66px bold or 24px and for
 * anything non-text that has to be identifiable. Where a pair is held above the line it is
 * because the text is small and the line is a floor, not a target.
 */
class ThemeContrastTest {

    /** One rule: this colour, on that one, must clear [minimum]. */
    private data class Pair(
        val what: String,
        val foreground: (Roles) -> Long,
        val background: (Roles) -> Long,
        val minimum: Double,
    )

    private val rules = listOf(
        // ---- text -------------------------------------------------------------------------
        Pair("body text on the screen", { it.onBackground }, { it.background }, 4.5),
        Pair("body text on a card", { it.onSurface }, { it.surface }, 4.5),
        // Secondary text carries most of the explaining this app does, and most of it is small.
        // Held well above the floor on purpose.
        Pair("secondary text on a card", { it.onSurfaceVariant }, { it.surface }, 7.0),
        Pair("secondary text on the screen", { it.onSurfaceVariant }, { it.background }, 6.0),
        Pair("secondary text on a variant surface", { it.onSurfaceVariant }, { it.surfaceVariant }, 6.0),
        Pair("secondary text on the highest container", { it.onSurfaceVariant }, { it.surfaceContainerHighest }, 6.0),
        Pair("secondary text on a container", { it.onSurfaceVariant }, { it.surfaceContainer }, 6.0),
        // The accent is a text colour here, not just a fill: section headings, the value at the
        // end of a row, the figure on the statistics card.
        Pair("accent text on a card", { it.primary }, { it.surface }, 4.5),
        Pair("accent text on the screen", { it.primary }, { it.background }, 4.5),
        Pair("accent text on a container", { it.primary }, { it.surfaceContainer }, 4.5),
        Pair("the amber accent on a card", { it.secondary }, { it.surface }, 4.5),
        Pair("the error accent on a card", { it.error }, { it.surface }, 4.5),
        Pair("the error accent on the screen", { it.error }, { it.background }, 4.5),
        Pair("text on the accent", { it.onPrimary }, { it.primary }, 4.5),
        Pair("text on the amber", { it.onSecondary }, { it.secondary }, 4.5),
        Pair("text on the error", { it.onError }, { it.error }, 4.5),
        Pair("text on the accent container", { it.onPrimaryContainer }, { it.primaryContainer }, 4.5),
        Pair("text on the amber container", { it.onSecondaryContainer }, { it.secondaryContainer }, 4.5),
        Pair("text on the error container", { it.onErrorContainer }, { it.errorContainer }, 4.5),

        // ---- everything that is not text but still has to be seen ---------------------------
        Pair("a component border on a card", { it.outline }, { it.surface }, 3.0),
        Pair("a component border on the screen", { it.outline }, { it.background }, 3.0),
        Pair("the blocked/allowed glyph on a card", { it.error }, { it.surface }, 3.0),
        Pair("the proportion bar against its track", { it.primary }, { it.surfaceContainerHighest }, 3.0),
        Pair("a chart bar against the card it sits on", { it.primary }, { it.surface }, 3.0),
    )

    /**
     * The card hairline is the only thing separating a white card from an almost-white screen —
     * the cards carry no shadow — so it is held to a floor of its own. Not 3:1: a border that
     * dark reads as a box rather than a card, and the separation is reinforced by the fill.
     */
    private val hairline = 1.7

    @Test
    fun `the light palette can be read`() = assertAll(LightRoles, "light")

    @Test
    fun `the dark palette can be read`() = assertAll(DarkRoles, "dark")

    @Test
    fun `the card hairline is visible in both palettes`() {
        for ((roles, name) in listOf(LightRoles to "light", DarkRoles to "dark")) {
            for (behind in listOf(roles.surface to "a card", roles.background to "the screen")) {
                val ratio = contrast(roles.outlineVariant, behind.first)
                assertTrue(
                    ratio >= hairline,
                    "$name: the card hairline against ${behind.second} is %.2f:1, below $hairline".format(ratio),
                )
            }
        }
    }

    @Test
    fun `the two palettes describe the same design`() {
        // Not a contrast rule, a consistency one: a role that exists in one and not the other is
        // a screen that looks finished in the dark and unfinished in the light.
        assertTrue(LightRoles.javaClass == DarkRoles.javaClass)
        // And nothing is left at its Compose default by accident.
        val fields = Roles::class.java.declaredFields.filter { it.type == Long::class.java }
        assertTrue(fields.size >= 27, "the palette lost a role: ${fields.size}")
    }

    @Test
    fun `the ratio is computed the way WCAG defines it`() {
        // The reference values from the specification, so a wrong formula fails here rather than
        // quietly passing everything above.
        assertNear(21.0, contrast(0xFFFFFFFF, 0xFF000000))
        assertNear(1.0, contrast(0xFF808080, 0xFF808080))
        assertNear(4.6, contrast(0xFF767676, 0xFFFFFFFF))
    }

    // -------------------------------------------------------------------------------------

    private fun assertAll(roles: Roles, name: String) {
        val failures = rules.mapNotNull { rule ->
            val ratio = contrast(rule.foreground(roles), rule.background(roles))
            if (ratio >= rule.minimum) null else "%s: %s is %.2f:1, below %.1f".format(name, rule.what, ratio, rule.minimum)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun assertNear(expected: Double, actual: Double) =
        assertTrue(kotlin.math.abs(expected - actual) < 0.1, "expected ~$expected, was $actual")

    /** WCAG 2.2 relative luminance, on sRGB with the alpha ignored — nothing here is translucent. */
    private fun luminance(argb: Long): Double {
        fun channel(value: Long): Double {
            val v = value / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    private fun contrast(a: Long, b: Long): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
