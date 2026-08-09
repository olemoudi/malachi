package dev.malachi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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

/** A domain is a technical string; it reads far better when the glyphs line up. */
val MonoSmall = TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontSize = 13.sp,
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
            val primary = MaterialTheme.colorScheme.primary
            return Brush.linearGradient(listOf(primary, lerp(primary, Color(0xFF042E2B), 0.45f)))
        }
}
