package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryKindsTest {

    @Test
    fun commands() {
        listOf(
            "ruf Mama an", "Mama anrufen", "schreib Anna, dass ich später komme", "Nachricht an Tom: bin da",
            "navigiere nach Hause", "Route zum Bahnhof", "Termin morgen 15 Uhr Zahnarzt", "erinnere mich an Müll",
            "call Tom", "remind me to buy milk", "Zahnarzt am Freitag in den Kalender",
        ).forEach { assertTrue(it, QueryKinds.isCommand(it)) }
    }

    @Test
    fun questionsAndTerms() {
        listOf(
            "Wann muss ich zum Bahnhof?", "höhe eiffelturm", "was ist ein kWh", "Spotify", "Rezept Pfannkuchen",
            "wie spät ist es in Tokio", "Routenplaner", "Terminator Schauspieler",
        ).forEach { assertFalse(it, QueryKinds.isCommand(it)) }
    }

    @Test
    fun exactNames() {
        assertTrue(QueryKinds.isExactName(" spotify", "Spotify"))
        assertTrue(QueryKinds.isExactName("anna muller", "Anna Müller"))
        assertFalse(QueryKinds.isExactName("spot", "Spotify"))
    }

    @Test
    fun conversations() {
        listOf("fasse meine Nachrichten zusammen", "erkläre Photosynthese", "Wann muss ich zum Bahnhof?", "übersetze guten Morgen ins Englische")
            .forEach { assertTrue(it, QueryKinds.isConversational(it)) }
        listOf("Spotify", "anna", "pfannkuchen rezept").forEach { assertFalse(it, QueryKinds.isConversational(it)) }
    }

    @Test
    fun messageOverviews() {
        assertEquals(QueryKinds.MessageScope(24, null), QueryKinds.wantsMessages("Fasse meine Nachrichten zusammen"))
        assertEquals(QueryKinds.MessageScope(24, "whatsapp"), QueryKinds.wantsMessages("was gibt es Neues auf WhatsApp"))
        assertEquals(QueryKinds.MessageScope(168, null), QueryKinds.wantsMessages("Zusammenfassung meiner Chats der letzten Woche"))
        assertEquals(QueryKinds.MessageScope(48, null), QueryKinds.wantsMessages("welche Nachrichten kamen gestern"))
        assertEquals(QueryKinds.MessageScope(24, null), QueryKinds.wantsMessages("summarize my messages"))
        assertNull(QueryKinds.wantsMessages("Wann muss ich zum Bahnhof?"))
        assertNull(QueryKinds.wantsMessages("Nachricht an Anna"))
        assertNull(QueryKinds.wantsMessages("WhatsApp"))
    }
}
