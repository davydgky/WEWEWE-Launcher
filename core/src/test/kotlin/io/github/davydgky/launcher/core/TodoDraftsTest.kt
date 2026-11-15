package io.github.davydgky.launcher.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoDraftsTest {

    @Test
    fun readsOneTaskPerLine() {
        val reply = """
            1 | Pakete bei der Post abholen | 2026-11-12 | 10:00
            3 | Miete überweisen | 2026-11-30 | -
        """.trimIndent()
        val drafts = TodoDrafts.parse(reply, count = 3)
        assertEquals(2, drafts.size)
        assertEquals(TodoDraft(0, "Pakete bei der Post abholen", LocalDate.of(2026, 11, 12), LocalTime.of(10, 0)), drafts[0])
        assertEquals(TodoDraft(2, "Miete überweisen", LocalDate.of(2026, 11, 30), null), drafts[1])
    }

    @Test
    fun noTaskMeansNothing() {
        assertTrue(TodoDrafts.parse("KEINE", count = 4).isEmpty())
        assertTrue(TodoDrafts.parse("Es gibt keine Aufgaben.", count = 4).isEmpty())
    }

    @Test
    fun whatIsNotInTheExpectedFormIsLeftOut() {
        val reply = """
            - 2. | einkaufen gehen | morgen | später
            9 | Gibt es nicht | - | -
            x | Kein Index | - | -
            1 | ab | - | -
        """.trimIndent()
        val drafts = TodoDrafts.parse(reply, count = 3)
        assertEquals(1, drafts.size)
        // Capitalised; a date it cannot read is no date, and then no time either.
        assertEquals("Einkaufen gehen", drafts.single().title)
        assertNull(drafts.single().date)
        assertNull(drafts.single().time)
    }

    @Test
    fun theSameTaskTwiceIsOneSuggestion() {
        val reply = "1 | Mama zurückrufen | - | -\n2 | mama zurückrufen. | - | -"
        assertEquals(1, TodoDrafts.parse(reply, count = 2).size)
    }

    @Test
    fun shortRepliesAreNotWorthAsking() {
        assertFalse(TodoDrafts.worthAsking("ok 👍"))
        assertFalse(TodoDrafts.worthAsking("Danke dir!"))
        assertFalse(TodoDrafts.worthAsking("https://example.com/x"))
        assertTrue(TodoDrafts.worthAsking("Kannst du morgen die Pakete abholen?"))
    }

    @Test
    fun chatterCodesAndAdvertisingAreNotWorthAsking() {
        assertFalse(TodoDrafts.worthAsking("Hahaha das war echt ein lustiger Abend gestern"))
        assertFalse(TodoDrafts.worthAsking("Guten Morgen, wie geht es dir heute so?"))
        assertFalse(TodoDrafts.worthAsking("Wo bist du gerade unterwegs?"))
        assertFalse(TodoDrafts.worthAsking("Dein Bestätigungscode lautet 482913. Bitte nicht weitergeben."))
        assertFalse(TodoDrafts.worthAsking("Nur heute 20 % Rabatt auf alles, jetzt bestellen!"))
    }

    @Test
    fun asksPromisesAndAppointmentsAreWorthAsking() {
        assertTrue(TodoDrafts.worthAsking("Ruf bitte Oma an, sie hat Geburtstag"))
        assertTrue(TodoDrafts.worthAsking("Ich überweise dir das Geld heute Abend"))
        assertTrue(TodoDrafts.worthAsking("Der Zahnarzt hat noch was frei am Freitag"))
        assertTrue(TodoDrafts.worthAsking("Wir sehen uns um 18:30 vor dem Kino"))
        assertTrue(TodoDrafts.worthAsking("Übermorgen ist die Abgabe der Hausarbeit"))
        assertTrue(TodoDrafts.worthAsking("Can you pick up the kids at five?"))
    }

    @Test
    fun oneTaskPerMessage() {
        val reply = "1 | Pakete abholen | - | -\n1 | Zur Post gehen | - | -\n2 | Mama zurückrufen | - | -"
        val drafts = TodoDrafts.parse(reply, count = 2)
        assertEquals(listOf("Pakete abholen", "Mama zurückrufen"), drafts.map { it.title })
    }

    @Test
    fun theInputIsNumberedFromOne() {
        val messages = listOf(
            MessageText("a", "WhatsApp", "Anna", "Anna", "Kannst du\ndie Pakete abholen?", 1_762_884_120_000L),
            MessageText("b", "SMS", "Ich", "Tom", "Mach ich", 1_762_884_180_000L),
        )
        val lines = TodoDrafts.input(messages, ZoneOffset.UTC).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("1. ["))
        assertTrue(lines[0], lines[0].endsWith("WhatsApp · Anna: Kannst du die Pakete abholen?"))
        assertTrue(lines[1], lines[1].startsWith("2. ["))
    }
}
