package io.github.davydgky.launcher.core

/**
 * What an input to the search asks for, decided without the language model so that the model gets one
 * clear task: a request to the phone ("ruf Mama an") becomes a command, everything else is answered.
 */
object QueryKinds {

    /** "ruf mama an", "schreib anna dass …", "navigiere nach hause", "termin morgen 15 uhr", "call tom". */
    fun isCommand(input: String): Boolean {
        val text = TextNormalizer.fold(input.trim())
        if (text.isEmpty()) return false
        val first = text.substringBefore(' ')
        return first in COMMAND_STARTS || COMMAND_PHRASES.any { text.startsWith(it) } || COMMAND_ENDINGS.any { text.endsWith(it) }
    }

    /** The input names one thing the search found as it is ("spotify", "anna müller"): no answer needed. */
    fun isExactName(input: String, title: String): Boolean =
        TextNormalizer.fold(input.trim()) == TextNormalizer.fold(title.trim())

    private val COMMAND_STARTS = setOf(
        "ruf", "rufe", "anrufen", "schreib", "schreibe", "sende", "send", "schick", "schicke", "navigier", "navigiere",
        "navigation", "route", "termin", "erinnere", "erinner", "trag", "notier", "call", "text", "message", "email",
        "mail", "navigate", "remind", "schedule", "googel", "google",
    )
    private val COMMAND_PHRASES = listOf(
        "nachricht an ", "sms an ", "e-mail an ", "mail an ", "fahr mich ", "bring mich ", "weg nach ", "weg zu ",
        "directions to ", "add event", "new event", "neuer termin", "such im web", "suche im web", "im web suchen",
    )
    private val COMMAND_ENDINGS = listOf(" anrufen", " eintragen", " in den kalender")
}
