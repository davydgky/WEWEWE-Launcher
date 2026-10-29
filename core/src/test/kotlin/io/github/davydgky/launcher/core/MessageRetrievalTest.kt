package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRetrievalTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun message(key: String, sender: String, text: String, hoursAgo: Long, app: String = "WhatsApp") =
        MessageText(key, app, sender, sender, text, now - hoursAgo * hour)

    private val messages = listOf(
        message("1", "Anna", "Wir treffen uns um 14.20 am Bahnhof", 3),
        message("2", "Tom", "Hast du das Paket bekommen?", 5),
        message("3", "DHL", "Ihre Sendung wird heute zugestellt", 6, app = "DHL"),
        message("4", "Mama", "Denk an den Bahnhofs-Parkplatz, Gleis 3", 200),
        message("5", "Anna", "Bin am Bahnhof!", 900),
    )

    @Test
    fun findsTheMessageThatAnswersTheQuestion() {
        val found = MessageRetrieval.relevant("Wann muss ich zum Bahnhof?", messages, now)
        assertEquals(listOf("5", "4", "1"), found.map { it.key })
    }

    @Test
    fun senderNamesCount() {
        val found = MessageRetrieval.relevant("Was hat Tom geschrieben?", messages, now)
        assertEquals(listOf("2"), found.map { it.key })
    }

    @Test
    fun questionWordsAloneFindNothing() {
        assertTrue(MessageRetrieval.relevant("Wann muss ich?", messages, now).isEmpty())
        assertEquals(listOf("paket"), MessageRetrieval.terms("Wo ist mein Paket?"))
    }
}
