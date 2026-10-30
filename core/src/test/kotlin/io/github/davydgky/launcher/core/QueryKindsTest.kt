package io.github.davydgky.launcher.core

import org.junit.Assert.assertFalse
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
}
