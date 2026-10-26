package io.github.davydgky.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class LauncherPrefs(
    /** Ids of search sources the user switched off. All sources are on by default. */
    val disabledSources: Set<String> = emptySet(),
    /** App keys in the dock, in order; null until the user changes the dock. */
    val dock: List<String>? = null,
    /** Folder trees (SAF URIs) whose text files are searched as notes. */
    val noteFolders: Set<String> = emptySet(),
)

class LauncherSettings(private val dataStore: DataStore<Preferences>) {

    val prefs: Flow<LauncherPrefs> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            LauncherPrefs(
                disabledSources = prefs[DisabledSourcesKey].orEmpty(),
                // App keys never contain line breaks.
                dock = prefs[DockKey]?.split('\n')?.filter { it.isNotBlank() },
                noteFolders = prefs[NoteFoldersKey].orEmpty(),
            )
        }
        .distinctUntilChanged()

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        dataStore.edit {
            val current = it[DisabledSourcesKey].orEmpty()
            it[DisabledSourcesKey] = if (enabled) current - sourceId else current + sourceId
        }
    }

    suspend fun setDock(keys: List<String>) {
        dataStore.edit { it[DockKey] = keys.joinToString("\n") }
    }

    suspend fun addNoteFolder(uri: String) {
        dataStore.edit { it[NoteFoldersKey] = it[NoteFoldersKey].orEmpty() + uri }
    }

    suspend fun removeNoteFolder(uri: String) {
        dataStore.edit { it[NoteFoldersKey] = it[NoteFoldersKey].orEmpty() - uri }
    }

    private companion object {
        val DisabledSourcesKey = stringSetPreferencesKey("disabled_sources")
        val DockKey = stringPreferencesKey("dock")
        val NoteFoldersKey = stringSetPreferencesKey("note_folders")
    }
}
