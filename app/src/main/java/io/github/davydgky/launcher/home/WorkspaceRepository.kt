package io.github.davydgky.launcher.home

import android.content.Context
import android.util.Log
import io.github.davydgky.launcher.apps.AppsRepository
import io.github.davydgky.launcher.apps.DefaultApps
import io.github.davydgky.launcher.core.Cell
import io.github.davydgky.launcher.data.LauncherPrefs
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
            pinned = state.value?.pinnedShortcuts().orEmpty()
            combine(apps.apps, settings.prefs) { list, prefs -> list to prefs }.collect { (list, prefs) ->
                if (list.isEmpty()) return@collect
                val keys = list.map { it.key }
                val packages = list.mapTo(HashSet()) { it.packageName }
                val current = state.value
                val (columns, rows) = gridOf(prefs)
                if (current == null) {
                    state.value = createInitial(keys, prefs.dock, columns, rows)
                    scheduleSave()
                } else {
                    update { it.withGrid(columns, rows).sync(keys, prefs.addNewAppsToHome, packages).withoutMissingWidgets() }
                }
                // Edits that came in before the arrangement was loaded (a shortcut added from another app).
                if (pending.isNotEmpty()) {
                    val edits = pending.toList()
                    pending.clear()
                    edits.forEach(::update)
                }
            }
        }
    }

    /** The grid the user chose, or 5×6 on phones and 6×5 on tablets. */
    private fun gridOf(prefs: LauncherPrefs): Pair<Int, Int> {
        val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
        return (prefs.gridColumns ?: if (tablet) 6 else 5) to (prefs.gridRows ?: if (tablet) 5 else 6)
    }

    private suspend fun createInitial(keys: List<String>, legacyDock: List<String>?, columns: Int, rows: Int): Workspace {
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
        updatePins(next)
        scheduleSave()
    }

    /** Pinned shortcuts as last told to the system, per user serial and package. */
    private var pinned: Map<Pair<Long, String>, Set<String>> = emptyMap()

    /**
     * The system keeps a shortcut only while a launcher has it pinned; it is told the complete list per
     * app whenever shortcuts are added to or taken off the home screen.
     */
    private fun updatePins(ws: Workspace) {
        val now = ws.pinnedShortcuts()
        if (now == pinned) return
        for (key in now.keys + pinned.keys) {
            val ids = now[key].orEmpty()
            if (ids != pinned[key]) apps.pinShortcuts(key.first, key.second, ids.toList())
        }
        pinned = now
    }

    fun move(id: Long, target: Cell) = update { it.move(id, target) }
    fun removeItem(id: Long) {
        val item = state.value?.item(id)
        if (item is WidgetItem) widgets.deleteId(item.widgetId)
        update { it.remove(id) }
    }
    fun removeApp(appKey: String) = update { it.removeApp(appKey) }
    fun removePage(page: Int) {
        state.value?.itemsOn(page)?.filterIsInstance<WidgetItem>()?.forEach { widgets.deleteId(it.widgetId) }
        update { it.removePage(page) }
    }
    fun addApp(appKey: String) = update { it.addApp(appKey) }
    fun addShortcut(shortcutKey: String, label: String, page: Int) {
        val change = { ws: Workspace -> ws.addShortcut(shortcutKey, label, page) }
        if (state.value == null) pending += change else update(change)
    }

    private val pending = ArrayList<(Workspace) -> Workspace>()
    fun makeFolder(draggedId: Long, targetId: Long, name: String) = update { it.makeFolder(draggedId, targetId, name) }
    fun makeFolderFromDock(appKey: String, targetId: Long, name: String) = update { it.makeFolderWith(appKey, targetId, name) }
    fun addToFolder(appKey: String, folderId: Long) = update { it.addToFolder(appKey, folderId) }
    fun removeFromFolder(folderId: Long, appKey: String) = update { it.removeFromFolder(folderId, appKey) }
    fun dissolveFolder(folderId: Long) = update { it.dissolveFolder(folderId) }
    fun renameFolder(folderId: Long, name: String) = update { it.renameFolder(folderId, name) }
    fun setFolderStyle(folderId: Long, style: FolderStyle) = update { it.setFolderStyle(folderId, style) }
    fun setFolderPlayful(folderId: Long, playful: Boolean) = update { it.setFolderPlayful(folderId, playful) }
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
