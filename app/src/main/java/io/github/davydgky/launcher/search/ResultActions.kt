package io.github.davydgky.launcher.search

import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.sources.Velo

/**
 * Opens results. Every launch is written to the launch log, which feeds the suggestions and ranks
 * things the user picked before higher. [bounds] are the on-screen bounds of the tapped element; apps
 * grow out of them.
 */
class ResultActions(private val activity: Activity) {

    private val app = activity.launcherApp
    private val launcherApps = activity.getSystemService(LauncherApps::class.java)

    fun open(item: SearchItem, bounds: Rect?) {
        when (val target = item.payload) {
            is AppTarget -> launchApp(target, bounds)
            is ShortcutTarget -> launchShortcut(target, bounds)
            is ContactTarget -> start(Intent(Intent.ACTION_VIEW, target.lookupUri), bounds, item.key)
            is EventTarget -> start(
                Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, target.eventId))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, target.begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, target.end),
                bounds, item.key,
            )
            is WebTarget -> start(Intent(Intent.ACTION_VIEW, Uri.parse(target.url)).setPackage(Velo.PACKAGE), bounds, item.key)
            is FileTarget -> openFile(target, bounds, item.key)
            is ImageTarget -> start(
                Intent(Intent.ACTION_VIEW).setDataAndType(target.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                bounds, item.key,
            )
            else -> Log.w(TAG, "Nothing to open for $item")
        }
    }

    fun launchApp(target: AppTarget, bounds: Rect?) {
        try {
            launcherApps.startMainActivity(target.component, target.user, bounds, options(bounds))
            app.launchLog.record(target.key)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot launch ${target.component}", e)
            toast(R.string.cannot_open)
        }
    }

    fun launchShortcut(target: ShortcutTarget, bounds: Rect?) {
        try {
            launcherApps.startShortcut(target.info, bounds, options(bounds))
            app.launchLog.record(target.key)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start shortcut ${target.key}", e)
            toast(R.string.cannot_open)
        }
    }

    fun call(contact: ContactTarget) {
        val phone = contact.phone ?: return
        start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)), null, null)
    }

    fun message(contact: ContactTarget) {
        val phone = contact.phone ?: return
        start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null)), null, null)
    }

    /** Hands the query to Velo (or the default browser). Only here does the input leave the launcher. */
    fun webSearch(query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val velo = Intent(intent).setPackage(Velo.PACKAGE)
        if (!start(velo, null, null, quiet = true)) start(intent, null, null)
    }

    fun appInfo(target: AppTarget) {
        runCatching { launcherApps.startAppDetailsActivity(target.component, target.user, null, null) }
            .onFailure { start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", target.packageName, null)), null, null) }
    }

    fun uninstall(target: AppTarget) {
        start(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", target.packageName, null))
                .putExtra(Intent.EXTRA_USER, target.user),
            null, null,
        )
    }

    private fun openFile(target: FileTarget, bounds: Rect?, key: String) {
        target.obsidianUri?.let { uri ->
            if (start(Intent(Intent.ACTION_VIEW, uri).setPackage(OBSIDIAN), bounds, key, quiet = true)) return
        }
        start(
            Intent(Intent.ACTION_VIEW).setDataAndType(target.uri, target.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            bounds, key,
        )
    }

    private fun start(intent: Intent, bounds: Rect?, logKey: String?, quiet: Boolean = false): Boolean = try {
        intent.sourceBounds = bounds
        activity.startActivity(intent, options(bounds))
        logKey?.let(app.launchLog::record)
        true
    } catch (e: ActivityNotFoundException) {
        if (!quiet) toast(R.string.no_app_to_open)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to open $intent", e)
        if (!quiet) toast(R.string.cannot_open)
        false
    }

    private fun options(bounds: Rect?) = bounds?.let {
        val location = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
        ActivityOptions.makeClipRevealAnimation(
            activity.window.decorView, it.left - location[0], it.top - location[1], it.width(), it.height(),
        ).toBundle()
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "ResultActions"
        const val OBSIDIAN = "md.obsidian"
    }
}
