package io.github.davydgky.launcher.home

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.apps.AppsRepository
import io.github.davydgky.launcher.apps.DefaultApps
import io.github.davydgky.launcher.core.Cell
import io.github.davydgky.launcher.data.LauncherPrefs
import io.github.davydgky.launcher.data.LauncherSettings
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.usage.UsageHistory
import io.github.davydgky.launcher.widgets.WidgetHost
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val file = AtomicFile(File(context.filesDir, "workspace.json"))
    /** The arrangement as it was at the last start that read it fine. */
    private val previous = File(context.filesDir, "workspace.prev.json")
    private val state = MutableStateFlow<Workspace?>(null)
    /** Null until loaded. */
    val workspace: StateFlow<Workspace?> = state.asStateFlow()
    private var saveJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            state.value = withContext(Dispatchers.IO) { load() }
            pinned = state.value?.pinnedShortcuts().orEmpty()
            // Only the settings that shape the home screen: a new recent search is no reason to sync.
            val homePrefs = settings.prefs.distinctUntilChanged { a, b ->
                a.gridColumns == b.gridColumns && a.gridRows == b.gridRows && a.addNewAppsToHome == b.addNewAppsToHome
            }
            combine(apps.apps, homePrefs) { list, prefs -> list to prefs }.collect { (list, prefs) ->
                val unavailable = apps.unavailableProfiles.value
                if (list.isEmpty()) return@collect
                val keys = list.map { it.key }
                val packages = list.mapTo(HashSet()) { it.packageName }
                val current = state.value
                val (columns, rows) = gridOf(prefs)
                if (current == null) {
                    state.value = createInitial(keys, prefs.dock, columns, rows)
                    scheduleSave()
                } else {
                    // Private space apps stay off the home screen unless the user puts one there.
                    val privateApps = list.filterTo(HashSet()) { it.isPrivate }.mapTo(HashSet()) { it.key }
                    update {
                        it.withGrid(columns, rows)
                            .sync(keys, prefs.addNewAppsToHome, packages, unavailable) { key -> key in privateApps }
                            .withoutMissingWidgets()
                    }
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

    /**
     * The saved arrangement. A file that cannot be read is kept aside, never overwritten, and the one
     * from the last good start is used instead; only without both is a new arrangement made.
     */
    private fun load(): Workspace? {
        val main = file.baseFile
        if (!main.exists() && !previous.exists()) return null
        val text = runCatching { file.readFully().decodeToString() }.getOrNull()
        val ws = text?.let { runCatching { WorkspaceCodec.decode(it) }.onFailure { e -> Log.e(TAG, "Arrangement unreadable", e) }.getOrNull() }
        if (ws != null) {
            runCatching { previous.writeText(text) }
            return ws
        }
        if (main.exists()) main.renameTo(File(context.filesDir, "workspace.broken-${System.currentTimeMillis()}.json"))
        return runCatching { WorkspaceCodec.decode(previous.readText()) }
            .onFailure { Log.e(TAG, "No earlier arrangement either", it) }
            .getOrNull()
    }

    /** The grid the user chose, or 5×6 on phones and 6×5 on tablets. */
    private fun gridOf(prefs: LauncherPrefs): Pair<Int, Int> {
        val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
        return (prefs.gridColumns ?: if (tablet) 6 else 5) to (prefs.gridRows ?: if (tablet) 5 else 6)
    }

    private suspend fun createInitial(keys: List<String>, legacyDock: List<String>?, columns: Int, rows: Int): Workspace {
        val all = apps.apps.value.filter { !it.isPrivate && !it.paused }
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

    /** The last removal, for a few seconds: it can be taken back. */
    class Undo internal constructor(val message: Int, internal val before: Workspace, internal val widgetIds: List<Int>)

    private val undoState = MutableStateFlow<Undo?>(null)
    val undo: StateFlow<Undo?> = undoState.asStateFlow()
    private var undoJob: Job? = null

    /**
     * Applies a removal that can be taken back. Removed widgets keep their ids (and with them their
     * content and settings) until it can no longer be undone.
     */
    private fun removing(message: Int, widgetIds: List<Int>, change: (Workspace) -> Workspace) {
        val before = state.value ?: return
        update(change)
        if (state.value == before) return
        expireUndo()
        undoState.value = Undo(message, before, widgetIds)
        undoJob = scope.launch {
            delay(UNDO_MS)
            expireUndo()
        }
    }

    private fun expireUndo() {
        undoJob?.cancel()
        undoState.value?.widgetIds?.forEach(widgets::deleteId)
        undoState.value = null
    }

    fun undo() {
        val undo = undoState.value ?: return
        undoJob?.cancel()
        undoState.value = null
        update { undo.before }
    }

    fun move(id: Long, target: Cell) = update { it.move(id, target) }
    fun removeItem(id: Long) {
        val item = state.value?.item(id) ?: return
        val widgetIds = listOfNotNull((item as? WidgetItem)?.widgetId)
        val message = when (item) {
            is WidgetItem -> R.string.undo_widget_removed
            is FolderItem -> R.string.undo_folder_removed
            else -> R.string.undo_removed
        }
        removing(message, widgetIds) { it.remove(id) }
    }
    fun removeApp(appKey: String) = removing(R.string.undo_removed, emptyList()) { it.removeApp(appKey) }
    fun removePage(page: Int) {
        val widgetIds = state.value?.itemsOn(page)?.filterIsInstance<WidgetItem>()?.map { it.widgetId }.orEmpty()
        removing(R.string.undo_page_removed, widgetIds) { it.removePage(page) }
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
    fun dissolveFolder(folderId: Long) = removing(R.string.undo_folder_dissolved, emptyList()) { it.dissolveFolder(folderId) }
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
            withContext(Dispatchers.IO) { write(text) }
        }
    }

    /** Written to a new file, synced and only then swapped in: a crash or full disk leaves the old one. */
    private fun write(text: String) {
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.e(TAG, "Cannot save the arrangement", e)
            return
        }
        try {
            out.write(text.toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            Log.e(TAG, "Cannot save the arrangement", e)
            file.failWrite(out)
        }
    }

    private companion object {
        const val TAG = "WorkspaceRepository"
        const val PRIOR_WAIT_MS = 1_500L
        const val UNDO_MS = 6_000L
    }
}
