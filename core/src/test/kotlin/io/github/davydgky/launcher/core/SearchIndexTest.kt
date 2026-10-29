package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {

    private fun item(key: String, title: String, kind: SourceKind = SourceKind.App, subtitle: String = "",
                     keywords: List<String> = emptyList(), body: String = "") =
        SearchItem(key, kind, title, subtitle, keywords, body)

    private fun SearchIndex.find(query: String): List<String> {
        val out = ArrayList<Match>()
        match(Query.parse(query), out)
        return out.sortedByDescending { it.score }.map { it.item.key }
    }

    @Test
    fun everyWordIsAPrefix() {
        val index = SearchIndex.build(listOf(item("maps", "Google Maps"), item("mail", "Gmail"), item("news", "Google News")))
        assertEquals(listOf("maps"), index.find("goo ma"))
        assertEquals(setOf("maps", "news"), index.find("goo").toSet())
        assertEquals(emptyList<String>(), index.find("goo xyz"))
    }

    @Test
    fun exactTitleBeatsPrefix() {
        val index = SearchIndex.build(listOf(item("long", "Notizen Archiv"), item("exact", "Notizen")))
        assertEquals(listOf("exact", "long"), index.find("notizen"))
    }

    @Test
    fun titleBeatsSubtitleAndBody() {
        val index = SearchIndex.build(listOf(
            item("body", "Quittung", kind = SourceKind.Image, body = "Rechnung Bäckerei Schmidt"),
            item("subtitle", "Brot", kind = SourceKind.Note, subtitle = "Bäckerei"),
            item("title", "Bäckerei Schmidt", kind = SourceKind.Contact),
        ))
        assertEquals(listOf("title", "subtitle", "body"), index.find("bäcker"))
    }

    @Test
    fun umlautsMatchBothSpellings() {
        val index = SearchIndex.build(listOf(item("c", "Jürgen Müller", kind = SourceKind.Contact)))
        assertEquals(listOf("c"), index.find("muller"))
        assertEquals(listOf("c"), index.find("mueller"))
        assertEquals(listOf("c"), index.find("Müll"))
        assertEquals(listOf("c"), index.find("juergen mue"))
    }

    @Test
    fun phoneNumbersMatchFromAnyPosition() {
        val index = SearchIndex.build(listOf(item("c", "Anna", kind = SourceKind.Contact, keywords = listOf("01761234567"))))
        assertEquals(listOf("c"), index.find("0176"))
        assertEquals(listOf("c"), index.find("1234567"))
        assertEquals(emptyList<String>(), index.find("999"))
    }

    @Test
    fun singleLetterOnlyMatchesTitles() {
        val index = SearchIndex.build(listOf(
            item("app", "Maps"),
            item("contact", "Tim", kind = SourceKind.Contact, subtitle = "Meier"),
        ))
        assertEquals(listOf("app"), index.find("m"))
        assertEquals(listOf("contact"), index.find("me"))
    }

    @Test
    fun bodyNeedsThreeLetters() {
        val index = SearchIndex.build(listOf(item("img", "Screenshot", kind = SourceKind.Image, body = "WLAN Passwort lautet")))
        assertEquals(emptyList<String>(), index.find("pa"))
        assertEquals(listOf("img"), index.find("pas"))
        assertEquals(listOf("img"), index.find("screen wlan"))
    }

    @Test
    fun initialsAndInfixForSmallIndexes() {
        val index = SearchIndex.build(listOf(item("maps", "Google Maps"), item("fb", "Facebook")))
        assertEquals(listOf("maps"), index.find("gm"))
        assertEquals(listOf("fb"), index.find("book"))
        val large = SearchIndex.build(listOf(item("fb", "Facebook")), scanTitles = false)
        assertEquals(emptyList<String>(), large.find("book"))
    }

    @Test
    fun emptyQueryAndIndex() {
        assertEquals(emptyList<String>(), SearchIndex.Empty.find("a"))
        assertEquals(emptyList<String>(), SearchIndex.build(listOf(item("a", "A"))).find(" ,"))
    }

    @Test
    fun repeatedWordsInBodyAreIndexedOnce() {
        val index = SearchIndex.build(listOf(item("n", "Note", kind = SourceKind.Note, body = "test test test")))
        val out = ArrayList<Match>()
        index.match(Query.parse("test"), out)
        assertEquals(1, out.size)
        assertTrue(out[0].score > 0f)
    }

    @Test
    fun typosFindAppsSettingsAndContacts() {
        val index = SearchIndex.build(listOf(
            item("spotify", "Spotify"),
            item("youtube", "YouTube"),
            item("whatsapp", "WhatsApp"),
            item("bt", "Bluetooth", kind = SourceKind.Setting),
            item("anna", "Annabelle Schneider", kind = SourceKind.Contact),
            item("note", "Spotify Rechnung", kind = SourceKind.Note),
        ))
        assertEquals(listOf("spotify"), index.find("spotfy"))
        assertEquals(listOf("youtube"), index.find("yuotube"))
        assertEquals(listOf("whatsapp"), index.find("whatsap"))
        assertEquals(listOf("bt"), index.find("bluetoth"))
        assertEquals(listOf("anna"), index.find("schnieder"))
        // Short words and wrong first letters stay exact; notes are never matched with typos.
        assertEquals(emptyList<String>(), index.find("spo xyz"))
        assertEquals(emptyList<String>(), index.find("xpotify"))
        assertEquals(emptyList<String>(), index.find("rechnnug"))
    }

    @Test
    fun exactMatchesOutrankTypos() {
        val index = SearchIndex.build(listOf(item("maps", "Maps"), item("mapss", "Mapss Tool")))
        val out = ArrayList<Match>()
        index.match(Query.parse("maps"), out)
        assertEquals("maps", out.maxBy { it.score }.item.key)
    }

    @Test
    fun prefixDistance() {
        assertEquals(0, Typos.prefixDistance("spot", "spotify", 1))
        assertEquals(1, Typos.prefixDistance("spotfy", "spotify", 1))
        assertEquals(1, Typos.prefixDistance("yuotube", "youtube", 1))
        assertEquals(2, Typos.prefixDistance("einstelungne", "einstellungen", 2))
        assertEquals(2, Typos.prefixDistance("mail", "maps", 1))
    }
}
