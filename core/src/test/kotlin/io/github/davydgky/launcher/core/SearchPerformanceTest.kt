package io.github.davydgky.launcher.core

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the per-keystroke budget on a large, realistic index: thousands of contacts, events, pages
 * and pictures with recognised text. The device budget is 50 ms including drawing; the search itself
 * must stay far below that even on a slow CI machine.
 */
class SearchPerformanceTest {

    private val words = listOf(
        "rechnung", "termin", "anna", "berlin", "projekt", "urlaub", "garantie", "vertrag", "bahn", "ticket",
        "einkauf", "arzt", "steuer", "foto", "konzert", "meeting", "passwort", "adresse", "notiz", "bericht",
    )

    private fun text(random: Random, count: Int) =
        List(count) { words[random.nextInt(words.size)] + random.nextInt(500) }.joinToString(" ")

    @Test
    fun keystrokesStayWithinBudget() {
        val random = Random(42)
        val engine = SearchEngine()
        engine.publish("apps", SearchIndex.build(List(300) { SearchItem("a$it", SourceKind.App, text(random, 2)) }))
        engine.publish("contacts", SearchIndex.build(List(3_000) {
            SearchItem("c$it", SourceKind.Contact, text(random, 2), keywords = listOf("0176${random.nextInt(9_999_999)}"))
        }))
        engine.publish("history", SearchIndex.build(List(3_000) { SearchItem("h$it", SourceKind.VeloHistory, text(random, 5)) }))
        engine.publish("images", SearchIndex.build(List(20_000) {
            SearchItem("i$it", SourceKind.Image, "IMG_$it", body = text(random, 60))
        }))

        val typed = listOf("r", "re", "rec", "rech", "rechn", "rechnung", "rechnung 1", "rechnung 12", "a", "an", "ann", "anna 4")
        repeat(3) { typed.forEach { engine.search(it) } } // warm up the JIT
        val start = System.nanoTime()
        val rounds = 10
        repeat(rounds) { typed.forEach { engine.search(it) } }
        val averageMs = (System.nanoTime() - start) / 1e6 / (rounds * typed.size)
        println("Average search over ${engine.itemCount} items: %.2f ms".format(averageMs))
        assertTrue("average $averageMs ms", averageMs < 25.0)
    }
}
