package io.github.davydgky.launcher.home

import android.content.Context
import android.util.Log
import io.github.davydgky.launcher.apps.AppsRepository
import io.github.davydgky.launcher.apps.DefaultApps
import io.github.davydgky.launcher.core.Cell
import io.github.davydgky.launcher.data.LauncherSettings
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.usage.UsageHistory
import io.github.davydgky.launcher.widgets.WidgetHost
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the home screen arrangement: loads and saves it, follows installs and uninstalls, and applies
 * the user's edits. All changes happen on the main thread.
 */
class WorkspaceRepository(
    private val context: Context,
    private val apps: AppsRepository,
    private val settings: LauncherSettings,
    private val usage: UsageHistory,
    private val widgets: WidgetHost,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "workspace.json")
    private val state = MutableStateFlow<Workspace?>(null)
    /** Null until loaded. */
    val workspace: StateFlow<Workspace?> = state.asStateFlow()
    private var saveJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            state.value = withContext(Dispatchers.IO) { runCatching { WorkspaceCodec.decode(file.readText()) }.getOrNull() }
            combine(apps.apps, settings.prefs) { list, prefs -> list to prefs }.collect { (list, prefs) ->
                if (list.isEmpty()) return@collect
                val keys = list.map { it.key }
                val current = state.value
                if (current == null) {
                    state.value = createInitial(keys, prefs.dock)
                    scheduleSave()
                } else {
                    update { it.sync(keys, prefs.addNewAppsToHome).withoutMissingWidgets() }
                }
            }
        }
    }

    private suspend fun createInitial(keys: List<String>, legacyDock: List<String>?): Workspace {
        val config = context.resources.configuration
        val columns = if (config.smallestScreenWidthDp >= 600) 6 else 4
        val rows = if (config.smallestScreenWidthDp >= 600) 5 else 6
        val all = apps.apps.value
        // Four default apps: the fifth place is left to the suggested app.
        val dock = legacyDock?.filter { it in keys } ?: DefaultApps.resolve(context, all).take(Workspace.DOCK_SIZE - 1).map { it.key }
        // Most used apps first, so the first page is the useful one; the rest alphabetically.
        if (usage.checkAccess()) {
            usage.refresh()
            withTimeoutOrNull(PRIOR_WAIT_MS) { usage.appPrior.first { it.isNotEmpty() } }
        }
        val prior = usage.appPrior.value
        val ordered = all.sortedWith(compareByDescending<AppTarget> { prior[it.key] ?: 0f })
            .map { it.key }
        return withContext(Dispatchers.Default) { Workspace.initial(columns, rows, ordered, dock) }
    }

    /** Widgets whose app was uninstalled cannot show anything any more. */
    private fun Workspace.withoutMissingWidgets(): Workspace {
        val missing = items.filterIsInstance<WidgetItem>().filter { widgets.info(it.widgetId) == null }
        return missing.fold(this) { ws, item -> ws.remove(item.id) }
    }

    fun update(change: (Workspace) -> Workspace) {
        val current = state.value ?: return
        val next = runCatching { change(current) }.onFailure { Log.w(TAG, "Edit failed", it) }.getOrNull() ?: return
        if (next == current) return
        state.value = next
        scheduleSave()
    }

    fun move(id: Long, target: Cell) = update { it.move(id, target) }
    fun removeItem(id: Long) {
        val item = state.value?.item(id)
        if (item is WidgetItem) widgets.deleteId(item.widgetId)
        update { it.remove(id) }
    }
    fun removeApp(appKey: String) = update { it.removeApp(appKey) }
    fun addApp(appKey: String) = update { it.addApp(appKey) }
    fun makeFolder(draggedId: Long, targetId: Long, name: String) = update { it.makeFolder(draggedId, targetId, name) }
    fun makeFolderFromDock(appKey: String, targetId: Long, name: String) = update { it.makeFolderWith(appKey, targetId, name) }
    fun addToFolder(appKey: String, folderId: Long) = update { it.addToFolder(appKey, folderId) }
    fun removeFromFolder(folderId: Long, appKey: String) = update { it.removeFromFolder(folderId, appKey) }
    fun dissolveFolder(folderId: Long) = update { it.dissolveFolder(folderId) }
    fun renameFolder(folderId: Long, name: String) = update { it.renameFolder(folderId, name) }
    fun toDock(appKey: String, index: Int) = update { it.toDock(appKey, index) }
    fun fromDock(appKey: String, target: Cell?) = update { it.fromDock(appKey, target) }
    fun resizeWidget(id: Long, w: Int, h: Int) = update { it.resize(id, w, h) }
    fun addWidget(widgetId: Int, provider: String, w: Int, h: Int, page: Int) = update { it.addWidget(widgetId, provider, w, h, page) }

    /** Removes widget ids the host still holds but no element uses (e.g. after a cancelled add). */
    suspend fun cleanUpWidgets() {
        val ws = workspace.first { it != null } ?: return
        widgets.deleteUnused(ws.items.filterIsInstance<WidgetItem>().map { it.widgetId }.toSet())
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            val text = WorkspaceCodec.encode(state.value ?: return@launch)
            withContext(Dispatchers.IO) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        const val TAG = "WorkspaceRepository"
        const val PRIOR_WAIT_MS = 1_500L
    }
}
