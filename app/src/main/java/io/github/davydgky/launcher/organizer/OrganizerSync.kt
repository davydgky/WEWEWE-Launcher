package io.github.davydgky.launcher.organizer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.github.davydgky.launcher.BuildConfig
import io.github.davydgky.launcher.launcherApp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The launcher and the island app share one set of reminders. Each keeps its own copy and tells the other
 * of every change, which merges it in reminder by reminder ([OrganizerMerge]); nothing is replaced as a
 * whole. They talk through [OrganizerProvider], which only an app signed with the same key may call.
 */
object OrganizerSync {

    private const val TAG = "OrganizerSync"
    const val METHOD_GET = "get"
    const val METHOD_PUT = "put"
    const val KEY_STATE = "state"

    /** The other app of the two: the island app for the launcher, and the other way round. */
    private val peerPackage = if (BuildConfig.IS_LAUNCHER) BuildConfig.ISLAND_PACKAGE else BuildConfig.LAUNCHER_PACKAGE

    private fun peerUri() = Uri.parse("content://$peerPackage.organizer")

    private fun peerInstalled(context: Context) =
        runCatching { context.packageManager.getPackageInfo(peerPackage, 0) }.isSuccess

    /** The other app's reminders, or null if it is not installed or does not answer. Not on the main thread. */
    fun fetch(context: Context): OrganizerState? {
        if (!peerInstalled(context)) return null
        return runCatching {
            val result = context.contentResolver.call(peerUri(), METHOD_GET, null, null) ?: return null
            result.getString(KEY_STATE)?.let(OrganizerCodec::decode)
        }.onFailure { Log.w(TAG, "Cannot read the other app's reminders", it) }.getOrNull()
    }

    /** Hands these reminders to the other app, which keeps them if they are newer. Not on the main thread. */
    fun send(context: Context, state: OrganizerState) {
        if (!peerInstalled(context)) return
        runCatching {
            context.contentResolver.call(peerUri(), METHOD_PUT, null, Bundle().apply { putString(KEY_STATE, OrganizerCodec.encode(state)) })
        }.onFailure { Log.w(TAG, "Cannot share the reminders", it) }
    }
}

/**
 * The reminders for the other WEWEWE app, through `call` only. The authority is `<package>.organizer`;
 * the manifest guards it with a signature permission, so only the launcher and the island app reach it.
 */
class OrganizerProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        val repository = context.launcherApp.organizer
        repository.start()
        // Called on a binder thread: waiting for the reminders to be read is fine here.
        val current = runBlocking { withTimeoutOrNull(5_000) { repository.organizer.filterNotNull().first() } } ?: return null
        return when (method) {
            OrganizerSync.METHOD_GET -> Bundle().apply { putString(OrganizerSync.KEY_STATE, OrganizerCodec.encode(current)) }
            OrganizerSync.METHOD_PUT -> {
                val incoming = extras?.getString(OrganizerSync.KEY_STATE)?.let { runCatching { OrganizerCodec.decode(it) }.getOrNull() } ?: return null
                // The repository lives on the main thread.
                android.os.Handler(android.os.Looper.getMainLooper()).post { repository.mergeFromPeer(incoming) }
                Bundle.EMPTY
            }
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
