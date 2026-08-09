package dev.malachi.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Deep teal, with amber reserved for counts and warnings.
 *
 * Teal because this app's job is quiet and continuous — it should read as infrastructure, not
 * as an alarm — and amber because the two numbers that matter (what was blocked, what got
 * through) need to be legible at a glance against it without becoming red, which in this app
 * has to keep meaning "something is wrong".
 */
private val Teal = Color(0xFF0D7C74)
private val TealLight = Color(0xFF4FD5C7)

// The background sits a clear step below the white cards, and outlineVariant draws the hairline
// borders — together they are what makes surfaces read as raised, since cards carry no shadow.
val MalachiLightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7EFE8),
    onPrimaryContainer = Color(0xFF00312D),
    secondary = Color(0xFFB4611A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1C7),
    onSecondaryContainer = Color(0xFF3B1B00),
    background = Color(0xFFEDF2F1),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFE7EDEC),
    onSurfaceVariant = Color(0xFF4C5A58),
    outline = Color(0xFFC2CDCB),
    outlineVariant = Color(0xFFDFE7E5),
    surfaceDim = Color(0xFFD6DEDC),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F9F8),
    surfaceContainer = Color(0xFFF0F4F3),
    surfaceContainerHigh = Color(0xFFE9EFEE),
    surfaceContainerHighest = Color(0xFFE3EAE9),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410E0A),
)

val MalachiDarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00312D),
    primaryContainer = Color(0xFF0B5C56),
    onPrimaryContainer = Color(0xFFB7EFE8),
    secondary = Color(0xFFF0A055),
    onSecondary = Color(0xFF3B1B00),
    secondaryContainer = Color(0xFF5C3410),
    onSecondaryContainer = Color(0xFFFFE1C7),
    background = Color(0xFF0B1211),
    onBackground = Color(0xFFE2E9E7),
    surface = Color(0xFF151F1E),
    onSurface = Color(0xFFE2E9E7),
    surfaceVariant = Color(0xFF1F2B2A),
    onSurfaceVariant = Color(0xFFA6B4B2),
    outline = Color(0xFF374644),
    outlineVariant = Color(0xFF263332),
    surfaceDim = Color(0xFF0B1211),
    surfaceBright = Color(0xFF31403E),
    surfaceContainerLowest = Color(0xFF060B0A),
    surfaceContainerLow = Color(0xFF111A19),
    surfaceContainer = Color(0xFF151F1E),
    surfaceContainerHigh = Color(0xFF1D2827),
    surfaceContainerHighest = Color(0xFF253231),
    error = Color(0xFFFF6B60),
    onError = Color(0xFF3A0906),
    errorContainer = Color(0xFF6B1A14),
    onErrorContainer = Color(0xFFFFDAD5),
)
