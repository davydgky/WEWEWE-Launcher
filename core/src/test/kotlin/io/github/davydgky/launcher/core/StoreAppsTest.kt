package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreAppsTest {

    private val page = """
        <html><head><script>var x = "details?id=com.fake.script";</script></head><body>
        <div><a class="Si6A0c" href="/store/apps/details?id=de.hafas.android.db">
          <img src="https://play-lh.googleusercontent.com/abc_DEF-1=s64-rw" alt="Thumbnail image">
          <div><span class="DdYX5">DB Navigator</span><span class="wMUdtb">Deutsche Bahn</span>
          <div><span class="w2kbF">4.6</span><span>star</span></div></div></a></div>
        <div><a href="/store/apps/details?id=com.flixbus.app">
          <img src="https://play-lh.googleusercontent.com/xyz=s64">
          <span>FlixBus &amp; FlixTrain</span><span>FlixMobility</span><span>4,7</span></a>
          <a href="/store/apps/details?id=com.flixbus.app">duplicate</a></div>
        <a href="/store/apps/details?id=com.example.noname"><img src="https://play-lh.googleusercontent.com/q=s64"></a>
        <a href="/store/apps/details?id=org.example.last"><span>Last App</span></a>
        </body></html>
    """.trimIndent()

    @Test
    fun readsTheResults() {
        val apps = StoreApps.parse(page)
        assertEquals(listOf("de.hafas.android.db", "com.flixbus.app", "org.example.last"), apps.map { it.packageName })
        val db = apps[0]
        assertEquals("DB Navigator", db.title)
        assertEquals("Deutsche Bahn", db.developer)
        assertEquals("4,6", db.rating)
        assertEquals("https://play-lh.googleusercontent.com/abc_DEF-1=s96", db.iconUrl)
        assertEquals("FlixBus & FlixTrain", apps[1].title)
        assertEquals("4,7", apps[1].rating)
    }

    @Test
    fun nothingFromAnEmptyOrChangedPage() {
        assertTrue(StoreApps.parse("<html><body>Keine Ergebnisse</body></html>").isEmpty())
    }

    @Test
    fun suggestionsFromGeminiNano() {
        val apps = StoreApps.parseSuggestions(
            """
            - DB Navigator | de.hafas.android.db
            * FlixBus | `com.flixbus.app`
            Trainline | not a package
            Omio
            """.trimIndent(),
        )
        assertEquals(listOf("de.hafas.android.db", "com.flixbus.app"), apps.map { it.packageName })
        assertTrue(apps.all { it.suggested })
    }
}
