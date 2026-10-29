package io.github.davydgky.launcher.core

/**
 * Where a result comes from. The order is the display order of groups with equal scores; [weight] is
 * how strongly a match of this kind counts compared to the others (apps beat old browser history).
 * [fuzzy] kinds are also found with a typo in the title ("spotfy" finds Spotify).
 */
enum class SourceKind(val weight: Float, val groupLimit: Int, val fuzzy: Boolean = false) {
    App(1.35f, 8, fuzzy = true),
    Setting(1.2f, 4, fuzzy = true),
    Shortcut(1.15f, 4, fuzzy = true),
    Contact(1.25f, 4, fuzzy = true),
    Message(1.1f, 4),
    Event(1.1f, 4),
    VeloBookmark(1.15f, 4),
    Note(1.1f, 4),
    Document(1.05f, 4),
    Image(1.0f, 6),
    VeloHistory(1.0f, 4),
}

/**
 * One searchable thing. Everything the user can find is reduced to these fields; [payload] carries the
 * platform object needed to open the result and is never looked at by the search core.
 */
class SearchItem(
    /** Stable across index rebuilds; used for engagement statistics and list keys. */
    val key: String,
    val kind: SourceKind,
    val title: String,
    val subtitle: String = "",
    /** Further short texts that should match: e-mail addresses, phone numbers, a location. */
    val keywords: List<String> = emptyList(),
    /** Long text such as note contents or text recognised in a picture. */
    val body: String = "",
    /** Relevance known to the source alone, 0..1: an event later today, a page visited often. */
    val boost: Float = 0f,
    val payload: Any? = null,
) {
    override fun toString() = "SearchItem($key, $title)"
}
