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
 * A folder in the style of Nothing OS: it covers 2×2 cells and shows its first apps as icons that open
 * directly, without opening the folder first.
 */
data class FolderItem(override val id: Long, override val cell: Cell, val name: String, val apps: List<String>) : HomeItem {
    override fun moved(cell: Cell) = copy(cell = cell.copy(w = SIZE, h = SIZE))

    companion object {
        const val SIZE = 2

        /** Apps shown as full icons; with more apps the last slot shows the rest in miniature. */
        const val DIRECT_SLOTS = 4
    }
}

data class WidgetItem(override val id: Long, override val cell: Cell, val widgetId: Int, val provider: String) : HomeItem {
    override fun moved(cell: Cell) = copy(cell = cell)
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
                is WidgetItem -> Unit
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

    /** Takes an app off the home screen wherever it is: page, folder or dock. */
    fun removeApp(appKey: String): Workspace = withoutApp(appKey).let { it.withCells(it.cells()) }

    /** [removeApp] without closing the gap of an emptied page, so page numbers stay valid. */
    private fun withoutApp(appKey: String): Workspace = copy(
        items = items.mapNotNull { item ->
            when (item) {
                is AppItem -> item.takeIf { it.appKey != appKey }
                is FolderItem -> if (appKey in item.apps) item.withApps(item.apps - appKey) else item
                is WidgetItem -> item
            }
        },
        dock = dock - appKey,
    )

    /** Dropping app [draggedId] on app [targetId] makes a folder where the target was. */
    fun makeFolder(draggedId: Long, targetId: Long, name: String): Workspace {
        val dragged = item(draggedId) as? AppItem ?: return this
        val target = item(targetId) as? AppItem ?: return this
        val folder = FolderItem(nextId, target.cell.copy(w = FolderItem.SIZE, h = FolderItem.SIZE), name, listOf(target.appKey, dragged.appKey))
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
        if (appKey in folder.apps) return this
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
     * folders and dock; newly installed apps are added to the first free cell if [addNew].
     */
    fun sync(installed: List<String>, addNew: Boolean): Workspace {
        val present = installed.toSet()
        var ws = copy(
            items = items.mapNotNull { item ->
                when (item) {
                    is AppItem -> item.takeIf { it.appKey in present }
                    is FolderItem -> item.withApps(item.apps.filter { it in present })
                    is WidgetItem -> item
                }
            },
            dock = dock.filter { it in present },
        )
        ws = ws.withCells(ws.cells())
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
        const val DOCK_SIZE = 4

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
                        }
                        is WidgetItem -> {
                            put("type", "widget")
                            put("widgetId", item.widgetId)
                            put("provider", item.provider)
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
                "folder" -> FolderItem(id, cell, item.optString("name"), item.getJSONArray("apps").strings())
                "widget" -> WidgetItem(id, cell, item.getInt("widgetId"), item.getString("provider"))
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
