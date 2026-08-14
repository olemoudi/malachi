package dev.malachi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.malachi.data.ThemeMode

private val MalachiTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** The big figure on the home card. */
val NumberDisplay = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-1).sp)

/**
 * The line that says what [NumberDisplay] is a figure *of*.
 *
 * Its own style because the default it used — 12sp at regular weight, and translucent on top of
 * that — was reported as looking "very thin", and it was: under 44sp of bold, a caption has to
 * hold its own or the number reads as unlabelled. Bigger, heavier, and fully opaque, which is
 * also the only way the palette's checked contrast survives to the screen.
 */
val NumberCaption = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.1.sp)

/**
 * A domain is a technical string; it reads far better when the glyphs line up.
 *
 * Medium rather than regular: this is the *title* of every row in the query log, sitting above a
 * secondary line, and a monospace face at regular weight is visibly lighter than the proportional
 * one beside it at the same size — so the row's most important text read as its least. At 13sp
 * it was also only one point clear of the supporting line under it, which is not a hierarchy.
 */
val MonoSmall = TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontSize = 14.sp,
    fontWeight = FontWeight.Medium,
)

/** Whether this preference renders dark right now (SYSTEM follows the device). */
@Composable
fun ThemeMode.resolvesToDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun MalachiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalMotion provides Motion(),
        LocalRoles provides if (darkTheme) DarkRoles else LightRoles,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) MalachiDarkColors else MalachiLightColors,
            typography = MalachiTypography,
            content = content,
        )
    }
}

/** Convenient token access from any composable. */
object Tokens {
    val spacing: Spacing
        @Composable get() = LocalSpacing.current
    val motion: Motion
        @Composable get() = LocalMotion.current

    /**
     * The one gradient in the app, on the one card that answers "is it on?". Reserved for that
     * card so it stays a signal rather than decoration.
     */
    val heroBrush: Brush
        @Composable get() {
            val roles = LocalRoles.current
            return Brush.linearGradient(listOf(Color(roles.heroStart), Color(roles.heroEnd)))
        }

    /** What is legible on [heroBrush]. Checked against both ends of it by ThemeContrastTest. */
    val onHero: Color
        @Composable get() = Color(LocalRoles.current.onHero)

    /** The deep end of [heroBrush], for a filled control that has to sit on the gradient. */
    val heroContainer: Color
        @Composable get() = Color(LocalRoles.current.heroEnd)
}
