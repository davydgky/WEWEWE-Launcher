package io.github.davydgky.launcher.crash

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.davydgky.launcher.R

/**
 * After a crash, the next start says so with a notification whose tap shares the report (nothing is sent
 * by itself). Each report is offered once.
 */
object CrashNotifier {

    private const val CHANNEL = "crashes"
    private const val ID = 4_301
    private const val PREFS = "crash_notifier"
    private const val SEEN = "seen"

    fun showIfNew(context: Context, guard: CrashGuard) {
        val latest = guard.reports().firstOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(SEEN, null) == latest.name) return
        prefs.edit().putString(SEEN, latest.name).apply()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val text = runCatching { latest.readText() }.getOrNull() ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.crash_reports), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val share = PendingIntent.getActivity(
            context,
            0,
            Intent.createChooser(send, context.getString(R.string.crash_reports)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_info)
            .setContentTitle(context.getString(R.string.crash_notification_title))
            .setContentText(context.getString(R.string.crash_notification_text))
            .setContentIntent(share)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ID, notification) }
    }
}
