package dev.malachi.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One spacing scale; screens never reach for a loose magic dp value. */
data class Spacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val screen: Dp = 20.dp,
    /** Gap between cards of the same connected group (see CardPosition). */
    val groupGap: Dp = 2.dp,
)

/** Motion tokens: short and purposeful — nothing here exceeds a quarter of a second. */
data class Motion(
    val fast: Int = 140,
    val medium: Int = 220,
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
val LocalMotion = staticCompositionLocalOf { Motion() }

/**
 * The palette behind the current theme.
 *
 * Provided rather than derived from `isSystemInDarkTheme()`, because the theme is the user's
 * setting and can be forced against the system's — anything reading the system directly is right
 * two times out of three.
 */
internal val LocalRoles = staticCompositionLocalOf { LightRoles }
