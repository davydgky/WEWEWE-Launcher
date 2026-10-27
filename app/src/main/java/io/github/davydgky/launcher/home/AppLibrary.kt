package io.github.davydgky.launcher.home

import android.content.pm.ApplicationInfo
import androidx.annotation.StringRes
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.search.AppTarget

/** A box in the App Library. */
data class LibraryGroup(@param:StringRes val title: Int, val apps: List<AppTarget>)

/**
 * Sorts all apps into App Library boxes like iOS: suggestions, recently added, then categories from the
 * apps' own declaration. Within a box, the most used apps come first.
 */
object AppLibrary {

    private const val RECENT_DAYS = 14L
    private const val RECENT_COUNT = 8

    fun groups(
        apps: List<AppTarget>,
        suggestions: List<AppTarget>,
        usage: Map<String, Float>,
        now: Long = System.currentTimeMillis(),
    ): List<LibraryGroup> {
        val byUse = compareByDescending<AppTarget> { usage[it.key] ?: 0f }.thenBy { it.label.lowercase() }
        val groups = ArrayList<LibraryGroup>()
        if (suggestions.isNotEmpty()) groups += LibraryGroup(R.string.library_suggestions, suggestions.take(8))
        val recent = apps
            .filter { now - it.info.firstInstallTime < RECENT_DAYS * 24 * 60 * 60 * 1000 }
            .sortedByDescending { it.info.firstInstallTime }
            .take(RECENT_COUNT)
        if (recent.isNotEmpty()) groups += LibraryGroup(R.string.library_recent, recent)
        apps.groupBy(::categoryOf)
            .toList()
            .sortedByDescending { (_, list) -> list.sumOf { (usage[it.key] ?: 0f).toDouble() } }
            .forEach { (title, list) -> groups += LibraryGroup(title, list.sortedWith(byUse)) }
        return groups
    }

    /** The category an app declares, as the title of its box. */
    @StringRes
    fun categoryOf(app: AppTarget): Int {
        val info = app.info.applicationInfo
        return when (info.category) {
            ApplicationInfo.CATEGORY_SOCIAL -> R.string.category_social
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> R.string.category_productivity
            ApplicationInfo.CATEGORY_GAME -> R.string.category_games
            ApplicationInfo.CATEGORY_IMAGE -> R.string.category_photo
            ApplicationInfo.CATEGORY_VIDEO -> R.string.category_entertainment
            ApplicationInfo.CATEGORY_AUDIO -> R.string.category_music
            ApplicationInfo.CATEGORY_NEWS -> R.string.category_news
            ApplicationInfo.CATEGORY_MAPS -> R.string.category_travel
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> R.string.category_utilities
            else -> if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) R.string.category_utilities else R.string.category_other
        }
    }
}
