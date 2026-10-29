package io.github.davydgky.launcher.core

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartActionsTest {

    @Test
    fun commands() {
        assertEquals(SmartAction.Call("Mama"), SmartActions.parse("""{"action":"call","who":"Mama"}"""))
        assertEquals(
            SmartAction.Message("Anna", "Ich komme später"),
            SmartActions.parse("""{"action": "sms", "who": "Anna", "text": "Ich komme später"}"""),
        )
        assertEquals(SmartAction.Navigate("Zuhause"), SmartActions.parse("""Sure: {"action":"navigate","place":"Zuhause"}"""))
        assertEquals(
            SmartAction.Event("Zahnarzt", LocalDateTime.of(2026, 10, 30, 15, 0), null, ""),
            SmartActions.parse("""{"action":"event","title":"Zahnarzt","start":"2026-10-30T15:00","end":null}"""),
        )
        assertEquals(
            SmartAction.Event("Geburtstag", LocalDateTime.of(2026, 11, 2, 9, 0), null, "Bei Oma"),
            SmartActions.parse("""{"action":"event","title":"Geburtstag","start":"2026-11-02","place":"Bei Oma"}"""),
        )
        assertEquals(SmartAction.Mail("tom@example.com", "Rechnung", "Hallo\nTom"),
            SmartActions.parse("""{"action":"mail","who":"tom@example.com","title":"Rechnung","text":"Hallo\nTom"}"""))
    }

    @Test
    fun notCommands() {
        assertNull(SmartActions.parse("Der Mount Everest ist 8849 m hoch."))
        assertNull(SmartActions.parse("-"))
        assertNull(SmartActions.parse("""{"action":"delete_everything"}"""))
        assertNull(SmartActions.parse("""{"action":"call"}"""))
        assertNull(SmartActions.parse("""{"action":"event","title":"x","start":"morgen"}"""))
        assertNull(SmartActions.parse("""{"action":"call","who":"""))
    }
}
