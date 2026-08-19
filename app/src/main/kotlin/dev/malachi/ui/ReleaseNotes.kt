package dev.malachi.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R

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
@Composable
fun ReleaseNotes(vm: MalachiViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    // The device's language, so a bilingual manifest reaches somebody in the language the rest
    // of the app is already speaking to them in.
    val language = LocalConfiguration.current.locales[0].language
    val notes = vm.releaseNotes(settings, language) ?: return

    AlertDialog(
        onDismissRequest = vm::markReleaseNotesSeen,
        title = { Text(stringResource(R.string.notes_title, vm.versionName)) },
        text = { Text(notes, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = vm::markReleaseNotesSeen) { Text(stringResource(R.string.notes_dismiss)) }
        },
    )
}
