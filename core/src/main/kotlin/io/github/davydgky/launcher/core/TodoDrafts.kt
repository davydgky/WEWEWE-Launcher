package io.github.davydgky.launcher.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A task Gemini Nano found in a message, before the user takes it or not. [message] is its index in the input. */
data class TodoDraft(val message: Int, val title: String, val date: LocalDate?, val time: LocalTime?)

/**
 * To-do suggestions from messages ("Kannst du morgen die Pakete abholen?" → "Pakete abholen", tomorrow):
 * which messages are worth asking Gemini Nano about, the prompt, and reading its answer. The answer is
 * one line per task, `<message number> | <task> | <YYYY-MM-DD or -> | <HH:MM or ->`, or `KEINE`.
 */
object TodoDrafts {

    /** Messages per request: enough context for a conversation, short enough for a small model. */
    const val BATCH = 6

    private const val MAX_TITLE = 80
    private const val MAX_MESSAGE_CHARS = 280

    /** Greetings, "ok", emoji and links alone hold no task: they are not worth Gemini Nano's quota. */
    fun worthAsking(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters < 12) return false
        val words = text.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }
        return words >= 3
    }

    fun instruction(today: LocalDate): String = """
        Du hilfst dem Besitzer eines Handys, nichts zu vergessen. Heute ist ${today.format(DAY)}.
        Unten stehen nummerierte Nachrichten, die er bekommen oder geschrieben hat ("Ich" ist er selbst).
        Finde nur echte Aufgaben für ihn: Bitten an ihn, Zusagen von ihm, Termine, Fristen, Zahlungen,
        Abholungen, Rückrufe. Keine Werbung, keine Newsletter, keine Statusmeldungen, nichts was schon erledigt ist.
        Antworte pro Aufgabe mit genau einer Zeile:
        <Nummer der Nachricht> | <Aufgabe, kurz, ab 2 bis 8 Wörter, ohne Namen des Absenders am Anfang> | <Datum YYYY-MM-DD oder -> | <Uhrzeit HH:MM oder ->
        Rechne Wörter wie "morgen" oder "Freitag" vom Datum der Nachricht aus. Ohne Aufgabe antworte nur: KEINE

        Beispiel:
        1. [Mi 11.11.2026 18:02] WhatsApp · Anna: Kannst du morgen um 10 die Pakete bei der Post abholen?
        2. [Mi 11.11.2026 18:05] WhatsApp · Anna: Danke dir!
        Antwort:
        1 | Pakete bei der Post abholen | 2026-11-12 | 10:00
    """.trimIndent()

    /** The messages, numbered from 1, oldest first. */
    fun input(messages: List<MessageText>, zone: ZoneId = ZoneId.systemDefault()): String =
        messages.withIndex().joinToString("\n") { (i, m) ->
            val time = Instant.ofEpochMilli(m.time).atZone(zone).format(STAMP)
            val text = m.text.replace('\n', ' ').take(MAX_MESSAGE_CHARS)
            "${i + 1}. [$time] ${m.app} · ${m.sender}: $text"
        }

    /** The tasks in Gemini Nano's [reply] about [count] messages; anything not in the expected form is left out. */
    fun parse(reply: String, count: Int): List<TodoDraft> = reply.lineSequence()
        .map { it.trim().trimStart('-', '*', '•', ' ') }
        .filter { '|' in it }
        .mapNotNull { line ->
            val parts = line.split('|').map { it.trim() }
            val index = parts[0].trimEnd('.', ':', ')').toIntOrNull()?.minus(1) ?: return@mapNotNull null
            if (index !in 0 until count) return@mapNotNull null
            val title = parts.getOrNull(1)?.trim('"', '„', '“', '.', ' ')?.takeIf { it.length in 3..MAX_TITLE } ?: return@mapNotNull null
            if (title.equals("KEINE", ignoreCase = true)) return@mapNotNull null
            val date = parts.getOrNull(2)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val time = if (date == null) null else parts.getOrNull(3)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            TodoDraft(index, title.replaceFirstChar { it.uppercase() }, date, time)
        }
        .distinctBy { normalized(it.title) }
        .toList()

    /** For telling a suggestion apart from one the user already has: case, punctuation and spacing aside. */
    fun normalized(title: String): String = title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val DAY = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)
    private val STAMP = DateTimeFormatter.ofPattern("EEE dd.MM.yyyy HH:mm", Locale.GERMAN)
}
