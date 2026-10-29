package io.github.davydgky.launcher.search

import android.os.SystemClock
import android.util.Log
import io.github.davydgky.launcher.LauncherApplication
import io.github.davydgky.launcher.core.Engagement
import io.github.davydgky.launcher.core.SearchEngine
import io.github.davydgky.launcher.core.SearchIndex
import io.github.davydgky.launcher.core.SearchResults
import io.github.davydgky.launcher.data.LauncherPrefs
import io.github.davydgky.launcher.sources.AppsSource
import io.github.davydgky.launcher.sources.CalendarSource
import io.github.davydgky.launcher.sources.ContactsSource
import io.github.davydgky.launcher.sources.DocumentsSource
import io.github.davydgky.launcher.sources.ImagesSource
import io.github.davydgky.launcher.sources.MessagesSource
import io.github.davydgky.launcher.sources.NotesSource
import io.github.davydgky.launcher.sources.Requirement
import io.github.davydgky.launcher.sources.SearchSource
import io.github.davydgky.launcher.sources.SettingsSource
import io.github.davydgky.launcher.sources.ShortcutsSource
import io.github.davydgky.launcher.sources.SmsSource
import io.github.davydgky.launcher.sources.Velo
import io.github.davydgky.launcher.sources.VeloSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of one source as shown in the settings. */
data class SourceStatus(
    val enabled: Boolean,
    val missing: Requirement?,
    val itemCount: Int,
    val loading: Boolean,
)

/**
 * Owns the search engine and keeps one index per source current: loads a source when it becomes
 * usable (switched on, permission granted), reloads it when it reports a change, and drops it when it
 * is switched off or loses its permission. All state changes happen on the main thread.
 */
class SearchRepository(private val app: LauncherApplication, private val scope: CoroutineScope) {

    val engine = SearchEngine()

    private var prefs = LauncherPrefs()

    val sources: List<SearchSource> = listOf(
        AppsSource(app, app.apps, app.appKnowledge, scope),
        SettingsSource(app),
        ShortcutsSource(app.apps, scope),
        ContactsSource(app),
        MessagesSource(app, app.messages, app.notifications, scope),
        SmsSource(app, app.messages),
        CalendarSource(app),
        VeloSource(app),
        NotesSource(app, app.indexStore) { prefs.noteFolders },
        DocumentsSource(app, app.indexStore),
        ImagesSource(app, app.indexStore),
    )

    private val statusState = MutableStateFlow<Map<String, SourceStatus>>(emptyMap())
    val status: StateFlow<Map<String, SourceStatus>> = statusState.asStateFlow()

    private val engagementState = MutableStateFlow<Map<String, Float>>(emptyMap())

    private val observers = HashMap<String, AutoCloseable?>()
    private val loadJobs = HashMap<String, Job>()
    private val loadedAt = HashMap<String, Long>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        app.apps.start()
        app.apps.checkShortcutAccess()
        app.torch // starts following the flashlight's state for the search result
        scope.launch {
            app.settings.prefs.collect { newPrefs ->
                val foldersChanged = newPrefs.noteFolders != prefs.noteFolders
                prefs = newPrefs
                applyAvailability()
                if (foldersChanged) reload(NotesSource.ID)
            }
        }
        // Velo was installed or removed: its source may have become (un)available.
        scope.launch { app.apps.packageChanges.collect { if (it == Velo.PACKAGE) applyAvailability() } }
        scope.launch {
            combine(app.launchLog.events, app.usage.systemEvents, app.usage.appPrior) { _, _, prior -> app.usage.combinedEvents() to prior }
                .collect { (events, prior) ->
                    engagementState.value = withContext(Dispatchers.Default) {
                        // Recent launches count most; screen time of the last month ranks apps never opened from here.
                        val scores = Engagement.scores(events, System.currentTimeMillis()).toMutableMap()
                        prior.forEach { (key, value) -> scores.merge(key, value * PRIOR_WEIGHT, ::maxOf) }
                        scores
                    }
                }
        }
    }

    /** The home screen became visible: permissions or the launcher role may have changed meanwhile. */
    fun onResume() {
        app.apps.checkShortcutAccess()
        app.usage.refresh()
        applyAvailability()
        val now = SystemClock.elapsedRealtime()
        for (source in sources) {
            val interval = source.refreshIntervalMs ?: continue
            val last = loadedAt[source.id] ?: continue
            if (now - last > interval) reload(source.id)
        }
    }

    fun search(query: String): SearchResults {
        val engagement = engagementState.value
        return engine.search(query) { engagement[it] ?: 0f }
    }

    /** Reloads one source after a short pause, so bursts of change notifications cause one reload. */
    fun reload(sourceId: String, delayMs: Long = 0) {
        scope.launch {
            val source = sources.firstOrNull { it.id == sourceId } ?: return@launch
            if (isActive(source)) load(source, delayMs)
        }
    }

    fun rebuildAll() {
        scope.launch {
            withContext(Dispatchers.IO) { app.indexStore.clear() }
            sources.filter(::isActive).forEach { load(it, 0) }
        }
    }

    private fun isActive(source: SearchSource) = source.id !in prefs.disabledSources && source.missing(app) == null

    private fun applyAvailability() {
        for (source in sources) {
            val missing = source.missing(app)
            val active = source.id !in prefs.disabledSources && missing == null
            updateStatus(source.id) {
                SourceStatus(
                    enabled = source.id !in prefs.disabledSources,
                    missing = missing,
                    itemCount = if (active) engine.index(source.id)?.size ?: 0 else 0,
                    loading = it?.loading == true && active,
                )
            }
            val observed = source.id in observers
            if (active && !observed) {
                observers[source.id] = source.observe { reload(source.id, CHANGE_DEBOUNCE_MS) }
                load(source, 0)
            } else if (!active && observed) {
                observers.remove(source.id)?.close()
                loadJobs.remove(source.id)?.cancel()
                loadedAt.remove(source.id)
                engine.remove(source.id)
            }
        }
    }

    private fun load(source: SearchSource, delayMs: Long) {
        loadJobs[source.id]?.cancel()
        loadJobs[source.id] = scope.launch {
            delay(delayMs)
            updateStatus(source.id) { it?.copy(loading = true) }
            val started = SystemClock.elapsedRealtime()
            val index = runCatching {
                withContext(Dispatchers.IO) { SearchIndex.build(source.load()) }
            }.onFailure {
                // A newer load replaced this one: it reports the status, not this one.
                if (it is CancellationException) throw it
                Log.w(TAG, "Loading ${source.id} failed", it)
            }.getOrNull()
            if (index != null && isActive(source)) {
                engine.publish(source.id, index)
                Log.i(TAG, "${source.id}: ${index.size} items in ${SystemClock.elapsedRealtime() - started} ms")
            }
            loadedAt[source.id] = SystemClock.elapsedRealtime()
            updateStatus(source.id) { it?.copy(loading = false, itemCount = engine.index(source.id)?.size ?: 0) }
        }
    }

    private fun updateStatus(id: String, change: (SourceStatus?) -> SourceStatus?) {
        statusState.update { current ->
            val next = change(current[id]) ?: return@update current
            current + (id to next)
        }
    }

    private companion object {
        const val TAG = "SearchRepository"
        const val CHANGE_DEBOUNCE_MS = 1_500L
        const val PRIOR_WEIGHT = 0.8f
    }
}
