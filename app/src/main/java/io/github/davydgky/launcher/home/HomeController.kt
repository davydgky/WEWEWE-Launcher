package io.github.davydgky.launcher.home

import android.appwidget.AppWidgetProviderInfo
import android.util.Log
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import io.github.davydgky.launcher.core.Cell
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.search.ResultActions
import io.github.davydgky.launcher.search.ShortcutTarget
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What is being dragged. */
sealed interface DragSource {
    data class Item(val item: HomeItem) : DragSource
    data class Dock(val appKey: String) : DragSource

    /** The app being dragged, if it is one (folders and widgets are not). */
    val draggedApp: String?
        get() = when (this) {
            is Dock -> appKey
            is Item -> (item as? AppItem)?.appKey
        }
}

/** Where a drag would end if the finger lifted now. */
sealed interface DropTarget {
    data class Move(val cell: Cell) : DropTarget
    data class MakeFolder(val targetId: Long) : DropTarget
    data class IntoFolder(val folderId: Long) : DropTarget
    data class Dock(val index: Int) : DropTarget
}

@Stable
class DragState(val source: DragSource, start: Offset, val grab: Offset, val size: IntSize) {
    var pointer by mutableStateOf(start)
    var target by mutableStateOf<DropTarget?>(null)
}

/** Everything the home screen needs from outside, plus its interaction state. */
@Stable
class HomeController(
    val actions: ResultActions,
    val repository: WorkspaceRepository,
    val shortcutsOf: (AppTarget) -> List<ShortcutTarget>,
    val openSearch: () -> Unit,
    val addWidget: (AppWidgetProviderInfo, Int, Int, Int) -> Unit,
    private val scope: CoroutineScope,
) {
    lateinit var pager: PagerState

    var drag by mutableStateOf<DragState?>(null)
        private set
    var openFolderId by mutableStateOf<Long?>(null)

    /**
     * Edit mode as on iOS: elements wiggle, show a remove badge and move without a long press. Entered by
     * a long press on the background, from an element's menu or by dragging an element.
     */
    var editMode by mutableStateOf(false)
        private set

    fun startEditing() {
        editMode = true
        dismissMenus()
    }

    fun stopEditing() {
        editMode = false
    }
    var openCategory by mutableStateOf<LibraryGroup?>(null)
    var widgetPickerOpen by mutableStateOf(false)

    /** Latest arrangement; set by the home screen on every composition. */
    var workspace: Workspace? = null

    /** Name for a new folder from the apps it starts with. */
    var folderName: (List<String>) -> String = { "" }

    /** Grid area of each page and of the dock, in root coordinates. */
    val pageBounds = HashMap<Int, Rect>()
    var dockBounds = Rect.Zero
    val gridBounds: Rect get() = pageBounds[pager.currentPage] ?: pageBounds.values.firstOrNull() ?: Rect.Zero
    var density = 1f
    var screenWidth = 0f
    var columns = 4
    var rows = 6
    var homePages = 1
    var dockSize = 0

    /** The App Library is showing (the page after the last home page). */
    val onLibrary: Boolean get() = ::pager.isInitialized && pager.currentPage >= homePages

    val cellWidth: Float get() = gridBounds.width / columns
    val cellHeight: Float get() = gridBounds.height / rows
    val cellWidthDp: Float get() = cellWidth / density
    val cellHeightDp: Float get() = cellHeight / density

    /** An element is held after a long press (its menu shows or a drag is about to start). */
    var holding by mutableStateOf(false)
        private set

    /** Bumped by the home button; every open menu closes. */
    var menuEpoch by mutableIntStateOf(0)
        private set

    fun dismissMenus() {
        menuEpoch++
    }

    /** Fingers currently down on home screen elements; the background menu waits for none. */
    var itemsPressed = 0
        private set

    fun itemDown() {
        itemsPressed++
    }

    fun itemHeld() {
        holding = true
    }

    fun itemReleased() {
        itemsPressed = (itemsPressed - 1).coerceAtLeast(0)
        holding = false
    }

    /** No other gesture (search swipe, pager) while something is held, dragged or edited. */
    val isEditing: Boolean
        get() = editMode || holding || drag != null || openFolderId != null || openCategory != null || widgetPickerOpen

    private var edgeJob: Job? = null

    fun startDrag(source: DragSource, pointer: Offset, grab: Offset, size: IntSize) {
        Log.i(TAG, "Drag $source")
        drag = DragState(source, pointer, grab, size)
        editMode = true
    }

    fun moveDrag(pointer: Offset) {
        val state = drag ?: return
        val workspace = workspace ?: return
        state.pointer = pointer
        state.target = targetFor(workspace, state)
        flipPageAtEdges(pointer)
    }

    fun endDrag() {
        val state = drag ?: return
        drag = null
        edgeJob?.cancel()
        val workspace = workspace ?: return
        val source = state.source
        val target = targetFor(workspace, state)
        Log.i(TAG, "Drop at ${state.pointer} on $target")
        when (target) {
            is DropTarget.Move -> when (source) {
                is DragSource.Item -> repository.move(source.item.id, target.cell)
                is DragSource.Dock -> repository.fromDock(source.appKey, target.cell)
            }
            is DropTarget.MakeFolder -> {
                val targetApp = (workspace.item(target.targetId) as? AppItem)?.appKey ?: return
                val name = folderName(listOfNotNull(targetApp, source.draggedApp))
                when (source) {
                    is DragSource.Item -> repository.makeFolder(source.item.id, target.targetId, name)
                    is DragSource.Dock -> repository.makeFolderFromDock(source.appKey, target.targetId, name)
                }
            }
            is DropTarget.IntoFolder -> source.draggedApp?.let { repository.addToFolder(it, target.folderId) }
            is DropTarget.Dock -> source.draggedApp?.let { repository.toDock(it, target.index) }
            null -> Unit
        }
    }

    private fun targetFor(workspace: Workspace, state: DragState): DropTarget? {
        val p = state.pointer
        val appKey = state.source.draggedApp
        if (dockBounds.contains(p)) {
            if (appKey == null) return null
            val inDock = state.source is DragSource.Dock
            if (!inDock && dockSize >= Workspace.DOCK_SIZE) return null
            val slots = if (inDock) dockSize else dockSize + 1
            val index = floor((p.x - dockBounds.left) / (dockBounds.width / slots.coerceAtLeast(1))).toInt()
            return DropTarget.Dock(index.coerceIn(0, slots - 1))
        }
        if (!gridBounds.contains(p) || cellWidth <= 0f) return null
        val page = pager.currentPage
        if (page >= homePages) return null
        val col = floor((p.x - gridBounds.left) / cellWidth).toInt().coerceIn(0, columns - 1)
        val row = floor((p.y - gridBounds.top) / cellHeight).toInt().coerceIn(0, rows - 1)
        val under = workspace.itemAt(page, col, row)
        val dragged = (state.source as? DragSource.Item)?.item
        if (appKey != null && under != null && under.id != dragged?.id) {
            // Close to the middle of an app or anywhere on a folder: group them.
            when (under) {
                // A full folder takes no more apps; the app lands beside it instead.
                is FolderItem -> if (!under.isFull) return DropTarget.IntoFolder(under.id)
                is AppItem -> {
                    val centerX = gridBounds.left + (col + 0.5f) * cellWidth
                    val centerY = gridBounds.top + (row + 0.5f) * cellHeight
                    if (abs(p.x - centerX) < cellWidth * 0.3f && abs(p.y - centerY) < cellHeight * 0.3f) {
                        return DropTarget.MakeFolder(under.id)
                    }
                }
                is WidgetItem -> Unit
            }
        }
        // Elements larger than one cell are anchored where the finger holds them.
        val w = dragged?.cell?.w ?: 1
        val h = dragged?.cell?.h ?: 1
        val grabCol = if (w > 1) floor(state.grab.x / cellWidth).toInt().coerceIn(0, w - 1) else 0
        val grabRow = if (h > 1) floor(state.grab.y / cellHeight).toInt().coerceIn(0, h - 1) else 0
        return DropTarget.Move(workspace.grid.clamp(Cell(page, col - grabCol, row - grabRow, w, h)))
    }

    /** Holding an element at the left or right edge turns the page, as on iOS. */
    private fun flipPageAtEdges(pointer: Offset) {
        val edge = screenWidth * 0.06f
        val direction = when {
            pointer.x < edge -> -1
            pointer.x > screenWidth - edge -> 1
            else -> 0
        }
        if (direction == 0) {
            edgeJob?.cancel()
            edgeJob = null
            return
        }
        if (edgeJob?.isActive == true) return
        edgeJob = scope.launch {
            delay(EDGE_DELAY_MS)
            val next = (pager.currentPage + direction).coerceIn(0, homePages - 1)
            if (next != pager.currentPage) pager.animateScrollToPage(next)
            edgeJob = null
        }
    }

    private companion object {
        const val TAG = "HomeController"
        const val EDGE_DELAY_MS = 650L
    }
}
