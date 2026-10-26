package io.github.davydgky.launcher.core

/** Short excerpts of long text around the first word that matches a query. */
object Snippets {

    private val word = Regex("[\\p{L}\\p{N}]+")

    /**
     * About [length] characters of [text] around the first word starting with a word of [query],
     * with "…" where text was cut; null if no word matches.
     */
    fun around(text: String, query: String, length: Int = 90): String? {
        val terms = Query.parse(query).terms
        if (terms.isEmpty() || text.isEmpty()) return null
        val hit = word.findAll(text).firstOrNull { match ->
            val folded = TextNormalizer.fold(match.value)
            terms.any { folded.startsWith(it) }
        } ?: return null
        val start = (hit.range.first - length / 3).coerceAtLeast(0)
        val end = (start + length).coerceAtMost(text.length)
        // Start and end at word boundaries.
        val from = if (start == 0) 0 else text.indexOf(' ', start).let { if (it in start until hit.range.first) it + 1 else start }
        val to = if (end == text.length) end else text.lastIndexOf(' ', end).let { if (it > hit.range.last) it else end }
        val excerpt = text.substring(from, to).replace('\n', ' ').trim()
        return (if (from > 0) "…" else "") + excerpt + (if (to < text.length) "…" else "")
    }
}
