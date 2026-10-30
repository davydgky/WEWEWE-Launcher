package io.github.davydgky.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
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
    /** A free place in the dock shows a suggested app. */
    val dockSuggestion: Boolean = true,
    /** Results stack upwards from the search field, so the best one is within reach of the thumb. */
    val resultsAtBottom: Boolean = true,
    /** A short answer from Gemini Nano on the device above the results. */
    val aiAnswers: Boolean = true,
    /** Apps from the Play Store when few installed apps match; sends the search words to Google Play. */
    val storeResults: Boolean = true,
    /** Messages from notifications are kept on the device for the search and Gemini Nano. */
    val saveMessages: Boolean = true,
    /** Last searches that led somewhere, newest first; only on this device. */
    val recentSearches: List<String> = emptyList(),
    /** Home screen grid; null means the default for the device (5×6 on phones). */
    val gridColumns: Int? = null,
    val gridRows: Int? = null,
    /** Apps kept out of the App Library, the search and suggestions (still in the settings list). */
    val hiddenApps: Set<String> = emptySet(),
    /** Names the user gave apps, by app key. */
    val appLabels: Map<String, String> = emptyMap(),
    /** The home screen cannot be rearranged: no edit mode, no dragging, no removing. */
    val layoutLocked: Boolean = false,
    /** The dot on an app icon shows how many notifications it has. */
    val badgeNumbers: Boolean = false,
    /** Icons that have a monochrome version are drawn in the wallpaper colors, as on Pixel phones. */
    val themedIcons: Boolean = false,
    /** A double tap on an empty part of the home screen turns the screen off. */
    val doubleTapToLock: Boolean = false,
    /** The first-start introduction was shown. */
    val onboarded: Boolean = false,
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
                dockSuggestion = prefs[DockSuggestionKey] ?: true,
                resultsAtBottom = prefs[ResultsAtBottomKey] ?: true,
                aiAnswers = prefs[AiAnswersKey] ?: true,
                storeResults = prefs[StoreResultsKey] ?: true,
                saveMessages = prefs[SaveMessagesKey] ?: true,
                recentSearches = prefs[RecentSearchesKey]?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
                gridColumns = prefs[GridColumnsKey],
                gridRows = prefs[GridRowsKey],
                hiddenApps = prefs[HiddenAppsKey].orEmpty(),
                // "key<TAB>name"; neither contains a tab.
                appLabels = prefs[AppLabelsKey].orEmpty().mapNotNull { entry ->
                    entry.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap(),
                layoutLocked = prefs[LayoutLockedKey] ?: false,
                badgeNumbers = prefs[BadgeNumbersKey] ?: false,
                themedIcons = prefs[ThemedIconsKey] ?: false,
                doubleTapToLock = prefs[DoubleTapToLockKey] ?: false,
                onboarded = prefs[OnboardedKey] ?: false,
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

    suspend fun setGrid(columns: Int, rows: Int) {
        dataStore.edit {
            it[GridColumnsKey] = columns
            it[GridRowsKey] = rows
        }
    }

    suspend fun setSaveMessages(enabled: Boolean) {
        dataStore.edit { it[SaveMessagesKey] = enabled }
    }

    suspend fun setStoreResults(enabled: Boolean) {
        dataStore.edit { it[StoreResultsKey] = enabled }
    }

    suspend fun setAiAnswers(enabled: Boolean) {
        dataStore.edit { it[AiAnswersKey] = enabled }
    }

    suspend fun setResultsAtBottom(enabled: Boolean) {
        dataStore.edit { it[ResultsAtBottomKey] = enabled }
    }

    /** Remembers [query] as the newest recent search. */
    suspend fun addRecentSearch(query: String) {
        val clean = query.trim().replace('\n', ' ')
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val old = prefs[RecentSearchesKey]?.split('\n').orEmpty()
            val list = (listOf(clean) + old.filter { !it.equals(clean, ignoreCase = true) && it.isNotBlank() }).take(MAX_RECENT)
            prefs[RecentSearchesKey] = list.joinToString("\n")
        }
    }

    suspend fun clearRecentSearches() {
        dataStore.edit { it.remove(RecentSearchesKey) }
    }

    suspend fun setDockSuggestion(enabled: Boolean) {
        dataStore.edit { it[DockSuggestionKey] = enabled }
    }

    suspend fun dismissUsageHint() {
        dataStore.edit { it[UsageHintDismissedKey] = true }
    }

    suspend fun setAutoUpdate(enabled: Boolean) {
        dataStore.edit { it[AutoUpdateKey] = enabled }
    }

    suspend fun setAppHidden(appKey: String, hidden: Boolean) {
        dataStore.edit {
            val current = it[HiddenAppsKey].orEmpty()
            it[HiddenAppsKey] = if (hidden) current + appKey else current - appKey
        }
    }

    /** A blank [label] gives the app its own name back. */
    suspend fun setAppLabel(appKey: String, label: String) {
        val clean = label.replace('\t', ' ').replace('\n', ' ').trim()
        dataStore.edit {
            val others = it[AppLabelsKey].orEmpty().filterNot { entry -> entry.startsWith("$appKey\t") }.toSet()
            it[AppLabelsKey] = if (clean.isEmpty()) others else others + "$appKey\t$clean"
        }
    }

    suspend fun setLayoutLocked(locked: Boolean) {
        dataStore.edit { it[LayoutLockedKey] = locked }
    }

    suspend fun setBadgeNumbers(enabled: Boolean) {
        dataStore.edit { it[BadgeNumbersKey] = enabled }
    }

    suspend fun setThemedIcons(enabled: Boolean) {
        dataStore.edit { it[ThemedIconsKey] = enabled }
    }

    suspend fun setDoubleTapToLock(enabled: Boolean) {
        dataStore.edit { it[DoubleTapToLockKey] = enabled }
    }

    suspend fun setOnboarded() {
        dataStore.edit { it[OnboardedKey] = true }
    }

    /** The settings worth keeping in a backup, as plain values. */
    suspend fun export(): Map<String, Any> {
        val p = prefs.first()
        return buildMap {
            p.gridColumns?.let { put("gridColumns", it) }
            p.gridRows?.let { put("gridRows", it) }
            put("addNewAppsToHome", p.addNewAppsToHome)
            put("dockSuggestion", p.dockSuggestion)
            put("resultsAtBottom", p.resultsAtBottom)
            put("aiAnswers", p.aiAnswers)
            put("storeResults", p.storeResults)
            put("saveMessages", p.saveMessages)
            put("autoUpdate", p.autoUpdate)
            put("layoutLocked", p.layoutLocked)
            put("badgeNumbers", p.badgeNumbers)
            put("themedIcons", p.themedIcons)
            put("doubleTapToLock", p.doubleTapToLock)
            put("hiddenApps", p.hiddenApps.toList())
            put("disabledSources", p.disabledSources.toList())
            put("appLabels", p.appLabels)
        }
    }

    /** Restores what [export] wrote; unknown or missing values are left as they are. */
    suspend fun import(values: Map<String, Any?>) {
        dataStore.edit { prefs ->
            (values["gridColumns"] as? Int)?.let { prefs[GridColumnsKey] = it }
            (values["gridRows"] as? Int)?.let { prefs[GridRowsKey] = it }
            (values["addNewAppsToHome"] as? Boolean)?.let { prefs[AddNewAppsKey] = it }
            (values["dockSuggestion"] as? Boolean)?.let { prefs[DockSuggestionKey] = it }
            (values["resultsAtBottom"] as? Boolean)?.let { prefs[ResultsAtBottomKey] = it }
            (values["aiAnswers"] as? Boolean)?.let { prefs[AiAnswersKey] = it }
            (values["storeResults"] as? Boolean)?.let { prefs[StoreResultsKey] = it }
            (values["saveMessages"] as? Boolean)?.let { prefs[SaveMessagesKey] = it }
            (values["autoUpdate"] as? Boolean)?.let { prefs[AutoUpdateKey] = it }
            (values["layoutLocked"] as? Boolean)?.let { prefs[LayoutLockedKey] = it }
            (values["badgeNumbers"] as? Boolean)?.let { prefs[BadgeNumbersKey] = it }
            (values["themedIcons"] as? Boolean)?.let { prefs[ThemedIconsKey] = it }
            (values["doubleTapToLock"] as? Boolean)?.let { prefs[DoubleTapToLockKey] = it }
            (values["hiddenApps"] as? List<*>)?.let { list -> prefs[HiddenAppsKey] = list.filterIsInstance<String>().toSet() }
            (values["disabledSources"] as? List<*>)?.let { list -> prefs[DisabledSourcesKey] = list.filterIsInstance<String>().toSet() }
            (values["appLabels"] as? Map<*, *>)?.let { map ->
                prefs[AppLabelsKey] = map.mapNotNull { (k, v) -> if (k is String && v is String) "$k\t$v" else null }.toSet()
            }
        }
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
        val DockSuggestionKey = booleanPreferencesKey("dock_suggestion")
        val ResultsAtBottomKey = booleanPreferencesKey("results_at_bottom")
        val AiAnswersKey = booleanPreferencesKey("ai_answers")
        val StoreResultsKey = booleanPreferencesKey("store_results")
        val SaveMessagesKey = booleanPreferencesKey("save_messages")
        val RecentSearchesKey = stringPreferencesKey("recent_searches")
        val GridColumnsKey = intPreferencesKey("grid_columns")
        val GridRowsKey = intPreferencesKey("grid_rows")
        val HiddenAppsKey = stringSetPreferencesKey("hidden_apps")
        val AppLabelsKey = stringSetPreferencesKey("app_labels")
        val LayoutLockedKey = booleanPreferencesKey("layout_locked")
        val BadgeNumbersKey = booleanPreferencesKey("badge_numbers")
        val ThemedIconsKey = booleanPreferencesKey("themed_icons")
        val DoubleTapToLockKey = booleanPreferencesKey("double_tap_to_lock")
        val OnboardedKey = booleanPreferencesKey("onboarded")
        const val MAX_RECENT = 6
        val LastUpdateCheckKey = longPreferencesKey("last_update_check")
    }
}
