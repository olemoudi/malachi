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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * The corruption handler is what makes "damaged settings" recoverable rather than terminal.
 *
 * DataStore surfaces an unreadable file by throwing from every read *and every write*, forever,
 * because nothing repairs it on its own. Catching it on the read side alone — which is all this
 * used to do — produces an app that shows its defaults and cannot save anything: the filter
 * reads as off, turning it back on writes nothing, and the next launch says off again. Replacing
 * the file with empty preferences loses the user's rules once, which is bad, and is the only
 * outcome here that ends.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "malachi_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { error ->
        DebugLog.e("MalachiSettings", "settings file is corrupt; replacing it with defaults", error)
        emptyPreferences()
    },
)

/**
 * Persists [MalachiSettings] as a single JSON blob.
 *
 * A blob rather than a preference per field because the settings are read as a whole on every
 * rebuild of the filter, and because adding a field must not require touching this class at all.
 */
class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("settings_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = MalachiSettings.serializer()

    /**
     * The settings, and a promise that reading them cannot take the app down.
     *
     * DataStore surfaces a damaged file by throwing [IOException] from this flow — on every
     * read, forever, because the file does not repair itself. Uncaught, that is not a bad
     * session: it is an app that crashes on launch and keeps crashing, from one interrupted
     * write months into an install. Falling back to empty preferences means falling back to the
     * defaults, which leave filtering off: visibly not running, rather than silently not working.
     */
    val settings: Flow<MalachiSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) {
                DebugLog.e(TAG, "settings could not be read; falling back to defaults", error)
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { decode(it[key]) }

    suspend fun current(): MalachiSettings = settings.first()

    suspend fun update(transform: (MalachiSettings) -> MalachiSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(serializer, transform(decode(prefs[key])))
        }
    }

    /**
     * Unreadable settings fall back to the defaults rather than to nothing. The defaults leave
     * filtering *off*, which is the safe direction: a user whose settings were lost gets an app
     * that plainly isn't running, not one that silently blocks their bank.
     */
    private fun decode(raw: String?): MalachiSettings {
        if (raw == null) return MalachiSettings()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse {
            DebugLog.e(TAG, "stored settings are unreadable; falling back to defaults", it)
            MalachiSettings()
        }
    }

    private companion object {
        const val TAG = "MalachiSettings"
    }
}
