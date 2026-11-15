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

    /**
     * Only messages that look like they ask something of the user, hold a promise, an appointment, a
     * deadline or a payment are worth asking Gemini Nano about: greetings, "ok", emoji, links, chatter,
     * codes and advertising are not, and would only bring suggestions nobody wants.
     */
    fun worthAsking(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters < 12) return false
        val words = text.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }
        if (words < 3) return false
        val lower = text.lowercase(Locale.GERMAN)
        if (NOT_A_TASK.containsMatchIn(lower)) return false
        return TASK_SIGNAL.containsMatchIn(lower) || WHEN.containsMatchIn(lower)
    }

    /** Word edges that also know umlauts: Java's `\b` does not count "ü" as part of a word. */
    private const val START = "(?<![\\p{L}\\d])"
    private const val END = "(?![\\p{L}\\d])"

    /** Asks, promises, errands, appointments, deadlines and payments, in German and English; word starts. */
    private val TASK_SIGNAL = Regex(
        listOf(
            // Asked of the user
            "kannst du", "könntest du", "kannste", "würdest du", "magst du", "bitte", "denk dran", "denk daran",
            "vergiss nicht", "nicht vergessen", "erinner", "sag bescheid", "gib bescheid", "meld dich", "melde dich",
            "ruf mich", "ruf .{1,30} an$END", "zurückrufen", "rückruf", "musst du", "du musst", "sollst",
            // Promised by the user
            "mach ich", "ich mach das", "ich kümmer", "ich bring", "ich hol", "ich schick", "ich ruf", "ich zahl",
            "ich überweis", "ich meld", "ich komm", "ich muss",
            // Errands
            "abholen", "mitbringen", "besorgen", "einkaufen", "kaufen", "bezahlen", "zahlen", "überweis",
            "zurückzahlen", "unterschreib", "abgeben", "einreichen", "buchen", "reservier", "kündig", "anmelden",
            "absagen", "zusagen", "vorbeibringen", "vorbeikommen", "zurückgeben", "erledigen",
            // Appointments and deadlines
            "termin", "treffen wir", "treffen uns", "verabredet", "frist", "deadline", "fällig", "spätestens",
            "rechnung", "mahnung", "abholbereit", "abholung",
            // English
            "can you", "could you", "would you", "please", "pls", "don't forget", "dont forget", "remember to",
            "remind", "need you to", "you need to", "you have to", "call me", "call back", "pick up", "bring",
            "buy", "pay", "appointment", "meeting", "due$END", "i'll", "i will",
        ).joinToString("|", prefix = "$START(?:", postfix = ")"),
    )

    /** A day or time: "morgen um 10" is likely an appointment even without one of the words above. */
    private val WHEN = Regex(
        "$START(?:(?<!guten )morgen|übermorgen|montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|" +
            "wochenende|nächste woche|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|next week)$END|" +
            "$START\\d{1,2}(?::\\d{2})? ?uhr$END|$START\\d{1,2}:\\d{2}$END|$START\\d{1,2}\\.\\d{1,2}\\.",
    )

    /** Codes, advertising and newsletters: never a task, however they are worded. */
    private val NOT_A_TASK = Regex(
        listOf(
            "bestätigungscode", "verifizierungscode", "sicherheitscode", "einmalcode", "dein code", "ihr code",
            "verification code", "security code", "one-time", "otp", "tan", "rabatt", "% off", "newsletter",
            "abbestellen", "jetzt kaufen", "jetzt bestellen", "jetzt sichern", "gewinnspiel", "unsubscribe",
        ).joinToString("|", prefix = "$START(?:", postfix = ")$END"),
    )

    fun instruction(today: LocalDate): String = """
        Du hilfst dem Besitzer eines Handys, nichts zu vergessen. Heute ist ${today.format(DAY)}.
        Unten stehen nummerierte Nachrichten, die er bekommen oder geschrieben hat ("Ich" ist er selbst).
        Finde nur echte Aufgaben für ihn: Bitten an ihn, Zusagen von ihm, Termine, Fristen, Zahlungen,
        Abholungen, Rückrufe. Nur wenn eindeutig ist, dass er selbst etwas tun oder irgendwo sein muss.
        Keine Werbung, keine Newsletter, keine Statusmeldungen, keine Codes, kein Plaudern, keine Fragen,
        die nur eine Antwort wollen, nichts für andere Leute, nichts was schon erledigt oder abgesagt ist.
        Im Zweifel keine Aufgabe. Höchstens eine Aufgabe pro Nachricht; die meisten Nachrichten haben keine.
        Antworte pro Aufgabe mit genau einer Zeile:
        <Nummer der Nachricht> | <Aufgabe, kurz, ab 2 bis 8 Wörter, ohne Namen des Absenders am Anfang> | <Datum YYYY-MM-DD oder -> | <Uhrzeit HH:MM oder ->
        Rechne Wörter wie "morgen" oder "Freitag" vom Datum der Nachricht aus. Ohne Aufgabe antworte nur: KEINE

        Beispiel:
        1. [Mi 11.11.2026 18:02] WhatsApp · Anna: Kannst du morgen um 10 die Pakete bei der Post abholen?
        2. [Mi 11.11.2026 18:05] WhatsApp · Anna: Danke dir!
        3. [Mi 11.11.2026 18:20] WhatsApp · Tom: Wie war dein Tag? Ich muss morgen früh raus.
        Antwort:
        1 | Pakete bei der Post abholen | 2026-11-12 | 10:00

        Beispiel:
        1. [Do 12.11.2026 09:14] SMS · Shop: Nur heute 20 % Rabatt auf alles, jetzt bestellen!
        2. [Do 12.11.2026 09:30] Signal · Lisa: Kommst du morgen auch zum Essen?
        Antwort:
        KEINE
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
        // One task per message: a second one from the same message is mostly the first, worded differently.
        .distinctBy { it.message }
        .distinctBy { normalized(it.title) }
        .toList()

    /** For telling a suggestion apart from one the user already has: case, punctuation and spacing aside. */
    fun normalized(title: String): String = title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val DAY = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)
    private val STAMP = DateTimeFormatter.ofPattern("EEE dd.MM.yyyy HH:mm", Locale.GERMAN)
}
