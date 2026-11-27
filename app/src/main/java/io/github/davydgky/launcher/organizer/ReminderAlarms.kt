package io.github.davydgky.launcher.organizer

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.util.Log
import io.github.davydgky.launcher.BuildConfig
import io.github.davydgky.launcher.LauncherApplication
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.island.Peek
import io.github.davydgky.launcher.island.PeekKind
import io.github.davydgky.launcher.launcherApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reminders with a time remind: at that time a notification comes (with "Done" and "In 10 minutes"), and
 * the Dynamic Island shows the reminder. One exact alarm is kept for the next one; it follows every
 * change of the reminders and is set again after a restart, when the app starts.
 */
object ReminderAlarms {

    private const val TAG = "ReminderAlarms"
    private const val CHANNEL = "reminders"
    const val ACTION_FIRE = "io.github.davydgky.launcher.REMINDER_FIRE"
    const val ACTION_DONE = "io.github.davydgky.launcher.REMINDER_DONE"
    const val ACTION_SNOOZE = "io.github.davydgky.launcher.REMINDER_SNOOZE"
    const val EXTRA_TODO = "todo"
    private const val SNOOZE_MS = 10 * 60_000L

    /** Reminders due up to this long before the alarm are still announced (an alarm can come late). */
    private const val LATE_MS = 2 * 60_000L

    /** Keeps the alarm on the next due reminder for as long as the app runs. */
    fun watch(app: LauncherApplication) {
        app.appScope.launch {
            app.organizer.organizer
                .filterNotNull()
                .map { state -> nextDue(state.todos, System.currentTimeMillis()) }
                .distinctUntilChanged()
                .collect { next -> schedule(app, next) }
        }
    }

    /** The earliest time of an open reminder with a time, still to come. */
    fun nextDue(todos: List<Todo>, now: Long): Long? =
        todos.filter { !it.done && it.hasTime && (it.dueAtMillis ?: 0L) > now }.minOfOrNull { it.dueAtMillis!! }

    /** Open reminders with a time that are due at [time] (or came due shortly before). */
    fun dueAt(todos: List<Todo>, time: Long): List<Todo> =
        todos.filter { !it.done && it.hasTime && (it.dueAtMillis ?: 0L) in (time - LATE_MS)..(time + 1_000) }

    private fun schedule(context: Context, time: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = fireIntent(context, time ?: 0L)
        // Launcher and island app share the reminders; only one of them reminds, the island app if it is there.
        if (time == null || (BuildConfig.IS_LAUNCHER && islandAppInstalled(context))) {
            alarms.cancel(intent)
            return
        }
        runCatching {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
            }
        }.onFailure { Log.w(TAG, "Cannot set the reminder alarm", it) }
    }

    private fun islandAppInstalled(context: Context) =
        runCatching { context.packageManager.getPackageInfo(BuildConfig.ISLAND_PACKAGE, 0) }.isSuccess

    private fun fireIntent(context: Context, time: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra("time", time),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Notification and island for each reminder due now. */
    fun announce(context: Context, todos: List<Todo>) {
        val app = context.launcherApp
        todos.forEach { todo ->
            notify(context, todo)
            app.island.showPeek(
                Peek(
                    id = todo.id + 1_000_000_000L,
                    kind = PeekKind.Reminder,
                    title = todo.title,
                    text = context.getString(R.string.reminder_due_now),
                    source = context.getString(R.string.reminders_label),
                    iconRes = R.drawable.ic_bell,
                    tint = 0xFFFFD60A.toInt(),
                    durationMs = 8_000,
                ),
            )
        }
    }

    private fun notify(context: Context, todo: Todo) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminders_label), NotificationManager.IMPORTANCE_HIGH))
        }
        val id = todo.id.toInt()
        fun action(action: String, slot: Int) = PendingIntent.getBroadcast(
            context,
            id * 2 + slot,
            Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_TODO, todo.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, OrganizerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = todo.dueAtMillis?.let { DateFormat.getTimeFormat(context).format(it) }.orEmpty()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(todo.title)
            .setContentText(listOf(time, todo.notes).filter { it.isNotBlank() }.joinToString(" · "))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.reminder_done), action(ACTION_DONE, 0)).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.reminder_snooze), action(ACTION_SNOOZE, 1)).build())
            .build()
        runCatching { manager.notify(CHANNEL, id, notification) }
    }

    fun cancelNotification(context: Context, todoId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(CHANNEL, todoId.toInt())
    }

    fun snoozeMillis() = SNOOZE_MS
}

/** The alarm of [ReminderAlarms] and the buttons of its notification. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.launcherApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.organizer.start()
                // After a restart the reminders are still being read.
                val state = withTimeoutOrNull(10_000) { app.organizer.organizer.filterNotNull().first() } ?: return@launch
                val todoId = intent.getLongExtra(ReminderAlarms.EXTRA_TODO, -1L)
                when (intent.action) {
                    ReminderAlarms.ACTION_FIRE -> ReminderAlarms.announce(context, ReminderAlarms.dueAt(state.todos, System.currentTimeMillis()))
                    ReminderAlarms.ACTION_DONE -> {
                        if (state.todos.any { it.id == todoId && !it.done }) app.organizer.toggleDone(todoId)
                        ReminderAlarms.cancelNotification(context, todoId)
                    }
                    ReminderAlarms.ACTION_SNOOZE -> {
                        val later = System.currentTimeMillis() + ReminderAlarms.snoozeMillis()
                        app.organizer.updateTodo(todoId) { it.copy(dueAtMillis = later, hasTime = true) }
                        ReminderAlarms.cancelNotification(context, todoId)
                    }
                    // A restart: the app's start set the alarm again already.
                    else -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }
}
