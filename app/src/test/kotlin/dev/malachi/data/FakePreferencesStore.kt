package dev.malachi.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * DataStore's contract without its file.
 *
 * [SettingsStore] takes a `DataStore<Preferences>` so a test can damage the real one on a device;
 * the same seam serves here for the questions that are about the store's own behaviour rather
 * than about storage — how many times a write is decoded, and what the collectors downstream do
 * with it. A StateFlow is the right stand-in because the real thing behaves the same way in the
 * one respect these tests lean on: one write, one value, handed to every collector as the same
 * object.
 */
class FakePreferencesStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}
