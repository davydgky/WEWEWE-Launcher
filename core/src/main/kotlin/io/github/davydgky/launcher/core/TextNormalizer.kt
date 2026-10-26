package io.github.davydgky.launcher.core

import java.text.Normalizer

/**
 * Turns text into comparable search tokens: lower case, without diacritics, split at everything that is
 * neither a letter nor a digit. "Müller-Lüdenscheidt" becomes ["muller", "ludenscheidt"].
 */
object TextNormalizer {

    private val combiningMarks = Regex("\\p{Mn}+")

    /** Lower case without diacritics; ß becomes ss. Keeps all other characters. */
    fun fold(text: String): String {
        if (text.isEmpty()) return text
        val lower = text.lowercase()
        val ascii = lower.all { it.code < 128 }
        if (ascii) return lower
        val decomposed = Normalizer.normalize(lower.replace("ß", "ss"), Normalizer.Form.NFD)
        return combiningMarks.replace(decomposed, "")
    }

    /** Folded tokens of [text] in order of appearance. */
    fun tokens(text: String): List<String> {
        val folded = fold(text)
        val out = ArrayList<String>(4)
        var start = -1
        for (i in folded.indices) {
            if (folded[i].isLetterOrDigit()) {
                if (start < 0) start = i
            } else if (start >= 0) {
                out += folded.substring(start, i)
                start = -1
            }
        }
        if (start >= 0) out += folded.substring(start)
        return out
    }

    /**
     * Additional spellings under which a word should be found: German umlauts are often typed as
     * "ae", "oe" and "ue", so "Müller" is also indexed as "mueller".
     */
    fun alternateSpellings(word: String): List<String> {
        val lower = word.lowercase()
        if (lower.none { it == 'ä' || it == 'ö' || it == 'ü' }) return emptyList()
        val expanded = lower.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        return tokens(expanded)
    }

    /** First letters of the words in [text]: "Google Maps" -> "gm". Empty for single-word titles. */
    fun initials(text: String): String {
        val words = tokens(text)
        if (words.size < 2) return ""
        return buildString(words.size) { words.forEach { append(it[0]) } }
    }

    /** Only the digits of [text]; used for phone numbers. */
    fun digits(text: String): String = text.filter { it in '0'..'9' }
}
