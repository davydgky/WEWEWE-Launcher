package io.github.davydgky.launcher.home

import io.github.davydgky.launcher.core.Cell
import io.github.davydgky.launcher.core.HomeGrid
import org.json.JSONArray
import org.json.JSONObject

/** Something placed on a home screen page. */
sealed interface HomeItem {
    val id: Long
    val cell: Cell

    fun moved(cell: Cell): HomeItem
}

data class AppItem(override val id: Long, override val cell: Cell, val appKey: String) : HomeItem {
    override fun moved(cell: Cell) = copy(cell = cell.copy(w = 1, h = 1))
}

/**
 * How a folder looks: a [Classic] folder as on Pixel phones (one cell, opens on tap), or a tile in the
 * style of Nothing OS whose app icons open directly: [w]×[h] cells showing [appRows]×[appColumns] apps.
 */
enum class FolderStyle(val w: Int, val h: Int, val appRows: Int, val appColumns: Int) {
    Classic(1, 1, 2, 2),
    Small(1, 1, 1, 2),
    Wide(3, 1, 2, 3),
    Large(2, 2, 3, 3);

    val isTile: Boolean get() = this != Classic

    /** Apps shown on the home screen: the preview of a classic folder, every app of a tile. */
    val slots: Int get() = appRows * appColumns
}

/**
 * A folder of apps. [name] "" shows the default name, a name of only spaces shows no name at all.
 * [playful] tiles scatter their icons a little instead of lining them up.
 */
data class FolderItem(
    override val id: Long,
    override val cell: Cell,
    val name: String,
    val apps: List<String>,
    val style: FolderStyle = FolderStyle.Large,
    val playful: Boolean = false,
) : HomeItem {
    override fun moved(cell: Cell) = copy(cell = cell.copy(w = style.w, h = style.h))

    /** Tiles keep every app reachable on the home screen, so they take no more than the largest tile shows. */
    val isFull: Boolean get() = style.isTile && apps.size >= MAX_APPS

    val showsName: Boolean get() = name.isEmpty() || name.isNotBlank()

    companion object {
        /** As many apps as the largest tile (3×3) shows. */
        const val MAX_APPS = 9
    }
}

data class WidgetItem(override val id: Long, override val cell: Cell, val widgetId: Int, val provider: String) : HomeItem {
    override fun moved(cell: Cell) = copy(cell = cell)
}

/**
 * A pinned app shortcut on the home screen, such as a Chrome page or a WhatsApp chat. [shortcutKey] is
 * "shortcut:<user serial>/<package>/<id>"; [label] is shown while the shortcut itself is unavailable.
 */
data class ShortcutItem(override val id: Long, override val cell: Cell, val shortcutKey: String, val label: String) : HomeItem {
    override fun moved(cell: Cell) = copy(cell = cell.copy(w = 1, h = 1))

    val userSerial: Long get() = shortcutKey.removePrefix(PREFIX).substringBefore('/').toLongOrNull() ?: 0L
    val packageName: String get() = shortcutKey.removePrefix(PREFIX).substringAfter('/').substringBefore('/')
    val shortcutId: String get() = shortcutKey.removePrefix(PREFIX).substringAfter('/').substringAfter('/')

    companion object {
        private const val PREFIX = "shortcut:"
    }
}

/**
 * The arrangement of the home screen: pages of apps, folders and widgets, plus the dock. Immutable;
 * every operation returns a new arrangement. Apps that are on no page, in no folder and not in the dock
 * live only in the App Library.
 */
