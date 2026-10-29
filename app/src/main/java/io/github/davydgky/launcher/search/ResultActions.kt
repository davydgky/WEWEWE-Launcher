package io.github.davydgky.launcher.search

import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.bubbles.Bubbles
import io.github.davydgky.launcher.core.QuickAction
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SmartAction
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.settings.SettingsActivity
import io.github.davydgky.launcher.sources.Velo
import java.time.ZoneId

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
            is SettingTarget -> openSetting(target, bounds, item.key)
            is AnswerTarget -> copy(target.copyText)
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

    fun mail(contact: ContactTarget) {
        val email = contact.email ?: return
        start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null)), null, null)
    }

    /**
     * Carries out a request Gemini Nano recognised ("ruf Mama an"): always through the app that does it,
     * where the user confirms (dialer, messages, mail, maps, calendar), never on its own.
     */
    fun runSmart(action: SmartAction) {
        when (action) {
            is SmartAction.Call -> {
                val number = numberOf(action.who)
                if (number == null) toast(activity.getString(R.string.smart_contact_not_found, action.who))
                start(Intent(Intent.ACTION_DIAL, number?.let { Uri.fromParts("tel", it, null) }), null, null)
            }
            is SmartAction.Message -> {
                val number = numberOf(action.who)
                if (number == null) toast(activity.getString(R.string.smart_contact_not_found, action.who))
                start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number.orEmpty(), null)).putExtra("sms_body", action.text), null, null)
            }
            is SmartAction.Mail -> {
                val address = if ('@' in action.who) action.who else contactOf(action.who)?.email
                if (address == null) toast(activity.getString(R.string.smart_contact_not_found, action.who))
                start(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                        .putExtra(Intent.EXTRA_EMAIL, listOfNotNull(address).toTypedArray())
                        .putExtra(Intent.EXTRA_SUBJECT, action.subject)
                        .putExtra(Intent.EXTRA_TEXT, action.text),
                    null, null,
                )
            }
            is SmartAction.Navigate -> {
                val place = Uri.encode(action.place)
                if (!start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$place")), null, null, quiet = true)) {
                    start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$place")), null, null)
                }
            }
            is SmartAction.Event -> {
                val zone = ZoneId.systemDefault()
                val begin = action.start.atZone(zone).toInstant().toEpochMilli()
                val end = (action.end ?: action.start.plusHours(1)).atZone(zone).toInstant().toEpochMilli()
                start(
                    Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, action.title)
                        .putExtra(CalendarContract.Events.EVENT_LOCATION, action.place)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end),
                    null, null,
                )
            }
            is SmartAction.WebSearch -> webSearch(action.query)
        }
    }

    /** A number as typed, or the number of the contact that best matches [who]. */
    private fun numberOf(who: String): String? =
        if (who.matches(PHONE)) who else contactOf(who)?.phone

    private fun contactOf(who: String): ContactTarget? {
        val results = app.search.search(who)
        return (listOfNotNull(results.top) + results.groups.flatMap { it.items })
            .firstNotNullOfOrNull { it.item.payload as? ContactTarget }
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    /** Copies a calculated result; Android confirms the copy itself. */
    fun copy(text: String) {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
    }

    /** Searches the Play Store for apps (or its web page without the store). */
    fun playStoreSearch(query: String) {
        val encoded = Uri.encode(query)
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encoded&c=apps")).setPackage(PLAY_STORE)
        if (!start(store, null, null, quiet = true)) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$encoded&c=apps")), null, null)
        }
    }

    /** Google Photos is installed: its own search covers the pictures that are only in the cloud. */
    val hasGooglePhotos: Boolean by lazy { activity.packageManager.getLaunchIntentForPackage(GOOGLE_PHOTOS) != null }

    fun googlePhotosSearch(query: String) {
        val search = Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.google.com/search/${Uri.encode(query)}")).setPackage(GOOGLE_PHOTOS)
        if (start(search, null, null, quiet = true)) return
        // Without the search link: open Photos with the words ready to paste.
        copy(query)
        activity.packageManager.getLaunchIntentForPackage(GOOGLE_PHOTOS)?.let { start(it, null, null) }
    }

    private fun openSetting(target: SettingTarget, bounds: Rect?, key: String) {
        when (target.special) {
            SettingTarget.Special.Torch -> {
                if (app.torch.toggle()) app.launchLog.record(key) else toast(R.string.torch_unavailable)
                return
            }
            SettingTarget.Special.Launcher -> {
                start(Intent(activity, SettingsActivity::class.java), bounds, key)
                return
            }
            null -> Unit
        }
        val action = target.action ?: return
        val intent = if (action == Intent.ACTION_SET_WALLPAPER) Intent.createChooser(Intent(action), null) else Intent(action)
        // Device makers rename or drop some pages; the main settings always exist.
        if (!start(intent, bounds, key, quiet = true)) start(Intent(Settings.ACTION_SETTINGS), bounds, key)
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

    /** Digital Wellbeing's page for the app (usage and app timer), if it is installed. */
    fun appTimerIntent(target: AppTarget): Intent? =
        Intent(Settings.ACTION_APP_USAGE_SETTINGS)
            .putExtra(Intent.EXTRA_PACKAGE_NAME, target.packageName)
            .takeIf { it.resolveActivity(activity.packageManager) != null }

    fun openAppTimer(target: AppTarget) {
        appTimerIntent(target)?.let { start(it, null, null) }
    }

    /** Starts a timer right away or sets an alarm (with the clock app's confirmation). */
    fun run(action: QuickAction) {
        val intent = when (action) {
            is QuickAction.Timer -> Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            is QuickAction.Alarm -> Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
        }
        if (start(intent, null, null) && action is QuickAction.Timer) {
            toast(R.string.timer_started)
        }
    }

    fun canOpenAsBubble(target: AppTarget): Boolean = Bubbles.canOpen(activity, target)

    fun openAsBubble(target: AppTarget) {
        Bubbles.open(activity, target)
        app.launchLog.record(target.key)
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
        const val PLAY_STORE = "com.android.vending"
        const val GOOGLE_PHOTOS = "com.google.android.apps.photos"
        val PHONE = Regex("^[+0-9 ()/-]{3,}$")
    }
}
