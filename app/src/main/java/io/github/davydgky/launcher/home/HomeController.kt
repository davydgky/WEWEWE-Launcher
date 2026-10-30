package io.github.davydgky.launcher.home

import android.appwidget.AppWidgetProviderInfo
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.unit.Dp
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

/** Where the elements stand while the dragged one hovers over [cell]: they have made room for it. */
class DragPreview(val cell: Cell, val cells: Map<Long, Cell>)

/** A widget being resized by its handles: the size it has under the finger, in cells. */
data class WidgetResize(val id: Long, val w: Int, val h: Int)

/** An element just let go; it glides from [topLeft] (root coordinates) into its new place. */
class DroppedItem(val id: Long, val topLeft: Offset)

@Stable
class DragState(
    val source: DragSource,
    start: Offset,
    val grab: Offset,
    val size: IntSize,
    /** The finger that drags; followed at the root of the screen ([homeDragTracker]). */
    val pointerId: PointerId,
    val iconSize: Dp,
) {
    var pointer by mutableStateOf(start)
    var target by mutableStateOf<DropTarget?>(null)
    var preview by mutableStateOf<DragPreview?>(null)

    /** Where [preview] (or else the arrangement) puts [item]. */
    fun cellOf(item: HomeItem): Cell = preview?.cells?.get(item.id) ?: item.cell
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

    /** A widget resized in edit mode, while its handle is held; the others make room for it live. */
    var resizing by mutableStateOf<WidgetResize?>(null)

    /** The views of the widgets on screen, so a swipe can tell whether it scrolls one of them. */
    val widgetViews = HashMap<Long, View>()

    /**
     * True if a widget under [position] (window coordinates) can scroll in [direction] (negative: back
     * towards its top): then a swipe there is the widget's, not the search's.
     */
    fun widgetScrollsAt(position: Offset, direction: Int): Boolean =
        widgetViews.values.any { it.canScrollAt(position.x, position.y, direction) }

    /** The element dropped last, until it has started gliding into its place. */
    var drop by mutableStateOf<DroppedItem?>(null)
    var openFolderId by mutableStateOf<Long?>(null)

    /**
     * Edit mode as on iOS: elements wiggle, show a remove badge and move without a long press. Entered by
     * a long press on the background, from an element's menu or by dragging an element.
     */
    var editMode by mutableStateOf(false)
        private set

    /**
     * Elements wiggle in edit mode, but not while one is dragged or a panel covers the home screen: the
     * glass behind a panel would otherwise blur a new picture every frame.
     */
    val wiggling: Boolean
        get() = editMode && drag == null && resizing == null && openFolderId == null && openCategory == null && !widgetPickerOpen && !pagesOverviewOpen

    /** The user locked the arrangement: no edit mode, no dragging, nothing removed by accident. */
    var layoutLocked by mutableStateOf(false)

    /** Icons show how many notifications there are instead of a dot. */
    var badgeNumbers by mutableStateOf(false)

    /** A double tap on the background turns the screen off. */
    var doubleTapToLock by mutableStateOf(false)

    /** Set by the screen: the lock service is off, so the user is sent to switch it on. */
    var lockUnavailable: () -> Unit = {}

    fun startEditing() {
        if (layoutLocked) return
        editMode = true
        dismissMenus()
    }

    fun stopEditing() {
        editMode = false
        // The home button or leaving the launcher while a finger still drags: the element stays where it was.
        if (drag != null) {
            drag = null
            edgeJob?.cancel()
            previewJob?.cancel()
            pendingPreview = null
        }
    }
    var openCategory by mutableStateOf<LibraryGroup?>(null)
    var widgetPickerOpen by mutableStateOf(false)

    /** Limits the widget picker to one app (from the app's long-press menu); null shows all. */
    var widgetPickerPackage by mutableStateOf<String?>(null)

    fun openWidgetPicker(packageName: String? = null) {
        widgetPickerPackage = packageName
        widgetPickerOpen = true
    }

    /** All pages side by side, to remove whole pages (edit mode). */
    var pagesOverviewOpen by mutableStateOf(false)

    /** Latest arrangement; set by the home screen on every composition. */
    var workspace: Workspace? = null

    /** Name for a new folder from the apps it starts with. */
    var folderName: (List<String>) -> String = { "" }

    /** A better name from Gemini Nano, given a moment later; null without it. */
    var smartFolderName: (suspend (List<String>) -> String?)? = null

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

    /** The home page on screen, or the last one while the App Library shows. */
    val currentHomePage: Int
        get() = if (::pager.isInitialized) pager.currentPage.coerceIn(0, (homePages - 1).coerceAtLeast(0)) else 0

    /** Swipe up on the home screen: the App Library, as the app drawer on Pixel phones. */
    fun showLibrary() {
        if (!::pager.isInitialized) return
        scope.launch { pager.animateScrollToPage(homePages) }
    }

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
        get() = editMode || holding || drag != null || openFolderId != null || openCategory != null || widgetPickerOpen ||
            pagesOverviewOpen

    private var edgeJob: Job? = null
    private var previewJob: Job? = null
    private var pendingPreview: Cell? = null
    private var restingAt = Offset.Zero

    fun startDrag(source: DragSource, pointer: Offset, grab: Offset, size: IntSize, pointerId: PointerId, iconSize: Dp) {
        if (layoutLocked) return
        Log.i(TAG, "Drag $source")
        drop = null
        drag = DragState(source, pointer, grab, size, pointerId, iconSize)
        editMode = true
    }

    fun moveDrag(pointer: Offset) {
        val state = drag ?: return
        val workspace = workspace ?: return
        state.pointer = pointer
        val target = targetFor(workspace, state)
        state.target = target
        updatePreview(state, target)
        flipPageAtEdges()
    }

    /**
     * The others make room once the finger rests over a cell for a moment, as on Pixel phones; passing
     * over cells on the way (say, towards an app to make a folder with) moves nothing.
     */
    private fun updatePreview(state: DragState, target: DropTarget?) {
        val item = (state.source as? DragSource.Item)?.item ?: return
        when (target) {
            is DropTarget.Move -> {
                val resting = (state.pointer - restingAt).getDistance() <= REST_SLOP_DP * density
                if (target.cell == pendingPreview && resting) return
                previewJob?.cancel()
                pendingPreview = null
                if (target.cell == state.preview?.cell) return
                restingAt = state.pointer
                pendingPreview = target.cell
                previewJob = scope.launch {
                    delay(REORDER_DELAY_MS)
                    val workspace = workspace ?: return@launch
                    state.preview = DragPreview(target.cell, workspace.previewMove(item.id, target.cell))
                    pendingPreview = null
                }
            }
            // Over an app or a folder: what made room stays, so the app under the finger stays there.
            is DropTarget.MakeFolder, is DropTarget.IntoFolder -> {
                previewJob?.cancel()
                pendingPreview = null
            }
            // Over the dock or off the grid: everything goes back.
            is DropTarget.Dock, null -> {
                previewJob?.cancel()
                pendingPreview = null
                state.preview = null
            }
        }
    }

    fun endDrag() {
        val state = drag ?: return
        drag = null
        edgeJob?.cancel()
        previewJob?.cancel()
        pendingPreview = null
        val workspace = workspace ?: return
        val source = state.source
        val target = targetFor(workspace, state)
        Log.i(TAG, "Drop at ${state.pointer} on $target")
        // Room made while hovering stays made when the app joins a folder.
        val madeRoom: (Workspace) -> Workspace = { ws ->
            val item = (source as? DragSource.Item)?.item
            val cell = state.preview?.cell
            if (item != null && cell != null) ws.move(item.id, cell) else ws
        }
        when (target) {
            is DropTarget.Move -> when (source) {
                is DragSource.Item -> {
                    drop = DroppedItem(source.item.id, state.pointer - state.grab)
                    repository.move(source.item.id, target.cell)
                }
                is DragSource.Dock -> repository.fromDock(source.appKey, target.cell)
            }
            is DropTarget.MakeFolder -> {
                val targetApp = (workspace.item(target.targetId) as? AppItem)?.appKey ?: return
                val keys = listOfNotNull(targetApp, source.draggedApp)
                val name = folderName(keys)
                when (source) {
                    is DragSource.Item -> repository.update { madeRoom(it).makeFolder(source.item.id, target.targetId, name) }
                    is DragSource.Dock -> repository.makeFolderFromDock(source.appKey, target.targetId, name)
                }
                renameSmartly(keys, name)
            }
            is DropTarget.IntoFolder -> source.draggedApp?.let { app -> repository.update { madeRoom(it).addToFolder(app, target.folderId) } }
            is DropTarget.Dock -> source.draggedApp?.let { repository.toDock(it, target.index) }
            null -> Unit
        }
    }

    /** Gives a new folder Gemini Nano's name for its apps, unless it was renamed in the meantime. */
    private fun renameSmartly(keys: List<String>, name: String) {
        val ask = smartFolderName ?: return
        scope.launch {
            val better = ask(keys) ?: return@launch
            val folder = repository.workspace.value?.items?.filterIsInstance<FolderItem>()
                ?.firstOrNull { it.name == name && it.apps.containsAll(keys) } ?: return@launch
            if (better != name) repository.renameFolder(folder.id, better)
        }
    }

    private fun targetFor(workspace: Workspace, state: DragState): DropTarget? {
        val p = state.pointer
        val appKey = state.source.draggedApp
        // The dock is hidden on the App Library.
        if (!onLibrary && dockBounds.contains(p)) {
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
        // What is under the finger as the screen shows it: elements that made room count where they are now.
        val under = workspace.items.firstOrNull { state.cellOf(it).contains(page, col, row) }
        val dragged = (state.source as? DragSource.Item)?.item
        if (appKey != null && under != null && under.id != dragged?.id) {
            // Close to the middle of an app or anywhere on a folder: group them.
            when (under) {
                // A full folder takes no more apps; the app lands beside it instead.
                is FolderItem -> if (!under.isFull) return DropTarget.IntoFolder(under.id)
                is AppItem -> {
                    val cell = state.cellOf(under)
                    val centerX = gridBounds.left + (cell.x + 0.5f) * cellWidth
                    val centerY = gridBounds.top + (cell.y + 0.5f) * cellHeight
                    if (abs(p.x - centerX) < cellWidth * 0.35f && abs(p.y - centerY) < cellHeight * 0.35f) {
                        return DropTarget.MakeFolder(under.id)
                    }
                }
                is WidgetItem, is ShortcutItem -> Unit
            }
        }
        // Elements larger than one cell are anchored where the finger holds them.
        val w = dragged?.cell?.w ?: 1
        val h = dragged?.cell?.h ?: 1
        val grabCol = if (w > 1) floor(state.grab.x / cellWidth).toInt().coerceIn(0, w - 1) else 0
        val grabRow = if (h > 1) floor(state.grab.y / cellHeight).toInt().coerceIn(0, h - 1) else 0
        return DropTarget.Move(workspace.grid.clamp(Cell(page, col - grabCol, row - grabRow, w, h)))
    }

    /** Holding an element at the left or right edge turns the page, as on iOS, page after page while it stays there. */
    private fun flipPageAtEdges() {
        val state = drag ?: return
        if (edgeDirection(state.pointer) == 0) {
            edgeJob?.cancel()
            edgeJob = null
            return
        }
        if (edgeJob?.isActive == true) return
        edgeJob = scope.launch {
            while (true) {
                delay(EDGE_DELAY_MS)
                val current = drag ?: break
                val direction = edgeDirection(current.pointer)
                if (direction == 0) break
                val next = (pager.currentPage + direction).coerceIn(0, homePages - 1)
                if (next == pager.currentPage) break
                pager.animateScrollToPage(next)
                // The finger did not move, but the cell under it is a new one.
                moveDrag(current.pointer)
            }
        }
    }

    private fun edgeDirection(pointer: Offset): Int {
        val edge = screenWidth * 0.06f
        return when {
            pointer.x < edge -> -1
            pointer.x > screenWidth - edge -> 1
            else -> 0
        }
    }

    private companion object {
        const val TAG = "HomeController"
        const val EDGE_DELAY_MS = 650L
        const val REORDER_DELAY_MS = 250L
        const val REST_SLOP_DP = 10f
    }
}

/** True if this view, or one of its children under [x], [y] (window coordinates), can scroll in [direction]. */
private fun View.canScrollAt(x: Float, y: Float, direction: Int): Boolean {
    if (!isShown) return false
    val location = IntArray(2)
    getLocationInWindow(location)
    if (x < location[0] || y < location[1] || x >= location[0] + width || y >= location[1] + height) return false
    if (canScrollVertically(direction)) return true
    if (this is ViewGroup) for (i in 0 until childCount) if (getChildAt(i).canScrollAt(x, y, direction)) return true
    return false
}
