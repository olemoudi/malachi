package dev.malachi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** How the app resolves light/dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "malachi_theme")

/** Persists the manual theme choice; SYSTEM (follow the device) is the default. */
class ThemeStore(private val context: Context) {
    private val key = stringPreferencesKey("mode")

    /** A damaged file falls back to the default rather than throwing at every reader. */
    val mode: Flow<ThemeMode> = context.themeDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
        prefs[key]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } } ?: ThemeMode.SYSTEM
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[key] = mode.name }
    }
}
