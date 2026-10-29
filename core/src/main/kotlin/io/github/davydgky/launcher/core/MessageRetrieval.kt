package io.github.davydgky.launcher.core

/** A message received or sent, from a notification or the SMS store. [time] in epoch milliseconds. */
data class MessageText(
    val key: String,
    val app: String,
    val sender: String,
    val conversation: String,
    val text: String,
    val time: Long,
)

/**
 * Finds the messages that can answer a question ("Wann muss ich zum Bahnhof?" → "wir treffen uns um
 * 14.20 am Bahnhof"), to hand them to the language model with the question. Plain word matching on
 * the meaningful words of the question (without "wann", "muss", "ich" …), with word stems ("bahnhof"
 * finds "Bahnhofs") and a preference for recent messages.
 */
object MessageRetrieval {

    fun relevant(question: String, messages: List<MessageText>, now: Long, limit: Int = 8): List<MessageText> {
        val terms = terms(question)
        if (terms.isEmpty()) return emptyList()
        return messages
            .map { message -> message to score(terms, message, now) }
            .filter { (_, score) -> score > 0f }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { it.first }
            .sortedBy { it.time }
    }

    /** The words of the question that carry meaning, lower case. */
    fun terms(question: String): List<String> = TextNormalizer.tokens(question)
        .filter { it.length >= MIN_TERM && it !in STOPWORDS && it.any(Char::isLetter) }
        .distinct()

    private fun score(terms: List<String>, message: MessageText, now: Long): Float {
        val words = TextNormalizer.tokens("${message.sender} ${message.conversation} ${message.text}")
        val hits = terms.count { term -> words.any { word -> matches(term, word) } }
        if (hits == 0) return 0f
        val days = ((now - message.time).coerceAtLeast(0L) / DAY_MS).toFloat()
        // Every matching word counts most; among equals the newer message wins.
        return hits + 1f / (1f + days / 3f)
    }

    /** Same word, or one is the other plus an ending: "bahnhof"/"bahnhofs", "treffen"/"treffpunkt" not. */
    private fun matches(term: String, word: String): Boolean = when {
        word == term -> true
        word.startsWith(term) -> word.length - term.length <= MAX_ENDING
        term.startsWith(word) && word.length >= MIN_STEM -> term.length - word.length <= MAX_ENDING
        else -> false
    }

    private const val MIN_TERM = 3
    private const val MIN_STEM = 4
    private const val MAX_ENDING = 3
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val STOPWORDS = setOf(
        // German question and filler words
        "wann", "wie", "was", "wer", "wem", "wen", "wo", "wohin", "woher", "warum", "wieso", "weshalb", "welche", "welcher",
        "welches", "muss", "musst", "müssen", "soll", "sollte", "kann", "können", "darf", "habe", "hast", "hat", "haben",
        "bin", "bist", "ist", "sind", "war", "waren", "wird", "werde", "werden", "ich", "mich", "mir", "mein", "meine",
        "meinen", "meinem", "du", "dich", "dir", "wir", "uns", "ihr", "sie", "der", "die", "das", "den", "dem", "des",
        "ein", "eine", "einen", "einem", "und", "oder", "aber", "zum", "zur", "zu", "nach", "von", "vom", "mit", "bei",
        "beim", "auf", "aus", "für", "über", "noch", "schon", "heute", "morgen", "gestern", "nochmal", "eigentlich",
        "genau", "gesagt", "geschrieben", "schrieb", "sagte", "nachricht", "nachrichten", "uhrzeit", "denn", "dass",
        // English
        "when", "what", "who", "where", "why", "how", "which", "must", "should", "can", "could", "have", "has",
        "the", "and", "for", "with", "from", "about", "did", "does", "was", "were", "are", "you", "your", "our",
        "message", "messages", "said", "wrote", "today", "tomorrow", "yesterday",
    ).mapTo(HashSet(), TextNormalizer::fold)
}
