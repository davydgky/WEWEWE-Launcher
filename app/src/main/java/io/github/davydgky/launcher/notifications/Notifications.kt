package io.github.davydgky.launcher.notifications

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One notification as the launcher shows it: in the app's long-press menu, and as a dot on its icon. */
class AppNotification(
    val key: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val contentIntent: PendingIntent?,
    val autoCancel: Boolean,
)

/**
 * Notifications of all apps while notification access is granted, like the dots and the long-press
 * list of the Pixel Launcher. Held in memory here; their message texts are also kept on the device by
 * [io.github.davydgky.launcher.messages.Messages] when saving messages is on.
 */
class Notifications(private val context: Context) {

    private val state = MutableStateFlow<Map<String, List<AppNotification>>>(emptyMap())

    /** Per [keyOf] app, newest first. */
    val byApp: StateFlow<Map<String, List<AppNotification>>> = state.asStateFlow()

    private var listener: NotificationListener? = null

    val component: ComponentName get() = ComponentName(context, NotificationListener::class.java)

    fun hasAccess(): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)

    /** Opens the system page where notification access for the launcher is switched on. */
    fun accessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())

    /** Opens what the notification leads to and, like the shade, removes it if it asks for that. */
    fun open(notification: AppNotification) {
        val intent = notification.contentIntent ?: return
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
            .toBundle()
        runCatching { intent.send(context, 0, null, null, null, null, options) }
            .onFailure { Log.w(TAG, "Cannot open notification", it) }
        if (notification.autoCancel) runCatching { listener?.cancelNotification(notification.key) }
    }

    internal fun attach(service: NotificationListener) {
        listener = service
        refresh()
    }

    internal fun detach(service: NotificationListener) {
        if (listener === service) listener = null
        state.value = emptyMap()
    }

    internal fun refresh() {
        val service = listener ?: return
        val active = runCatching { service.activeNotifications }.getOrNull() ?: return
        state.value = active
            .filter(::shows)
            .sortedByDescending { it.postTime }
            .groupBy({ keyOf(it.packageName, it.user) }, ::toAppNotification)
    }

    /** Ongoing notifications (music, navigation) and group summaries get no dot, as in the shade. */
    private fun shows(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return !sbn.isOngoing && n.flags and Notification.FLAG_GROUP_SUMMARY == 0
    }

    private fun toAppNotification(sbn: StatusBarNotification): AppNotification {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        return AppNotification(
            key = sbn.key,
            title = title,
            text = text,
            postTime = sbn.postTime,
            contentIntent = sbn.notification.contentIntent,
            autoCancel = sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
    }

    companion object {
        private const val TAG = "Notifications"

        fun keyOf(packageName: String, user: UserHandle) = "${user.hashCode()}/$packageName"
    }
}

/** Receives notifications from the system and hands them to [Notifications]. */
class NotificationListener : NotificationListenerService() {

    private val notifications get() = (application as io.github.davydgky.launcher.LauncherApplication).notifications

    private val app get() = application as io.github.davydgky.launcher.LauncherApplication

    override fun onListenerConnected() {
        notifications.attach(this)
        // What is in the shade right now is kept as well (duplicates are ignored).
        runCatching { activeNotifications }.getOrNull()?.forEach { app.messages.capture(it, app.appScope) }
    }

    override fun onListenerDisconnected() = notifications.detach(this)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        notifications.refresh()
        app.messages.capture(sbn, app.appScope)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = notifications.refresh()
}
