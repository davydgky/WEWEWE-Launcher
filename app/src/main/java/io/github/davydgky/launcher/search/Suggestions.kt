package io.github.davydgky.launcher.search

import android.content.Context
import io.github.davydgky.launcher.apps.AppsRepository
import io.github.davydgky.launcher.apps.DefaultApps
import io.github.davydgky.launcher.core.Predictor
import io.github.davydgky.launcher.data.LaunchLog
import io.github.davydgky.launcher.usage.UsageHistory
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SuggestionState(val apps: List<AppTarget>, val shortcuts: List<ShortcutTarget>) {
    companion object {
        val Empty = SuggestionState(emptyList(), emptyList())
    }
}

/**
 * What the search shows before anything is typed: apps for this time of day and how often they are
 * used, and shortcuts the user opens at this time or the top shortcuts of the predicted apps.
 */
class Suggestions(
    private val apps: AppsRepository,
    private val usage: UsageHistory,
    private val launchLog: LaunchLog,
    private val scope: CoroutineScope,
    private val predictor: Predictor = Predictor(),
) {
    private val state = MutableStateFlow(SuggestionState.Empty)
    val current: StateFlow<SuggestionState> = state.asStateFlow()

    /** Recomputes the suggestions; called when the search starts to open. Takes a few milliseconds. */
    fun refresh(context: Context) {
        scope.launch {
            state.value = withContext(Dispatchers.Default) { compute(context) }
        }
    }

    private fun compute(context: Context): SuggestionState {
        // Paused apps cannot start; private space apps are not shown outside the private space.
        val allApps = apps.apps.value.filter { !it.paused && !it.isPrivate }
        val byKey = allApps.associateBy { it.key }
        val ranking = predictor.rank(usage.combinedEvents(), System.currentTimeMillis(), ZoneId.systemDefault())

        val predictedApps = ranking.mapNotNull { byKey[it.key] }.take(APP_COUNT)
        // Without enough history, the default apps for common tasks fill the remaining places.
        val suggestedApps = (predictedApps + DefaultApps.resolve(context, allApps))
            .distinctBy { it.key }
            .take(APP_COUNT)

        val shortcutsByKey = apps.shortcuts.value.associateBy { it.key }
        val usedShortcuts = ranking.mapNotNull { shortcutsByKey[it.key] }.take(SHORTCUT_COUNT)
        val fromApps = suggestedApps.take(3).flatMap { app -> apps.shortcutsOf(app).take(2) }
        val shortcuts = (usedShortcuts + fromApps).distinctBy { it.key }.take(SHORTCUT_COUNT)
        return SuggestionState(suggestedApps, shortcuts)
    }

    private companion object {
        const val APP_COUNT = 8
        const val SHORTCUT_COUNT = 4
    }
}
