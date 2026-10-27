package io.github.davydgky.launcher.home

import io.github.davydgky.launcher.core.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {

    private val apps = (1..10).map { "app$it" }

    private fun initial() = Workspace.initial(columns = 4, rows = 6, apps = apps, dock = listOf("app1", "app2"))

    private fun Workspace.appItem(key: String) = items.filterIsInstance<AppItem>().single { it.appKey == key }

    private fun Workspace.assertNoOverlaps() {
        for (i in items.indices) for (j in i + 1 until items.size) {
            assertFalse("${items[i]} overlaps ${items[j]}", items[i].cell.overlaps(items[j].cell))
        }
    }

    @Test
    fun initialArrangementPutsEveryAppSomewhereOnce() {
        val ws = initial()
        assertEquals(listOf("app1", "app2"), ws.dock)
        assertEquals(8, ws.items.size)
        assertEquals(apps.toSet(), ws.appsOnHome())
        assertEquals(Cell(0, 0, 0), ws.appItem("app3").cell)
        ws.assertNoOverlaps()
    }

    @Test
    fun droppingAnAppOnAnotherMakesANothingStyleFolder() {
        val ws = initial()
        val folderWs = ws.makeFolder(ws.appItem("app4").id, ws.appItem("app3").id, "Ordner")
        val folder = folderWs.items.filterIsInstance<FolderItem>().single()
        assertEquals(listOf("app3", "app4"), folder.apps)
        assertEquals(Cell(0, 0, 0, 2, 2), folder.cell)
        assertEquals(apps.toSet(), folderWs.appsOnHome())
        folderWs.assertNoOverlaps()
    }

    @Test
    fun folderWithOneAppLeftBecomesThatApp() {
        val ws = initial()
        val folderWs = ws.makeFolder(ws.appItem("app4").id, ws.appItem("app3").id, "Ordner")
        val folder = folderWs.items.filterIsInstance<FolderItem>().single()
        val result = folderWs.removeFromFolder(folder.id, "app4")
        assertTrue(result.items.none { it is FolderItem })
        assertEquals(Cell(0, 0, 0), result.appItem("app3").cell)
        assertEquals(apps.toSet(), result.appsOnHome())
        result.assertNoOverlaps()
    }

    @Test
    fun removedAppsStayInTheLibraryOnly() {
        val ws = initial().removeApp("app5")
        assertFalse("app5" in ws.appsOnHome())
        // Still known: sync must not add it back as "new".
        assertFalse("app5" in ws.sync(apps, addNew = true).appsOnHome())
    }

    @Test
    fun syncDropsUninstalledAndAddsNewApps() {
        val ws = initial().sync(apps - "app3" - "app1" + "new", addNew = true)
        assertFalse("app3" in ws.appsOnHome())
        assertEquals(listOf("app2"), ws.dock)
        assertNotNull(ws.items.filterIsInstance<AppItem>().singleOrNull { it.appKey == "new" })
        val quiet = initial().sync(apps + "new2", addNew = false)
        assertFalse("new2" in quiet.appsOnHome())
        assertTrue("new2" in quiet.knownApps)
    }

    @Test
    fun movingOntoAnOccupiedCellSwapsPlaces() {
        val ws = initial()
        val moved = ws.move(ws.appItem("app5").id, Cell(0, 0, 0))
        assertEquals(Cell(0, 0, 0), moved.appItem("app5").cell)
        moved.assertNoOverlaps()
        assertEquals(8, moved.items.size)
    }

    @Test
    fun dockTakesAppsOffThePagesAndBack() {
        val ws = initial().toDock("app3", 0)
        assertEquals(listOf("app3", "app1", "app2"), ws.dock)
        assertTrue(ws.items.none { it is AppItem && it.appKey == "app3" })
        val back = ws.fromDock("app3", Cell(0, 3, 3))
        assertEquals(Cell(0, 3, 3), back.appItem("app3").cell)
        assertFalse("app3" in back.dock)
    }

    @Test
    fun widgetsFindRoomAndResize() {
        val ws = initial().addWidget(widgetId = 7, provider = "pkg/.Widget", w = 4, h = 2, page = 0)
        val widget = ws.items.filterIsInstance<WidgetItem>().single()
        assertEquals(Cell(0, 0, 2, 4, 2), widget.cell)
        val resized = ws.resize(widget.id, 4, 4)
        assertEquals(4, resized.item(widget.id)!!.cell.h)
        resized.assertNoOverlaps()
    }

    @Test
    fun emptyPagesCloseUp() {
        val many = Workspace.initial(4, 6, (1..30).map { "a$it" }, emptyList())
        assertEquals(2, many.pageCount)
        val lastPageApps = many.items.filter { it.cell.page == 1 }.map { (it as AppItem).appKey }
        val fewer = lastPageApps.fold(many) { ws, key -> ws.removeApp(key) }
        assertEquals(1, fewer.pageCount)
    }

    @Test
    fun codecRoundTrip() {
        val base = initial()
        val ws = base.makeFolder(base.appItem("app4").id, base.appItem("app3").id, "Spiele")
            .addWidget(3, "pkg/.W", 2, 2, 0)
        assertEquals(ws, WorkspaceCodec.decode(WorkspaceCodec.encode(ws)))
        assertEquals(null, WorkspaceCodec.decode("not json"))
    }
}
