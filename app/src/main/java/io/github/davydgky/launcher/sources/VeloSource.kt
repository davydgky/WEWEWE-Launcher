package io.github.davydgky.launcher.sources

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.search.WebTarget
import kotlin.math.ln
import kotlin.math.pow

/**
 * Velo's browsing history and start page favourites, read from Velo's search data provider. The
 * contract (authority, permission, columns) is documented in Velo's README.
 */
class VeloSource(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) :
    SearchSource(ID) {

    override fun missing(context: Context): Requirement? = when {
        context.packageManager.resolveContentProvider(Velo.AUTHORITY, PackageManager.ComponentInfoFlags.of(0)) == null ->
            Requirement.VeloInstall
        context.checkSelfPermission(Velo.PERMISSION) != PackageManager.PERMISSION_GRANTED -> Requirement.VeloPermission
        else -> null
    }

    override suspend fun load(): List<SearchItem> {
        val now = clock()
        val bookmarks = ArrayList<SearchItem>()
        val bookmarkSites = HashSet<String>()
        context.contentResolver.query(Velo.BOOKMARKS, arrayOf("title", "url"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val url = c.getString(1) ?: continue
                bookmarkSites += VeloUrls.site(url)
                bookmarks += SearchItem(
                    key = "velo:bookmark:$url",
                    kind = SourceKind.VeloBookmark,
                    title = c.getString(0)?.takeIf { it.isNotBlank() } ?: VeloUrls.display(url),
                    subtitle = VeloUrls.display(url),
                    boost = 0.3f,
                    payload = WebTarget(url, isBookmark = true),
                )
            }
        }
        val history = ArrayList<SearchItem>()
        context.contentResolver.query(Velo.HISTORY, arrayOf("url", "title", "last_visited", "visits"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val url = c.getString(0) ?: continue
                // A favourite already stands for this page.
                if (VeloUrls.site(url) in bookmarkSites) continue
                history += SearchItem(
                    key = "velo:history:$url",
                    kind = SourceKind.VeloHistory,
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: VeloUrls.display(url),
                    subtitle = VeloUrls.display(url),
                    boost = historyBoost(visits = c.getInt(3), lastVisited = c.getLong(2), now = now),
                    payload = WebTarget(url, isBookmark = false),
                )
            }
        }
        return bookmarks + history
    }

    override fun observe(onChange: () -> Unit) = context.observeUri(Velo.ROOT, onChange)

    companion object {
        const val ID = "velo"

        /** Often and recently visited pages first: visits count logarithmically, fading over 30 days. */
        fun historyBoost(visits: Int, lastVisited: Long, now: Long): Float {
            val frequency = (ln(1.0 + visits.coerceAtLeast(1)) / ln(32.0)).coerceAtMost(1.0)
            val ageDays = ((now - lastVisited).coerceAtLeast(0) / 86_400_000.0)
            return (frequency * 0.5.pow(ageDays / 30.0)).toFloat()
        }
    }
}

/** Velo's search data contract. */
object Velo {
    const val PACKAGE = "io.github.davydgky.browser"
    const val AUTHORITY = "io.github.davydgky.browser.searchdata"
    const val PERMISSION = "io.github.davydgky.browser.permission.READ_SEARCH_DATA"
    val ROOT: Uri = Uri.parse("content://$AUTHORITY")
    val HISTORY: Uri = Uri.withAppendedPath(ROOT, "history")
    val BOOKMARKS: Uri = Uri.withAppendedPath(ROOT, "bookmarks")
}

object VeloUrls {
    /** "https://www.example.com/a/" -> "example.com/a" */
    fun display(url: String): String = url.substringAfter("://").removePrefix("www.").removeSuffix("/")

    /** Compares pages the way users see them: scheme, "www." and a trailing slash do not matter. */
    fun site(url: String): String = display(url).lowercase()
}
