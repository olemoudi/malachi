package dev.malachi.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.malachi.MalachiApplication
import dev.malachi.R
import dev.malachi.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Sending a backup somewhere else — a chat, a mail to oneself, a cloud drive's own app.
 *
 * The other half of "save a copy", and for a lot of people the half they will actually use: a
 * file in Downloads is on the phone that is about to be lost, while one sent to an inbox is not.
 *
 * The file is written to the cache rather than kept: it is a copy on its way out, the system may
 * reclaim it at any time, and the directory is emptied before each share so this can never become
 * a pile of stale backups nobody knows about.
 */
object BackupSharing {

    private const val DIRECTORY = "backups"

    /** The authority declared in the manifest; see res/xml/file_paths.xml. */
    private fun authority(context: Context) = "${context.packageName}.files"

    /**
     * Writes the backup and opens the share sheet, or returns false if it could not be written.
     *
     * The chooser is given somewhere to report back to ([BackupSharedReceiver]), because whether
     * the user actually sent it is the difference between a backup existing and a reminder having
     * been silenced for one that does not.
     */
    fun share(context: Context, backup: Backup): Boolean = runCatching {
        val directory = File(context.cacheDir, DIRECTORY)
        directory.deleteRecursively()
        directory.mkdirs()
        val file = File(directory, Backup.suggestedFileName(backup.exportedAtMs))
        file.writeText(Backup.encode(backup))

        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val callback = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, BackupSharedReceiver::class.java),
            // Mutable because the system is the one that fills in which app was chosen. An
            // immutable one comes back empty and we would never know the share happened.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val chooser = Intent.createChooser(send, context.getString(R.string.backup_share_title), callback.intentSender)
            // Started from a context that is not an activity — the view model holds the
            // application — so the sheet needs its own task.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(chooser)
        true
    }.onFailure { DebugLog.w(TAG, "could not share the backup: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrDefault(false)

    internal const val TAG = "MalachiBackup"
}

/**
 * Fires when the user picks an app from the share sheet, which is the only honest moment to call
 * a shared backup saved. Dismissing the sheet sends nothing and reaches nothing here, so the
 * reminder stays exactly where it was.
 */
class BackupSharedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MalachiApplication ?: return
        val chosen = intent.getParcelableExtra<android.content.ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)
        DebugLog.i(BackupSharing.TAG, "a backup was shared with ${chosen?.packageName ?: "another app"}")
        // Its own scope: this receiver outlives nothing, and the write is a suspend call on
        // DataStore that must not be tied to whatever was on screen when the sheet opened.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { app.settingsStore.update { BackupPolicy.backedUp(it) } }
                .onFailure { DebugLog.w(BackupSharing.TAG, "could not record the shared backup", it) }
        }
    }
}
