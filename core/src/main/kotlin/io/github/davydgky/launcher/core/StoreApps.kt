package io.github.davydgky.launcher.core

/** An app from the Play Store that is not installed. [iconUrl] and [rating] may be unknown. */
data class StoreApp(
    val packageName: String,
    val title: String,
    val developer: String = "",
    val rating: String = "",
    val iconUrl: String? = null,
    /** Suggested by Gemini Nano from what it knows, not found in the store itself. */
    val suggested: Boolean = false,
)

/**
 * Reads apps out of the Play Store's search page. The page has no stable markup, so this relies only on
 * what does not change: every result links to "details?id=<package>", and the name, developer, rating and
 * icon follow that link before the next result.
 */
object StoreApps {

    private val link = Regex("""details\?id=([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)""")
    private val icon = Regex("""https://play-lh\.googleusercontent\.com/[A-Za-z0-9_\-./=]+""")
    private val rating = Regex("""^[1-5][.,]\d$""")
    private val tag = Regex("<[^>]*>")
    private val script = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val packageName = Regex("""^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$""")

    fun parse(html: String, limit: Int = 5): List<StoreApp> {
        val page = script.replace(html, " ")
        val links = link.findAll(page).toList()
        val out = LinkedHashMap<String, StoreApp>()
        links.forEachIndexed { index, match ->
            val pkg = match.groupValues[1]
            if (pkg in out) return@forEachIndexed
            // The card: from this link to the next link of another app.
            val end = links.drop(index + 1).firstOrNull { it.groupValues[1] != pkg }?.range?.first ?: minOf(page.length, match.range.last + CARD_CHARS)
            val card = page.substring(match.range.last + 1, end)
            // Text between tags; a tag cut off at either end of the card is markup, not text.
            val texts = card.split(tag)
                .filter { '<' !in it && '>' !in it }
                .map { decode(it).trim() }
                .filter { it.isNotEmpty() && it.length < 80 }
            val title = texts.firstOrNull { !rating.matches(it) && it.any(Char::isLetter) } ?: return@forEachIndexed
            val developer = texts.drop(texts.indexOf(title) + 1).firstOrNull { !rating.matches(it) && it.any(Char::isLetter) }.orEmpty()
            out[pkg] = StoreApp(
                packageName = pkg,
                title = title,
                developer = developer,
                rating = texts.firstOrNull { rating.matches(it) }?.replace('.', ',').orEmpty(),
                iconUrl = icon.find(card)?.value?.let(::sized),
            )
            if (out.size >= limit) return out.values.toList()
        }
        return out.values.toList()
    }

    /**
     * Gemini Nano's suggestions, one per line as "Name | package". Lines without a valid package name are
     * dropped: the download button needs it.
     */
    fun parseSuggestions(reply: String, limit: Int = 3): List<StoreApp> = reply.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().trimStart('-', '*', ' ').split('|').map { it.trim() }
            if (parts.size < 2) return@mapNotNull null
            val pkg = parts[1].trim('`', '"', '.').lowercase()
            if (!packageName.matches(pkg) || parts[0].isBlank()) null else StoreApp(pkg, parts[0], suggested = true)
        }
        .distinctBy { it.packageName }
        .take(limit)
        .toList()

    /** Play icons take their size from the URL; 96 px is sharp in a list. */
    private fun sized(url: String): String = url.substringBefore('=') + "=s96"

    private fun decode(text: String): String = text
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private const val CARD_CHARS = 4000
}
