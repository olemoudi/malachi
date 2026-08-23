package dev.malachi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * What changed, said once, on the launch after a version installed itself.
 *
 * This app updates without asking — that is the whole point of a sideloaded blocker keeping its
 * own lists current — so there is no moment *before* an update at which "here is what is about
 * to change" could be read. What there is instead is the moment after: the app comes back as a
 * version its owner did not choose, and it should be able to say what happened.
 *
 * Shown for the *installed* version only, and marked as seen the moment it is dismissed. The
 * notes travel in the channel manifest rather than in the app, so a release can explain itself
 * without a release of the explaining.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotes(vm: MalachiViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    // The device's language, so a bilingual manifest reaches somebody in the language the rest
    // of the app is already speaking to them in.
    val language = LocalConfiguration.current.locales[0].language
    val notes = vm.releaseNotes(settings, language) ?: return
    val lines = remember(notes) { releaseNoteLines(notes) }

    val spacing = Tokens.spacing
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    // Let the sheet slide out before the state that holds it goes away: marking the notes seen
    // straight from the button removes this composable on the same frame, which reads as the
    // screen blinking rather than as a sheet closing. A swipe has already played its animation
    // by the time onDismissRequest arrives, so that path marks it directly.
    val dismiss = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { vm.markReleaseNotesSeen() }
        Unit
    }

    ModalBottomSheet(onDismissRequest = vm::markReleaseNotesSeen, sheetState = sheetState) {
        // The button sits OUTSIDE the scrolling part: a release with eight things to say would
        // otherwise push the only way out below the fold, and the way out of something that
        // opened by itself must not be a thing you go looking for.
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = spacing.screen)
                .padding(bottom = spacing.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(
                // fill = false so a two-line release still wraps to its own height instead of
                // stretching the sheet to the full screen.
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text(
                    stringResource(R.string.notes_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        vm.versionName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    lines.forEach { line ->
                        if (line.bullet) {
                            Row(Modifier.fillMaxWidth()) {
                                Text("•", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(spacing.sm))
                                Text(line.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Text(line.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Button(onClick = dismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.notes_dismiss))
            }
        }
    }
}

/** One line of a release note: a paragraph, or an item of the list under it. */
internal data class NoteLine(val text: String, val bullet: Boolean)

/**
 * The notes as the sheet lays them out.
 *
 * They arrive as one string, because that is what a channel manifest carries and what every
 * already-installed copy of the app knows how to read — so the structure is recovered here
 * rather than added to the manifest. A line that opens with a bullet is drawn as one, with the
 * glyph in its own column so a wrapped item lines up under its own text instead of under the
 * bullet; everything else is a paragraph. Blank lines are separators, not content.
 */
internal fun releaseNoteLines(notes: String): List<NoteLine> =
    notes.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val bullet = BULLET_PREFIXES.firstOrNull { line.startsWith(it) }
            if (bullet == null) NoteLine(line, bullet = false)
            else NoteLine(line.removePrefix(bullet).trim(), bullet = true)
        }

private val BULLET_PREFIXES = listOf("•", "- ", "* ")
