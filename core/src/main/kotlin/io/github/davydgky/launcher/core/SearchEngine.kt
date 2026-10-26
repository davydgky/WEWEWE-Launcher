package io.github.davydgky.launcher.core

import java.util.EnumMap
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicReference

class ScoredItem(val item: SearchItem, val score: Float)

class ResultGroup(val kind: SourceKind, val items: List<ScoredItem>)

class SearchResults(
    val query: String,
    /** The single best result, shown above all groups; also removed from its group. */
    val top: ScoredItem?,
    /** Groups ordered by their best result. */
    val groups: List<ResultGroup>,
) {
    val isEmpty: Boolean get() = top == null && groups.isEmpty()

    companion object {
        val Empty = SearchResults("", null, emptyList())
    }
}

/**
 * Combines the indexes of all sources. Each source publishes a complete, immutable index; publishing
 * replaces the map atomically, so searches never lock and never see a half-built source.
 */
class SearchEngine {

    private val indexes = AtomicReference<Map<String, SearchIndex>>(emptyMap())

    fun publish(sourceId: String, index: SearchIndex) {
        indexes.updateAndGet { it + (sourceId to index) }
    }

    fun remove(sourceId: String) {
        indexes.updateAndGet { it - sourceId }
    }

    fun index(sourceId: String): SearchIndex? = indexes.get()[sourceId]

    val itemCount: Int get() = indexes.get().values.sumOf { it.size }

    /**
     * Searches all sources. [engagement] returns 0..1 for how much the user used an item recently; it is
     * only consulted for items that match.
     */
    fun search(raw: String, maxPerGroup: Int = MAX_PER_GROUP, engagement: (String) -> Float = { 0f }): SearchResults {
        val query = Query.parse(raw)
        if (query.isEmpty) return SearchResults.Empty
        // One more than shown per group, because the top hit is taken out of its group.
        val collector = TopCollector(maxPerGroup + 1)
        for (index in indexes.get().values) {
            index.match(query) { item, match ->
                val score = match * item.kind.weight + item.boost * BOOST_WEIGHT + engagement(item.key) * ENGAGEMENT_WEIGHT
                collector.offer(item, score)
            }
        }
        val ranked = collector.sortedGroups()
        if (ranked.isEmpty()) return SearchResults(raw, null, emptyList())

        val top = ranked.minWith(compareBy(ResultOrder) { it.first() }).first().takeIf { it.score >= TOP_HIT_MIN }
        val groups = ranked
            .map { list -> list.filter { it !== top }.take(maxPerGroup) }
            .filter { it.isNotEmpty() }
            .sortedWith(compareBy(ResultOrder) { it.first() })
            .map { ResultGroup(it.first().item.kind, it) }
        return SearchResults(raw, top, groups)
    }

    /** Keeps the best [limit] results per kind without sorting everything that matched. */
    private class TopCollector(private val limit: Int) {
        // Heads are the weakest kept result of each kind.
        private val heaps = EnumMap<SourceKind, PriorityQueue<ScoredItem>>(SourceKind::class.java)

        fun offer(item: SearchItem, score: Float) {
            val heap = heaps.getOrPut(item.kind) { PriorityQueue(limit + 1, ResultOrder.reversed()) }
            if (heap.size >= limit) {
                val weakest = heap.peek()
                if (score < weakest.score) return
                val candidate = ScoredItem(item, score)
                if (ResultOrder.compare(candidate, weakest) >= 0) return
                heap.poll()
                heap.add(candidate)
            } else {
                heap.add(ScoredItem(item, score))
            }
        }

        fun sortedGroups(): List<List<ScoredItem>> = heaps.values.map { it.sortedWith(ResultOrder) }
    }

    companion object {
        const val MAX_PER_GROUP = 25
        private const val BOOST_WEIGHT = 0.35f
        private const val ENGAGEMENT_WEIGHT = 0.9f
        /** Roughly "a word of the title starts with the input"; weaker hits never become the top hit. */
        private const val TOP_HIT_MIN = 0.8f

        /** Best first; ties go to the shorter, then alphabetically first title. */
        private val ResultOrder: Comparator<ScoredItem> = compareByDescending<ScoredItem> { it.score }
            .thenBy { it.item.title.length }
            .thenBy { it.item.title }
            .thenBy { it.item.key }
    }
}
