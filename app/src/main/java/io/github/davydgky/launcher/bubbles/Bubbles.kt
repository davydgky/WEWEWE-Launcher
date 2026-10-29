package io.github.davydgky.launcher.bubbles

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.LocusId
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.search.AppTarget

/**
 * "Open as bubble" for any app, as in the Pixel Launcher. Android lets only the system put another
 * app into a bubble, so the launcher posts a bubble of its own ([BubbleActivity]) and opens the app
 * inside it: apps that start in the task they were opened from stay in the bubble, apps that insist
 * on a task of their own open full screen as usual.
 *
 * Bubbles need two consents, asked for the first time: notifications for this app (no notification
 * is ever shown, the bubble suppresses it) and bubbles for this app.
 */
object Bubbles {

    private const val TAG = "Bubbles"
    private const val CHANNEL = "bubbles"
    private const val SHORTCUT_PREFIX = "bubble:"
    private const val PERMISSION_REQUEST = 41

    /** Only apps of this user: an app of the work profile cannot join a task of the personal profile. */
    fun canOpen(context: Context, target: AppTarget): Boolean =
        target.user == Process.myUserHandle() && target.packageName != context.packageName

    fun isBubbleShortcut(id: String): Boolean = id.startsWith(SHORTCUT_PREFIX)

    fun open(activity: Activity, target: AppTarget) {
        val notifications = activity.getSystemService(NotificationManager::class.java)
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            toast(activity, R.string.bubble_allow_notifications)
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
            return
        }
        ensureChannel(activity, notifications)
        // The channel's own bubble flag stays "unset" until the user changes it there; with bubbles
        // allowed for the app, unset channels bubble. Only the app-wide choice matters here.
        if (!notifications.areBubblesEnabled() || notifications.bubblePreference == NotificationManager.BUBBLE_PREFERENCE_NONE) {
            toast(activity, R.string.bubble_allow_bubbles)
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
                )
            }
            return
        }
        val tag = try {
            post(activity, notifications, target)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open ${target.component} as a bubble", e)
            toast(activity, R.string.cannot_open)
            return
        }
        // If the system shows it as a plain notification after all (e.g. only selected conversations
        // may bubble), the app opens as usual instead and the notification goes away.
        Handler(Looper.getMainLooper()).postDelayed({
            val posted = notifications.activeNotifications.firstOrNull { it.tag == tag && it.id == NOTIFICATION_ID }
            if (posted != null && posted.notification.flags and Notification.FLAG_BUBBLE == 0) {
                Log.i(TAG, "Not shown as a bubble: ${target.component}")
                notifications.cancel(tag, NOTIFICATION_ID)
                toast(activity, R.string.bubble_not_allowed)
                activity.getSystemService(LauncherApps::class.java).startMainActivity(target.component, target.user, null, null)
            }
        }, BUBBLE_CHECK_MS)
    }

    private fun ensureChannel(context: Context, notifications: NotificationManager) {
        if (notifications.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.bubble_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.bubble_channel_description)
            setAllowBubbles(true)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notifications.createNotificationChannel(channel)
    }

    /** Posts the bubble; returns the notification's tag. */
    private fun post(context: Context, notifications: NotificationManager, target: AppTarget): String {
        val id = SHORTCUT_PREFIX + target.component.flattenToShortString()
        val icon = Icon.createWithBitmap(target.info.getIcon(0).toBitmap(ICON_PX, ICON_PX))
        val person = Person.Builder().setName(target.label).setIcon(icon).setKey(id).setImportant(true).build()
        val open = Intent(context, BubbleActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            // One bubble per app: the data makes each app's intent distinct.
            .setData(Uri.fromParts("bubble", target.component.flattenToString(), null))
            .putExtra(BubbleActivity.EXTRA_COMPONENT, target.component.flattenToString())
        // Bubbles belong to conversations, and a conversation needs a long-lived shortcut.
        context.getSystemService(ShortcutManager::class.java).pushDynamicShortcut(
            ShortcutInfo.Builder(context, id)
                .setShortLabel(target.label)
                .setLongLived(true)
                .setIcon(icon)
                .setPerson(person)
                .setLocusId(LocusId(id))
                .setIntent(open)
                .build(),
        )
        val pending = PendingIntent.getActivity(
            context, id.hashCode(), open,
            // The system adds the bubble's task to the intent, so it has to stay mutable.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bubble = Notification.BubbleMetadata.Builder(pending, icon)
            .setDesiredHeight(BUBBLE_HEIGHT_DP)
            .setAutoExpandBubble(true)
            .setSuppressNotification(true)
            .build()
        val now = System.currentTimeMillis()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(target.label)
            .setContentIntent(pending)
            .setShortcutId(id)
            .setLocusId(LocusId(id))
            .addPerson(person)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(Notification.MessagingStyle(person).addMessage(context.getString(R.string.bubble_message), now, person))
            .setShowWhen(false)
            .setBubbleMetadata(bubble)
            .build()
        notifications.notify(id, NOTIFICATION_ID, notification)
        return id
    }

    private fun toast(context: Context, message: Int) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    private const val NOTIFICATION_ID = 1
    private const val ICON_PX = 192
    private const val BUBBLE_HEIGHT_DP = 640
    private const val BUBBLE_CHECK_MS = 700L
}

/**
 * The content of a bubble: starts the app in its own task, which is the bubble, and steps aside.
 */
class BubbleActivity : Activity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val component = intent.getStringExtra(EXTRA_COMPONENT)?.let(ComponentName::unflattenFromString)
        if (component != null) {
            // Without FLAG_ACTIVITY_NEW_TASK the app starts in this task, inside the bubble.
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
            runCatching { startActivity(launch) }.onFailure {
                Log.w("Bubbles", "Cannot start $component in a bubble", it)
                Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_COMPONENT = "component"
    }
}
