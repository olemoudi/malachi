package dev.malachi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Every colour the app uses, as plain ARGB numbers.
 *
 * Numbers rather than Compose `Color`s because contrast is arithmetic, and arithmetic belongs in
 * a test: `ThemeContrastTest` reads these and fails the build when a piece of text is not legible
 * against what it sits on. A value that exists only inside a `lightColorScheme(...)` call can be
 * checked by nothing but somebody squinting at a phone, which is how this palette came to ship a
 * card border at 1.26:1 and a glyph at 1.18:1 — both of them, in practice, invisible.
 */
internal data class Roles(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val outlineVariant: Long,
    val surfaceDim: Long,
    val surfaceBright: Long,
    val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,

    /**
     * The two ends of the one gradient in the app, and what is drawn on it.
     *
     * Roles of their own rather than a shade of `primary`, which is what they used to be, because
     * a gradient computed from another role is a background no test can see: in the dark palette
     * `primary` is a bright mint and the card drew white on it at 1.8:1. Named here, the pair is
     * checked like everything else.
     */
    val heroStart: Long,
    val heroEnd: Long,
    val onHero: Long,
)

/**
 * Deep teal, with amber reserved for counts and warnings.
 *
 * Teal because this app's job is quiet and continuous — it should read as infrastructure, not as
 * an alarm — and amber because the two numbers that matter (what was blocked, what got through)
 * need to be legible at a glance without becoming red, which in this app has to keep meaning
 * "something is wrong".
 *
 * The teal is a shade darker than it looks like it wants to be. At its previous value it cleared
 * white by a comfortable margin and the *screen* background by 4.47:1 — under the line, and the
 * section headings are drawn in it, on exactly that background.
 */
internal val LightRoles = Roles(
    primary = 0xFF0C7269,
    onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFB7EFE8,
    onPrimaryContainer = 0xFF00312D,
    secondary = 0xFFA55918,
    onSecondary = 0xFFFFFFFF,
    secondaryContainer = 0xFFFFE1C7,
    onSecondaryContainer = 0xFF3B1B00,
    background = 0xFFEDF2F1,
    onBackground = 0xFF161D1C,
    surface = 0xFFFFFFFF,
    onSurface = 0xFF161D1C,
    surfaceVariant = 0xFFE7EDEC,
    onSurfaceVariant = 0xFF44514F,
    // A component border has to be identifiable, which is 3:1. The hairline below it separates a
    // white card from an almost-white screen and is the only thing that does, so it is drawn far
    // stronger than a decorative divider would be — the cards carry no shadow.
    outline = 0xFF768E8A,
    outlineVariant = 0xFFA4BCB6,
    surfaceDim = 0xFFD6DEDC,
    surfaceBright = 0xFFFFFFFF,
    surfaceContainerLowest = 0xFFFFFFFF,
    surfaceContainerLow = 0xFFF6F9F8,
    surfaceContainer = 0xFFF0F4F3,
    surfaceContainerHigh = 0xFFE9EFEE,
    surfaceContainerHighest = 0xFFE3EAE9,
    error = 0xFFA6231C,
    onError = 0xFFFFFFFF,
    errorContainer = 0xFFFFDAD5,
    onErrorContainer = 0xFF410E0A,
    heroStart = 0xFF0C7269,
    heroEnd = 0xFF06413C,
    onHero = 0xFFFFFFFF,
)

internal val DarkRoles = Roles(
    primary = 0xFF4FD5C7,
    onPrimary = 0xFF00312D,
    primaryContainer = 0xFF0B5C56,
    onPrimaryContainer = 0xFFB7EFE8,
    secondary = 0xFFF0A055,
    onSecondary = 0xFF3B1B00,
    secondaryContainer = 0xFF5C3410,
    onSecondaryContainer = 0xFFFFE1C7,
    background = 0xFF0B1211,
    onBackground = 0xFFE2E9E7,
    surface = 0xFF151F1E,
    onSurface = 0xFFE2E9E7,
    surfaceVariant = 0xFF1F2B2A,
    onSurfaceVariant = 0xFFACBAB8,
    outline = 0xFF556D69,
    outlineVariant = 0xFF3D5250,
    surfaceDim = 0xFF0B1211,
    surfaceBright = 0xFF31403E,
    surfaceContainerLowest = 0xFF060B0A,
    surfaceContainerLow = 0xFF111A19,
    surfaceContainer = 0xFF151F1E,
    surfaceContainerHigh = 0xFF1D2827,
    surfaceContainerHighest = 0xFF253231,
    error = 0xFFFF6B60,
    onError = 0xFF3A0906,
    errorContainer = 0xFF6B1A14,
    onErrorContainer = 0xFFFFDAD5,
    // The mint stays. What changes is what is drawn on it: white on this was 1.8:1 — the state
    // of the filter, the figure, and the pause action all sat on a bright card in a colour that
    // barely showed. Dark content on a light card is what a dark theme does with a light accent
    // anyway, and it keeps the one piece of colour this app has instead of turning it into
    // another dark rectangle.
    heroStart = 0xFF4FD5C7,
    heroEnd = 0xFF2BB5A7,
    onHero = 0xFF00312D,
)

