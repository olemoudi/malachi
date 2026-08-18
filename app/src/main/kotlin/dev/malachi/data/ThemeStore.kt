package dev.malachi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.malachi.debug.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** How the app resolves light/dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The same recovery the settings file has, for the same reason.
 *
 * DataStore answers a damaged file by throwing from every read *and every write*, forever,
 * because nothing repairs it on its own. Catching the read side alone — which is all this used to
 * do — leaves an app that shows the default theme and can never store another one: choosing dark
 * writes nothing, and the write throws into a view-model coroutine that has no business dying.
 * Replacing the file costs one preference nobody will miss and is the only outcome here that ends.
 */
internal fun themeCorruptionHandler() = ReplaceFileCorruptionHandler { error: Throwable ->
    DebugLog.e("MalachiTheme", "theme file is corrupt; replacing it with the default", error)
    emptyPreferences()
}

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "malachi_theme",
    corruptionHandler = themeCorruptionHandler(),
)

/** Persists the manual theme choice; SYSTEM (follow the device) is the default. */
class ThemeStore internal constructor(private val store: DataStore<Preferences>) {

    /** The real one. The [DataStore] constructor is what lets a test damage the file. */
    constructor(context: Context) : this(context.themeDataStore)

    private val key = stringPreferencesKey("mode")

    /** A damaged file falls back to the default rather than throwing at every reader. */
    val mode: Flow<ThemeMode> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            prefs[key]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } } ?: ThemeMode.SYSTEM
        }

    suspend fun setMode(mode: ThemeMode) {
        store.edit { it[key] = mode.name }
    }
}