data class Workspace(
    val columns: Int,
    val rows: Int,
    val items: List<HomeItem>,
    val dock: List<String>,
    /** Every app key seen so far; anything else that is installed is new. */
    val knownApps: Set<String>,
    val nextId: Long,
) {
    val grid: HomeGrid get() = HomeGrid(columns, rows)

    val pageCount: Int get() = grid.pageCount(cells())

    fun item(id: Long): HomeItem? = items.firstOrNull { it.id == id }

    fun itemAt(page: Int, x: Int, y: Int): HomeItem? = items.firstOrNull { it.cell.contains(page, x, y) }

    fun itemsOn(page: Int): List<HomeItem> = items.filter { it.cell.page == page }

    /** App keys reachable from the home screen (pages, folders and dock). */
    fun appsOnHome(): Set<String> = buildSet {
        items.forEach { item ->
            when (item) {
                is AppItem -> add(item.appKey)
                is FolderItem -> addAll(item.apps)
                is WidgetItem, is ShortcutItem -> Unit
            }
        }
        addAll(dock)
    }

    private fun cells(): Map<Long, Cell> = items.associate { it.id to it.cell }

    private fun withCells(cells: Map<Long, Cell>): Workspace {
        val compact = grid.withoutEmptyPages(cells)
        return copy(items = items.mapNotNull { item -> compact[item.id]?.let(item::moved) })
    }

    private fun with(item: HomeItem): Workspace = copy(items = items.filter { it.id != item.id } + item)

    /** Moves an element; whatever it lands on moves aside. */
    fun move(id: Long, target: Cell): Workspace {
        val item = item(id) ?: return this
        return withCells(grid.place(cells(), id, target.copy(w = item.cell.w, h = item.cell.h)))
    }

    /**
     * Where every element would stand after [move], keeping page numbers: what the home screen shows
     * while [id] hovers over [target], so the others make room before the finger lifts.
     */
    fun previewMove(id: Long, target: Cell): Map<Long, Cell> {
        val item = item(id) ?: return cells()
        return grid.place(cells(), id, target.copy(w = item.cell.w, h = item.cell.h))
    }

    /**
     * Puts an app on the home screen at [target], or on the first free cell. An app is only ever in one
     * place, so it leaves the dock, a folder or its previous cell.
     */
    fun addApp(appKey: String, target: Cell? = null): Workspace {
        val base = withoutApp(appKey)
        val id = base.nextId
        val cell = (target ?: base.grid.firstFree(base.cells(), 1, 1)).copy(w = 1, h = 1)
        val added = base.copy(items = base.items + AppItem(id, cell, appKey), nextId = id + 1)
        return added.withCells(base.grid.place(added.cells(), id, cell))
    }

    /** Removes an element. Apps of a removed folder stay installed and remain in the App Library. */
    fun remove(id: Long): Workspace = copy(items = items.filter { it.id != id }).let { it.withCells(it.cells()) }

    /**
     * Removes a whole page. Its apps and folder contents stay in the App Library, its widgets go away,
     * and the pages after it move up.
     */
    fun removePage(page: Int): Workspace =
        copy(items = items.filter { it.cell.page != page }).let { it.withCells(it.cells()) }

    /** Takes an app off the home screen wherever it is: page, folder or dock. */
    fun removeApp(appKey: String): Workspace = withoutApp(appKey).let { it.withCells(it.cells()) }

    /** [removeApp] without closing the gap of an emptied page, so page numbers stay valid. */
    private fun withoutApp(appKey: String): Workspace = copy(
        items = items.mapNotNull { item ->
            when (item) {
                is AppItem -> item.takeIf { it.appKey != appKey }
                is FolderItem -> if (appKey in item.apps) item.withApps(item.apps - appKey) else item
                is WidgetItem, is ShortcutItem -> item
            }
        },
        dock = dock - appKey,
    )

    /** Dropping app [draggedId] on app [targetId] makes a folder where the target was. */
    fun makeFolder(draggedId: Long, targetId: Long, name: String): Workspace {
        val dragged = item(draggedId) as? AppItem ?: return this
        val target = item(targetId) as? AppItem ?: return this
        val style = FolderStyle.Large
        val folder = FolderItem(nextId, target.cell.copy(w = style.w, h = style.h), name, listOf(target.appKey, dragged.appKey), style)
        val ws = copy(items = items.filter { it.id != draggedId && it.id != targetId } + folder, nextId = nextId + 1)
        return ws.withCells(grid.place(ws.cells(), folder.id, folder.cell))
    }

    /** Makes a folder from an app that is not on a page yet (e.g. from the dock) and app [targetId]. */
    fun makeFolderWith(appKey: String, targetId: Long, name: String): Workspace {
        val withApp = addApp(appKey, grid.firstFree(cells(), 1, 1))
        val added = withApp.items.last { it is AppItem && it.appKey == appKey }
        return withApp.makeFolder(added.id, targetId, name)
    }

    fun addToFolder(appKey: String, folderId: Long): Workspace {
        val folder = item(folderId) as? FolderItem ?: return this
        if (appKey in folder.apps || folder.isFull) return this
        val ws = withoutApp(appKey)
        val current = ws.item(folderId) as? FolderItem ?: return this
        return ws.with(current.copy(apps = current.apps + appKey)).let { it.withCells(it.cells()) }
    }

    /** Takes an app out of a folder and puts it on the first free cell of the folder's page. */
    fun removeFromFolder(folderId: Long, appKey: String): Workspace {
        val folder = item(folderId) as? FolderItem ?: return this
        val ws = withoutApp(appKey)
        return ws.addApp(appKey, ws.grid.firstFree(ws.cells(), 1, 1, fromPage = folder.cell.page))
    }

    /** Replaces a folder with its apps as single icons. */
    fun dissolveFolder(folderId: Long): Workspace {
        val folder = item(folderId) as? FolderItem ?: return this
        var ws = copy(items = items.filter { it.id != folderId })
        folder.apps.forEachIndexed { index, app ->
            val preferred = if (index == 0) folder.cell.copy(w = 1, h = 1) else ws.grid.firstFree(ws.cells(), 1, 1, folder.cell.page)
            ws = ws.addApp(app, preferred)
        }
        return ws
    }

    /** Changes how a folder looks; a bigger folder pushes aside what is in its way. */
    fun setFolderStyle(folderId: Long, style: FolderStyle): Workspace {
        val folder = item(folderId) as? FolderItem ?: return this
        if (style.w > columns || style.h > rows) return this
        val target = grid.clamp(folder.cell.copy(w = style.w, h = style.h))
        val ws = with(folder.copy(style = style, cell = target))
        return ws.withCells(ws.grid.place(ws.cells(), folderId, target))
    }

    fun setFolderPlayful(folderId: Long, playful: Boolean): Workspace {
        val folder = item(folderId) as? FolderItem ?: return this
        return with(folder.copy(playful = playful))
    }

    /**
     * The same arrangement on a grid of [newColumns]×[newRows]: everything that still fits keeps its
     * place, the rest moves to the next free place from its page on.
     */
    fun withGrid(newColumns: Int, newRows: Int): Workspace {
        if (newColumns == columns && newRows == rows) return this
        val newGrid = HomeGrid(newColumns, newRows)
        val placed = HashMap<Long, Cell>()
        val kept = items.sortedWith(compareBy({ it.cell.page }, { it.cell.y }, { it.cell.x })).map { original ->
            // A folder tile wider than the new grid becomes a classic folder; a widget shrinks to fit.
            val item = if (original is FolderItem && (original.style.w > newColumns || original.style.h > newRows)) {
                original.copy(style = FolderStyle.Classic)
            } else original
            val w = if (item is FolderItem) item.style.w else minOf(item.cell.w, newColumns)
            val h = if (item is FolderItem) item.style.h else minOf(item.cell.h, newRows)
            val wanted = item.cell.copy(w = w, h = h)
            val fits = wanted.x + w <= newColumns && wanted.y + h <= newRows && newGrid.isFree(placed, wanted)
            val cell = if (fits) wanted else newGrid.firstFree(placed, w, h, fromPage = item.cell.page)
            placed[item.id] = cell
            if (item is WidgetItem) item.copy(cell = cell) else item.moved(cell)
        }
        return copy(columns = newColumns, rows = newRows, items = kept).let { it.withCells(it.cells()) }
    }

    fun renameFolder(folderId: Long, name: String): Workspace {
        val folder = item(folderId) as? FolderItem ?: return this
        return with(folder.copy(name = name))
    }

    /** Adds a widget on the first place of [page] (or later pages) where it fits. */
    fun addWidget(widgetId: Int, provider: String, w: Int, h: Int, page: Int): Workspace {
        val cell = grid.firstFree(cells(), w, h, fromPage = page)
        val ws = copy(items = items + WidgetItem(nextId, cell, widgetId, provider), nextId = nextId + 1)
        return ws.withCells(ws.cells())
    }

    fun resize(id: Long, w: Int, h: Int): Workspace {
        val item = item(id) as? WidgetItem ?: return this
        val target = grid.clamp(item.cell.copy(w = w, h = h))
        return copy(items = items.map { if (it.id == id) item.copy(cell = target) else it }).let { ws ->
            ws.withCells(ws.grid.place(ws.cells(), id, target))
        }
    }

    /** Puts a pinned shortcut on the first free cell of [page] or a later one; once only. */
    fun addShortcut(shortcutKey: String, label: String, page: Int): Workspace {
        if (items.any { it is ShortcutItem && it.shortcutKey == shortcutKey }) return this
        val cell = grid.firstFree(cells(), 1, 1, fromPage = page)
        val ws = copy(items = items + ShortcutItem(nextId, cell, shortcutKey, label), nextId = nextId + 1)
        return ws.withCells(ws.cells())
    }

    /** Pinned shortcut ids per user serial and package, as the system has to know them. */
    fun pinnedShortcuts(): Map<Pair<Long, String>, Set<String>> = items.filterIsInstance<ShortcutItem>()
        .groupBy({ it.userSerial to it.packageName }, { it.shortcutId })
        .mapValues { it.value.toSet() }

    /** Moves an app into the dock at [index]; it leaves its place on the pages. */
    fun toDock(appKey: String, index: Int): Workspace {
        if (dock.size >= DOCK_SIZE && appKey !in dock) return this
        val ws = withoutApp(appKey).let { it.withCells(it.cells()) }
        val list = ws.dock.toMutableList().apply { add(index.coerceIn(0, size), appKey) }
        return ws.copy(dock = list)
    }

    /** Takes an app out of the dock and places it at [target] (or the first free cell). */
    fun fromDock(appKey: String, target: Cell? = null): Workspace = addApp(appKey, target)

    /**
     * Brings the arrangement in line with the installed apps: uninstalled apps disappear from pages,
     * folders and dock, and so do shortcuts of packages no longer in [packages] (if known); newly
     * installed apps are added to the first free cell if [addNew].
     */
    fun sync(installed: List<String>, addNew: Boolean, packages: Set<String>? = null): Workspace {
        val present = installed.toSet()
        var ws = copy(
            items = items.mapNotNull { item ->
                when (item) {
                    is AppItem -> item.takeIf { it.appKey in present }
                    is FolderItem -> item.withApps(item.apps.filter { it in present })
                    is WidgetItem -> item
                    is ShortcutItem -> item.takeIf { packages == null || it.packageName in packages }
                }
            },
            dock = dock.filter { it in present },
        )
        ws = ws.withCells(ws.cells())
        // Tiles from before the limit keep their first apps; the others get their own cells.
        ws.items.filterIsInstance<FolderItem>().filter { it.style.isTile && it.apps.size > FolderItem.MAX_APPS }.forEach { folder ->
            val extra = folder.apps.drop(FolderItem.MAX_APPS)
            ws = ws.with(folder.copy(apps = folder.apps.take(FolderItem.MAX_APPS)))
            extra.forEach { ws = ws.addApp(it, ws.grid.firstFree(ws.cells(), 1, 1, fromPage = folder.cell.page)) }
        }
        if (addNew) {
            val onHome = ws.appsOnHome()
            installed.filter { it !in knownApps && it !in onHome }.forEach { ws = ws.addApp(it) }
        }
        return ws.copy(knownApps = present)
    }

    /** A folder with one app left becomes that app; an empty folder disappears. */
    private fun FolderItem.withApps(apps: List<String>): HomeItem? = when (apps.size) {
        0 -> null
        1 -> AppItem(id, cell.copy(w = 1, h = 1), apps.single())
        else -> copy(apps = apps)
    }

    companion object {
        const val DOCK_SIZE = 5

        /** A first arrangement: [dock] in the dock, all other apps in the given order on the pages. */
        fun initial(columns: Int, rows: Int, apps: List<String>, dock: List<String>): Workspace {
            var ws = Workspace(columns, rows, emptyList(), dock.take(DOCK_SIZE), apps.toSet(), 1L)
            apps.filter { it !in ws.dock }.forEach { ws = ws.addApp(it) }
            return ws
        }
    }
}

