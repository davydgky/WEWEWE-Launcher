package io.github.davydgky.launcher.sources

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.davydgky.launcher.core.SearchItem

/** What the user still has to allow before a source can deliver anything. */
enum class Requirement {
    DefaultLauncher,
    Contacts,
    Calendar,
    Images,
    AllFiles,
    NoteFolder,
    VeloInstall,
    VeloPermission,
    NotificationAccess,
    Sms,
}

/**
 * One kind of searchable data. A source reads its data completely in [load]; the repository builds an
 * index from it and replaces the previous one. [observe] reports changes so the index stays current.
 */
abstract class SearchSource(val id: String) {

    /** Null when the source can run; otherwise the permission or role it lacks. */
    abstract fun missing(context: Context): Requirement?

    /** Reads all items. Runs on a background thread and may take a while. */
    abstract suspend fun load(): List<SearchItem>

    /** Registers for change notifications; [onChange] may be called on any thread. */
    open fun observe(onChange: () -> Unit): AutoCloseable? = null

    /** Data without change notifications (or with time-dependent ranking) is re-read this often. */
    open val refreshIntervalMs: Long? = null
}

/** Observes [uri] and everything below it. Returns null if the provider refuses (missing permission). */
fun Context.observeUri(uri: Uri, onChange: () -> Unit): AutoCloseable? {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onChange()
    }
    return try {
        contentResolver.registerContentObserver(uri, true, observer)
        AutoCloseable { contentResolver.unregisterContentObserver(observer) }
    } catch (e: SecurityException) {
        Log.w("SearchSource", "Cannot observe $uri", e)
        null
    }
}
