package dev.malachi.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.data.Backup

/** The two things a person can do with a backup, wired to the system's own file picker. */
class BackupActions(val export: () -> Unit, val import: () -> Unit)

/**
 * Says how an export or an import went, once.
 *
 * A toast rather than something of our own, because it has to be visible from whichever screen
 * started the file picker and has to survive that screen being recomposed on the way back. It
 * names the counts — "47 rules and 6 lists" — since the failure this guards against is silently
 * restoring the wrong file over a year of work, and a number is the only way to notice.
 */
@Composable
fun BackupMessage(vm: MalachiViewModel) {
    val message by vm.backupMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        vm.clearBackupMessage()
    }
}

/**
 * Export and import through the storage access framework, which is the only honest way to do
 * this: the file lands where the user says it lands — their downloads, their cloud drive, a USB
 * stick — and the app needs no storage permission and never gets to see anything else. A backup
 * this app filed away in its own folder would be lost with the app, which is the one moment it
 * exists for.
 */
@Composable
fun rememberBackupActions(vm: MalachiViewModel): BackupActions {
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::exportBackup) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importBackup) }

    return remember(exportLauncher, importLauncher) {
        BackupActions(
            export = { exportLauncher.launch(Backup.suggestedFileName(System.currentTimeMillis())) },
            // More than one type, because a `.json` that has been round-tripped through a cloud
            // drive or a chat app comes back as text/plain or as a stream of bytes, and a picker
            // that greys out the user's own backup is a dead end they cannot argue with.
            import = {
                importLauncher.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream"),
                )
            },
        )
    }
}
