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

    /** A question: "Wann muss ich zum Bahnhof?", "wo treffen wir uns", a sentence of several words. */
    fun isQuestion(input: String): Boolean {
        val words = TextNormalizer.tokens(input)
        return input.contains('?') || words.firstOrNull() in QUESTION_WORDS || words.size >= 4
    }

    /**
     * Something to talk about with the language model rather than to open: a question, an instruction
     * ("fasse meine Nachrichten zusammen", "erkläre Photosynthese", "übersetze …"), a sentence.
     */
    fun isConversational(input: String): Boolean {
        val words = TextNormalizer.tokens(input)
        if (words.isEmpty()) return false
        return isQuestion(input) || words.first() in INSTRUCTION_STARTS || wantsMessages(input) != null
    }

    /**
     * The input asks about recent messages as a whole ("fasse meine Nachrichten zusammen", "was gibt es
     * Neues auf WhatsApp", "summarize my messages"): which ones, by age and maybe app. Null otherwise.
     */
    fun wantsMessages(input: String): MessageScope? {
        val words = TextNormalizer.tokens(input)
        val aboutMessages = words.any { it in MESSAGE_WORDS }
        val overview = words.any { it in OVERVIEW_WORDS } || TextNormalizer.fold(input).let { t -> OVERVIEW_PHRASES.any { it in t } }
        if (!aboutMessages && !(overview && words.any { it in APP_WORDS })) return null
        if (!overview) return null
        val hours = when {
            words.any { it in WEEK_WORDS } -> 24 * 7
            words.any { it in YESTERDAY_WORDS } -> 48
            else -> 24
        }
        return MessageScope(hours, words.firstOrNull { it in APP_WORDS })
    }

    /** Messages of the last [hours] hours, only from an app whose name contains [app] if given. */
    data class MessageScope(val hours: Int, val app: String?)

    private val QUESTION_WORDS = setOf(
        "wann", "wo", "wohin", "woher", "wer", "was", "wie", "warum", "wieso", "welche", "welcher", "welches", "hat",
        "habe", "ist", "muss", "soll", "kann", "gibt", "when", "where", "who", "what", "how", "why", "which", "did",
        "does", "is", "do", "can", "should",
    )
    private val INSTRUCTION_STARTS = setOf(
        "fasse", "fass", "erklare", "erklar", "ubersetze", "ubersetz", "nenne", "nenn", "gib", "zeig", "zeige",
        "hilf", "vergleiche", "plane", "formuliere", "beschreibe", "liste", "empfiehl", "schlag", "zusammenfassung",
        "summarize", "summarise", "explain", "translate", "tell", "give", "list", "describe", "compare", "plan",
        "suggest", "recommend", "help",
    )
    private val MESSAGE_WORDS = setOf(
        "nachrichten", "nachricht", "chats", "chat", "benachrichtigungen", "mails", "emails", "sms", "messages",
        "notifications", "posteingang", "inbox",
    )
    private val APP_WORDS = setOf("whatsapp", "signal", "telegram", "instagram", "gmail", "outlook", "threema", "messenger", "slack", "teams", "discord")
    private val OVERVIEW_WORDS = setOf(
        "zusammen", "zusammenfassen", "zusammenfassung", "fasse", "fass", "uberblick", "neues", "neu", "verpasst",
        "wichtig", "wichtiges", "summarize", "summarise", "summary", "overview", "new", "missed", "important", "welche",
        "alle", "letzten", "heute", "kamen", "bekommen", "erhalten",
    )
    private val OVERVIEW_PHRASES = listOf("was gibt", "was ist los", "what's new", "whats new")
    private val WEEK_WORDS = setOf("woche", "week", "tage", "days")
    private val YESTERDAY_WORDS = setOf("gestern", "yesterday")

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
