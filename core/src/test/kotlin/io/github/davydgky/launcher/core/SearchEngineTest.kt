package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {

    private fun engine(vararg sources: Pair<String, List<SearchItem>>) = SearchEngine().apply {
        sources.forEach { (id, items) -> publish(id, SearchIndex.build(items)) }
    }

    @Test
    fun appBeatsHistoryWithSameTitle() {
        val engine = engine(
            "apps" to listOf(SearchItem("app:wiki", SourceKind.App, "Wikipedia")),
            "velo" to listOf(SearchItem("url:wiki", SourceKind.VeloHistory, "Wikipedia", "de.wikipedia.org")),
        )
        val results = engine.search("wiki")
        assertEquals("app:wiki", results.top?.item?.key)
        assertEquals(listOf(SourceKind.VeloHistory), results.groups.map { it.kind })
    }

    @Test
    fun topHitIsRemovedFromItsGroup() {
        val engine = engine("apps" to listOf(
            SearchItem("a", SourceKind.App, "Kalender"),
            SearchItem("b", SourceKind.App, "Kamera"),
        ))
        val results = engine.search("ka")
        val top = results.top!!
        assertTrue(results.groups.single().items.none { it.item === top.item })
    }

    @Test
    fun weakMatchesDoNotBecomeTopHit() {
        val engine = engine("images" to listOf(SearchItem("i", SourceKind.Image, "IMG_0001", body = "Garantie Bedingungen")))
        val results = engine.search("garantie")
        assertNull(results.top)
        assertEquals(SourceKind.Image, results.groups.single().kind)
    }

    @Test
    fun engagementDecidesBetweenEqualMatches() {
        val engine = engine("contacts" to listOf(
            SearchItem("anna.a", SourceKind.Contact, "Anna Albers"),
            SearchItem("anna.b", SourceKind.Contact, "Anna Brecht"),
        ))
        assertEquals("anna.a", engine.search("anna").top?.item?.key)
        val results = engine.search("anna") { if (it == "anna.b") 0.8f else 0f }
        assertEquals("anna.b", results.top?.item?.key)
    }

    @Test
    fun groupsAreOrderedByTheirBestResult() {
        val engine = engine(
            "notes" to listOf(SearchItem("n", SourceKind.Note, "Urlaub", body = "Packliste")),
            "events" to listOf(SearchItem("e", SourceKind.Event, "Urlaub Packliste")),
            "images" to listOf(SearchItem("i", SourceKind.Image, "Foto", body = "Packliste")),
        )
        val results = engine.search("packliste")
        assertEquals("e", results.top?.item?.key)
        assertEquals(listOf(SourceKind.Note, SourceKind.Image), results.groups.map { it.kind })
    }

    @Test
    fun publishingReplacesASource() {
        val engine = engine("apps" to listOf(SearchItem("old", SourceKind.App, "Alt")))
        engine.publish("apps", SearchIndex.build(listOf(SearchItem("new", SourceKind.App, "Neu"))))
        assertTrue(engine.search("alt").isEmpty)
        assertEquals("new", engine.search("neu").top?.item?.key)
        engine.remove("apps")
        assertEquals(0, engine.itemCount)
    }

    @Test
    fun groupSizeIsLimited() {
        val items = (1..40).map { SearchItem("h$it", SourceKind.VeloHistory, "Seite $it") }
        val results = engine("velo" to items).search("seite", maxPerGroup = 10)
        assertEquals(10, results.groups.single().items.size)
    }
}
