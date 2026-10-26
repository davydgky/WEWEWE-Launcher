package io.github.davydgky.launcher.core

/** A parsed search input. All words are prefixes: "goo ma" finds "Google Maps". */
class Query private constructor(
    val raw: String,
    /** The whole input folded, used for whole-title comparisons. */
    val folded: String,
    val terms: List<String>,
) {
    /** The words without separators, compared against initials ("gm" for "Google Maps"). */
    val compact: String = terms.joinToString("")

    val isEmpty: Boolean get() = terms.isEmpty()

    companion object {
        /** More words than this are ignored; nobody types eight words into a launcher. */
        const val MAX_TERMS = 8

        fun parse(raw: String): Query {
            val terms = TextNormalizer.tokens(raw).distinct().take(MAX_TERMS)
            return Query(raw, terms.joinToString(" "), terms)
        }
    }
}

/** A search hit inside one index, before kind weights and personal statistics are applied. */
class Match(val item: SearchItem, val score: Float)

/**
 * Immutable prefix index over a list of [SearchItem]s.
 *
 * Every distinct token is stored once in a sorted array; a query word finds all tokens it is a prefix of
 * by binary search, so the cost per keystroke grows with the number of hits, not with the number of
 * items. Short fields (title, subtitle, keywords) and long text (body) live in separate dictionaries,
 * so short queries never have to walk the large body vocabulary.
 */
class SearchIndex private constructor(
    val items: List<SearchItem>,
    private val short: PrefixMap,
    private val body: PrefixMap,
    private val foldedTitles: Array<String>,
    private val initials: Array<String>,
    /** Substring and initials matching need a scan over all titles; only done for small indexes. */
    private val scanTitles: Boolean,
) {
    val size: Int get() = items.size

    /** Adds every item matching all words of [query] to [out]. */
    fun match(query: Query, out: MutableList<Match>) = match(query) { item, score -> out += Match(item, score) }

    /** Calls [sink] for every item matching all words of [query], with its match score. */
    fun match(query: Query, sink: (SearchItem, Float) -> Unit) {
        val n = items.size
        if (n == 0 || query.isEmpty) return
        val terms = query.terms
        val all = (1 shl terms.size) - 1
        val matched = IntArray(n)
        val sum = FloatArray(n)
        val best = FloatArray(n)
        val touched = IntArray(n)

        for ((t, term) in terms.withIndex()) {
            val required = (1 shl t) - 1
            var touchedCount = 0
            short.forEachPrefixed(term) { token, posting ->
                val doc = posting ushr 3
                if (matched[doc] != required) return@forEachPrefixed
                val field = (posting ushr 1) and 3
                // A single letter only selects titles ("m" lists Maps, Messages, not every contact named Tim M.).
                if (term.length == 1 && field != FIELD_TITLE) return@forEachPrefixed
                var score = FIELD_WEIGHTS[field] * completeness(term, token)
                if (field == FIELD_TITLE && posting and 1 == 1) score += TITLE_START_BONUS
                if (best[doc] == 0f) touched[touchedCount++] = doc
                if (score > best[doc]) best[doc] = score
            }
            if (term.length >= MIN_BODY_TERM) {
                body.forEachPrefixed(term) { token, doc ->
                    if (matched[doc] != required) return@forEachPrefixed
                    val score = BODY_WEIGHT * completeness(term, token)
                    if (best[doc] == 0f) touched[touchedCount++] = doc
                    if (score > best[doc]) best[doc] = score
                }
            }
            for (i in 0 until touchedCount) {
                val doc = touched[i]
                sum[doc] += best[doc]
                matched[doc] = matched[doc] or (1 shl t)
                best[doc] = 0f
            }
        }

        for (doc in 0 until n) {
            var score = if (matched[doc] == all) sum[doc] else 0f
            val title = foldedTitles[doc]
            if (score > 0f) {
                if (title == query.folded) score += EXACT_TITLE_BONUS
                else if (title.startsWith(query.folded)) score += TITLE_PREFIX_BONUS
            } else if (scanTitles) {
                if (query.compact.length >= 2 && initials[doc].startsWith(query.compact)) score = INITIALS_SCORE
                else if (query.folded.length >= 3 && title.contains(query.folded)) score = INFIX_SCORE
            }
            if (score > 0f) sink(items[doc], score)
        }
    }

    companion object {
        internal const val FIELD_TITLE = 0
        internal const val FIELD_SUBTITLE = 1
        internal const val FIELD_KEYWORD = 2
        private val FIELD_WEIGHTS = floatArrayOf(1f, 0.55f, 0.5f)
        private const val BODY_WEIGHT = 0.3f
        private const val TITLE_START_BONUS = 0.3f
        private const val EXACT_TITLE_BONUS = 1.5f
        private const val TITLE_PREFIX_BONUS = 0.6f
        private const val INITIALS_SCORE = 0.7f
        private const val INFIX_SCORE = 0.45f
        private const val MIN_BODY_TERM = 3
        private const val MAX_BODY_CHARS = 20_000
        private const val MAX_TOKEN = 40
        private const val SCAN_LIMIT = 3_000

        val Empty = build(emptyList())

        /** A full word match scores 1; a prefix covering half of the word scores 0.725. */
        private fun completeness(term: String, token: String): Float =
            if (term.length == token.length) 1f else 0.55f + 0.35f * term.length / token.length

        fun build(items: List<SearchItem>, scanTitles: Boolean = items.size <= SCAN_LIMIT): SearchIndex {
            val short = PrefixMap.Builder()
            val body = PrefixMap.Builder()
            val titles = Array(items.size) { "" }
            val initials = Array(items.size) { "" }
            for ((doc, item) in items.withIndex()) {
                titles[doc] = TextNormalizer.tokens(item.title).joinToString(" ")
                initials[doc] = TextNormalizer.initials(item.title)
                addShort(short, doc, FIELD_TITLE, item.title)
                addShort(short, doc, FIELD_SUBTITLE, item.subtitle)
                item.keywords.forEach { addShort(short, doc, FIELD_KEYWORD, it) }
                if (item.body.isNotEmpty()) {
                    val text = if (item.body.length > MAX_BODY_CHARS) item.body.substring(0, MAX_BODY_CHARS) else item.body
                    for (token in TextNormalizer.tokens(text)) {
                        if (token.length >= 2) body.addOnce(token.take(MAX_TOKEN), doc)
                    }
                }
            }
            return SearchIndex(items, short.build(), body.build(), titles, initials, scanTitles)
        }

        private fun addShort(builder: PrefixMap.Builder, doc: Int, field: Int, text: String) {
            if (text.isEmpty()) return
            val tokens = TextNormalizer.tokens(text)
            for ((i, token) in tokens.withIndex()) {
                val first = if (i == 0 && field == FIELD_TITLE) 1 else 0
                val posting = (doc shl 3) or (field shl 1) or first
                builder.add(token.take(MAX_TOKEN), posting)
                // Long digit runs are phone or customer numbers: make every tail findable as well.
                if (token.length >= 7 && token.all { it in '0'..'9' }) {
                    for (start in 1..token.length - 4) builder.add(token.substring(start), posting and 1.inv())
                }
            }
            for (word in text.split(' ')) {
                for (alt in TextNormalizer.alternateSpellings(word)) builder.add(alt, (doc shl 3) or (field shl 1))
            }
        }
    }
}

