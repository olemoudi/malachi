package dev.malachi.data

import android.content.ContentResolver
import android.net.Uri
import dev.malachi.debug.DebugLog

/**
 * Reading and writing a backup through whatever the user picked in the system's file picker.
 *
 * Its own class, and thin on purpose: everything here is a call into a `content://` provider that
 * cannot be reasoned about from a unit test, so this is the piece an instrumented test drives
 * against a real provider. What it must never become is a place where the format is decided —
 * that is [Backup], which is pure and tested without a device.
 */
class BackupStore(private val resolver: ContentResolver) {

    /**
     * Writes [text], replacing whatever the document held.
     *
     * The mode is `"wt"`, and the `t` is the whole point: without it the provider opens for
     * writing *without truncating*, so a shorter backup written over a longer one leaves the tail
     * of the old file behind. The result parses as nothing and is discovered on the day it is
     * needed, which is the one day it must not be.
     */
    fun write(uri: Uri, text: String): Boolean = runCatching {
        resolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: error("the picked document could not be opened for writing")
        true
    }.onFailure { DebugLog.w(TAG, "could not write the backup: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrDefault(false)

    /** The document's text, or null when it could not be read at all. */
    fun read(uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("the picked document could not be opened for reading")
    }.onFailure { DebugLog.w(TAG, "could not read the backup: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()

    private companion object {
        const val TAG = "MalachiBackup"
    }
}
