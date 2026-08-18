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
internal fun settingsCorruptionHandler() = ReplaceFileCorruptionHandler { error: Throwable ->
    DebugLog.e("MalachiSettings", "settings file is corrupt; replacing it with defaults", error)
    emptyPreferences()
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "malachi_settings",
    corruptionHandler = settingsCorruptionHandler(),
)

/**
 * Persists [MalachiSettings] as a single JSON blob.
 *
 * A blob rather than a preference per field because the settings are read as a whole on every
 * rebuild of the filter, and because adding a field must not require touching this class at all.
 */
class SettingsStore internal constructor(private val store: DataStore<Preferences>) {

    /** The real one. The [DataStore] constructor is what lets a test damage the file. */
    constructor(context: Context) : this(context.settingsDataStore)

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
    val settings: Flow<MalachiSettings> = store.data
        .catch { error ->
            if (error is IOException) {
                DebugLog.e(TAG, "settings could not be read; falling back to defaults", error)
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        // Anything that isn't an IOException still reaches the collectors, and an uncaught one
        // ends the collection permanently — for every one of them, not just the unlucky one.
        // The tunnel would go on filtering with settings nobody could change again.
        .retryingWithBackoff(RETRY_BASE_MS, RETRY_MAX_SHIFT) { cause, attempt ->
            DebugLog.e(TAG, "settings could not be read (attempt $attempt); retrying", cause)
        }
        .map { decode(it[key]) }

    suspend fun current(): MalachiSettings = settings.first()

    suspend fun update(transform: (MalachiSettings) -> MalachiSettings) {
        store.edit { prefs ->
            prefs[key] = json.encodeToString(serializer, transform(decode(prefs[key])))
        }
    }

    /**
     * The last blob and what it decoded to, so six collectors do not parse it six times.
     *
     * [settings] is a cold flow and half the process collects it — the tunnel, the filter's rule
     * assembly, the list scheduler, the work scheduler, the view model — each of which gets its
     * own `map { decode(...) }`. So every write of a single boolean used to parse the whole
     * settings document once per collector, and the document grows with the user: a few hundred
     * per-app exceptions accumulated one broken app at a time is a real amount of JSON to walk
     * six times for a pause.
     *
     * The comparison is safe and nearly free. DataStore hands the same `Preferences` instance to
     * every collector of one emission, so the strings are the same object and `==` settles on
     * identity; a genuinely new blob is a different string and is parsed once, for the first
     * collector to reach it.
     */
    @Volatile private var lastDecoded: Pair<String, MalachiSettings>? = null

    /**
     * Unreadable settings fall back to the defaults rather than to nothing. The defaults leave
     * filtering *off*, which is the safe direction: a user whose settings were lost gets an app
     * that plainly isn't running, not one that silently blocks their bank.
     */
    private fun decode(raw: String?): MalachiSettings {
        if (raw == null) return MalachiSettings()
        lastDecoded?.let { (text, decoded) -> if (text == raw) return decoded }
        val decoded = runCatching { json.decodeFromString(serializer, raw) }.getOrElse {
            DebugLog.e(TAG, "stored settings are unreadable; falling back to defaults", it)
            MalachiSettings()
        }
        lastDecoded = raw to decoded
        return decoded
    }

    private companion object {
        const val TAG = "MalachiSettings"

        /** 2s, doubling to about a minute. Storage that is unhappy is rarely unhappy briefly. */
        const val RETRY_BASE_MS = 2_000L
        const val RETRY_MAX_SHIFT = 5
    }
}