/** Sorted token dictionary with posting lists. */
internal class PrefixMap private constructor(
    internal val tokens: Array<String>,
    internal val postings: Array<IntArray>,
) {
    inline fun forEachPrefixed(prefix: String, action: (token: String, posting: Int) -> Unit) {
        var i = lowerBound(prefix)
        while (i < tokens.size && tokens[i].startsWith(prefix)) {
            val token = tokens[i]
            for (posting in postings[i]) action(token, posting)
            i++
        }
    }

    fun lowerBound(key: String): Int {
        var lo = 0
        var hi = tokens.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tokens[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    class Builder {
        private val map = HashMap<String, IntList>()

        fun add(token: String, posting: Int) {
            map.getOrPut(token) { IntList() }.add(posting)
        }

        /** Adds [posting] unless it is already the last one of [token] (body text repeats words). */
        fun addOnce(token: String, posting: Int) {
            val list = map.getOrPut(token) { IntList() }
            if (list.last() != posting) list.add(posting)
        }

        fun build(): PrefixMap {
            val keys = map.keys.toTypedArray()
            keys.sort()
            return PrefixMap(keys, Array(keys.size) { map.getValue(keys[it]).toArray() })
        }
    }

    private class IntList {
        private var data = IntArray(2)
        private var size = 0

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun last(): Int = if (size == 0) -1 else data[size - 1]

        fun toArray(): IntArray = data.copyOf(size)
    }
}
