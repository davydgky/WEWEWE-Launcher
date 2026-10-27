package io.github.davydgky.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class LauncherPrefs(
    /** Ids of search sources the user switched off. All sources are on by default. */
    val disabledSources: Set<String> = emptySet(),
    /** Dock of the first version; only read once to carry it over into the home screen arrangement. */
    val dock: List<String>? = null,
    /** Folder trees (SAF URIs) whose text files are searched as notes. */
    val noteFolders: Set<String> = emptySet(),
    /** Newly installed apps get a place on the home screen; otherwise they are only in the App Library. */
    val addNewAppsToHome: Boolean = true,
    /** The hint in the search asking for usage access was dismissed. */
    val usageHintDismissed: Boolean = false,
    /** Download updates on Wi-Fi and install them while the home screen is not in use. */
    val autoUpdate: Boolean = true,
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
                addNewAppsToHome = prefs[AddNewAppsKey] ?: true,
                usageHintDismissed = prefs[UsageHintDismissedKey] ?: false,
                autoUpdate = prefs[AutoUpdateKey] ?: true,
            )
        }
        .distinctUntilChanged()

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        dataStore.edit {
            val current = it[DisabledSourcesKey].orEmpty()
            it[DisabledSourcesKey] = if (enabled) current - sourceId else current + sourceId
        }
    }

    suspend fun addNoteFolder(uri: String) {
        dataStore.edit { it[NoteFoldersKey] = it[NoteFoldersKey].orEmpty() + uri }
    }

    suspend fun removeNoteFolder(uri: String) {
        dataStore.edit { it[NoteFoldersKey] = it[NoteFoldersKey].orEmpty() - uri }
    }

    suspend fun setAddNewAppsToHome(enabled: Boolean) {
        dataStore.edit { it[AddNewAppsKey] = enabled }
    }

    suspend fun dismissUsageHint() {
        dataStore.edit { it[UsageHintDismissedKey] = true }
    }

    suspend fun setAutoUpdate(enabled: Boolean) {
        dataStore.edit { it[AutoUpdateKey] = enabled }
    }

    suspend fun lastUpdateCheck(): Long = dataStore.data.first()[LastUpdateCheckKey] ?: 0L

    suspend fun setLastUpdateCheck(timeMillis: Long) {
        dataStore.edit { it[LastUpdateCheckKey] = timeMillis }
    }

    private companion object {
        val DisabledSourcesKey = stringSetPreferencesKey("disabled_sources")
        val DockKey = stringPreferencesKey("dock")
        val NoteFoldersKey = stringSetPreferencesKey("note_folders")
        val AddNewAppsKey = booleanPreferencesKey("add_new_apps_to_home")
        val UsageHintDismissedKey = booleanPreferencesKey("usage_hint_dismissed")
        val AutoUpdateKey = booleanPreferencesKey("auto_update")
        val LastUpdateCheckKey = longPreferencesKey("last_update_check")
    }
}
