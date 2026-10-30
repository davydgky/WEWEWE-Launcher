package io.github.davydgky.launcher.home

import android.content.pm.ApplicationInfo
import androidx.annotation.StringRes
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.ai.AppKnowledge
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
        learned: Map<String, AppKnowledge.Info> = emptyMap(),
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
        // Work and private space apps have their own boxes, so they are never mistaken for personal ones.
        val (own, other) = apps.partition { !it.isWorkProfile }
        own.groupBy { categoryOf(it, learned) }
            .toList()
            .sortedByDescending { (_, list) -> list.sumOf { (usage[it.key] ?: 0f).toDouble() } }
            .forEach { (title, list) -> groups += LibraryGroup(title, list.sortedWith(byUse)) }
        val (private, work) = other.partition { it.isPrivate }
        if (work.isNotEmpty()) groups += LibraryGroup(R.string.work_profile, work.sortedWith(byUse))
        if (private.isNotEmpty()) groups += LibraryGroup(R.string.private_space, private.sortedWith(byUse))
        // Everything from A to Z, for finding an app by its name.
        if (apps.isNotEmpty()) groups += LibraryGroup(R.string.library_all, apps.sortedBy { it.label.lowercase() })
        return groups
    }

    /**
     * The category an app declares, as the title of its box; for apps that declare none, the one Gemini
     * Nano gave it ([learned], by package).
     */
    @StringRes
    fun categoryOf(app: AppTarget, learned: Map<String, AppKnowledge.Info> = emptyMap()): Int {
        val info = app.info.applicationInfo
        if (info.category == ApplicationInfo.CATEGORY_UNDEFINED) {
            learnedCategory(learned[app.packageName]?.category)?.let { return it }
        }
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

    @StringRes
    private fun learnedCategory(id: String?): Int? = when (id) {
        "social" -> R.string.category_social
        "productivity" -> R.string.category_productivity
        "games" -> R.string.category_games
        "photo" -> R.string.category_photo
        "entertainment" -> R.string.category_entertainment
        "music" -> R.string.category_music
        "news" -> R.string.category_news
        "travel" -> R.string.category_travel
        "utilities" -> R.string.category_utilities
        "shopping" -> R.string.category_shopping
        "finance" -> R.string.category_finance
        "health" -> R.string.category_health
        "education" -> R.string.category_education
        "food" -> R.string.category_food
        else -> null
    }
}