/** Storage format of [Workspace]: a small JSON document. */
object WorkspaceCodec {

    private const val VERSION = 1

    fun encode(ws: Workspace): String = JSONObject().apply {
        put("version", VERSION)
        put("columns", ws.columns)
        put("rows", ws.rows)
        put("nextId", ws.nextId)
        put("dock", JSONArray(ws.dock))
        put("known", JSONArray(ws.knownApps.toList()))
        put("items", JSONArray().apply {
            ws.items.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("page", item.cell.page)
                    put("x", item.cell.x)
                    put("y", item.cell.y)
                    put("w", item.cell.w)
                    put("h", item.cell.h)
                    when (item) {
                        is AppItem -> {
                            put("type", "app")
                            put("app", item.appKey)
                        }
                        is FolderItem -> {
                            put("type", "folder")
                            put("name", item.name)
                            put("apps", JSONArray(item.apps))
                            put("style", item.style.name)
                            put("playful", item.playful)
                        }
                        is WidgetItem -> {
                            put("type", "widget")
                            put("widgetId", item.widgetId)
                            put("provider", item.provider)
                        }
                        is ShortcutItem -> {
                            put("type", "shortcut")
                            put("shortcut", item.shortcutKey)
                            put("label", item.label)
                        }
                    }
                })
            }
        })
    }.toString()

    /** Null if [raw] is not a stored arrangement; unknown elements are skipped. */
    fun decode(raw: String): Workspace? = runCatching {
        val json = JSONObject(raw)
        val items = json.getJSONArray("items").objects().mapNotNull { item ->
            val cell = Cell(item.getInt("page"), item.getInt("x"), item.getInt("y"), item.optInt("w", 1), item.optInt("h", 1))
            val id = item.getLong("id")
            when (item.optString("type")) {
                "app" -> AppItem(id, cell, item.getString("app"))
                "folder" -> FolderItem(
                    id, cell, item.optString("name"), item.getJSONArray("apps").strings(),
                    style = FolderStyle.entries.firstOrNull { it.name == item.optString("style") } ?: FolderStyle.Large,
                    playful = item.optBoolean("playful", false),
                )
                "widget" -> WidgetItem(id, cell, item.getInt("widgetId"), item.getString("provider"))
                "shortcut" -> ShortcutItem(id, cell, item.getString("shortcut"), item.optString("label"))
                else -> null
            }
        }
        Workspace(
            columns = json.getInt("columns"),
            rows = json.getInt("rows"),
            items = items,
            dock = json.optJSONArray("dock")?.strings().orEmpty(),
            knownApps = json.optJSONArray("known")?.strings().orEmpty().toSet(),
            nextId = maxOf(json.optLong("nextId", 1L), (items.maxOfOrNull { it.id } ?: 0L) + 1),
        )
    }.getOrNull()

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
}