val MalachiLightColors: ColorScheme = lightColorScheme(
    primary = Color(LightRoles.primary),
    onPrimary = Color(LightRoles.onPrimary),
    primaryContainer = Color(LightRoles.primaryContainer),
    onPrimaryContainer = Color(LightRoles.onPrimaryContainer),
    secondary = Color(LightRoles.secondary),
    onSecondary = Color(LightRoles.onSecondary),
    secondaryContainer = Color(LightRoles.secondaryContainer),
    onSecondaryContainer = Color(LightRoles.onSecondaryContainer),
    background = Color(LightRoles.background),
    onBackground = Color(LightRoles.onBackground),
    surface = Color(LightRoles.surface),
    onSurface = Color(LightRoles.onSurface),
    surfaceVariant = Color(LightRoles.surfaceVariant),
    onSurfaceVariant = Color(LightRoles.onSurfaceVariant),
    outline = Color(LightRoles.outline),
    outlineVariant = Color(LightRoles.outlineVariant),
    surfaceDim = Color(LightRoles.surfaceDim),
    surfaceBright = Color(LightRoles.surfaceBright),
    surfaceContainerLowest = Color(LightRoles.surfaceContainerLowest),
    surfaceContainerLow = Color(LightRoles.surfaceContainerLow),
    surfaceContainer = Color(LightRoles.surfaceContainer),
    surfaceContainerHigh = Color(LightRoles.surfaceContainerHigh),
    surfaceContainerHighest = Color(LightRoles.surfaceContainerHighest),
    error = Color(LightRoles.error),
    onError = Color(LightRoles.onError),
    errorContainer = Color(LightRoles.errorContainer),
    onErrorContainer = Color(LightRoles.onErrorContainer),
)

val MalachiDarkColors: ColorScheme = darkColorScheme(
    primary = Color(DarkRoles.primary),
    onPrimary = Color(DarkRoles.onPrimary),
    primaryContainer = Color(DarkRoles.primaryContainer),
    onPrimaryContainer = Color(DarkRoles.onPrimaryContainer),
    secondary = Color(DarkRoles.secondary),
    onSecondary = Color(DarkRoles.onSecondary),
    secondaryContainer = Color(DarkRoles.secondaryContainer),
    onSecondaryContainer = Color(DarkRoles.onSecondaryContainer),
    background = Color(DarkRoles.background),
    onBackground = Color(DarkRoles.onBackground),
    surface = Color(DarkRoles.surface),
    onSurface = Color(DarkRoles.onSurface),
    surfaceVariant = Color(DarkRoles.surfaceVariant),
    onSurfaceVariant = Color(DarkRoles.onSurfaceVariant),
    outline = Color(DarkRoles.outline),
    outlineVariant = Color(DarkRoles.outlineVariant),
    surfaceDim = Color(DarkRoles.surfaceDim),
    surfaceBright = Color(DarkRoles.surfaceBright),
    surfaceContainerLowest = Color(DarkRoles.surfaceContainerLowest),
    surfaceContainerLow = Color(DarkRoles.surfaceContainerLow),
    surfaceContainer = Color(DarkRoles.surfaceContainer),
    surfaceContainerHigh = Color(DarkRoles.surfaceContainerHigh),
    surfaceContainerHighest = Color(DarkRoles.surfaceContainerHighest),
    error = Color(DarkRoles.error),
    onError = Color(DarkRoles.onError),
    errorContainer = Color(DarkRoles.errorContainer),
    onErrorContainer = Color(DarkRoles.onErrorContainer),
)
