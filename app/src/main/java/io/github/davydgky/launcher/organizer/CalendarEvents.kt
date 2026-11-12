package io.github.davydgky.launcher.organizer

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.util.TimeZone

/** One occurrence of a calendar event on a day, read from the system Calendar Provider. */
data class CalendarEvent(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val allDay: Boolean,
    val color: Int,
)

/** A calendar the user can write new events into (their Google account's own calendar, typically). */
data class WritableCalendar(val id: Long, val accountName: String, val displayName: String, val color: Int)

/**
 * Reads events from and writes events to the system Calendar Provider (`CalendarContract`), which already
 * syncs with the Google Calendar app and account on the device. This needs no Google sign-in or API key
 * of its own: a reminder "sent to the calendar" shows up in Google Calendar through the normal Android
 * sync adapter, the same way the stock Calendar app would write it.
 */
object CalendarEvents {

    fun hasReadAccess(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun hasWriteAccess(context: Context) = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Every instance between [fromMillis] and [toMillis] (inclusive), one entry per occurrence. */
    fun between(context: Context, fromMillis: Long, toMillis: Long): List<CalendarEvent> {
        if (!hasReadAccess(context)) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMillis)
            ContentUris.appendId(it, toMillis)
        }.build()
        val projection = arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.ALL_DAY, Instances.DISPLAY_COLOR)
        val events = ArrayList<CalendarEvent>()
        context.contentResolver.query(uri, projection, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                events += CalendarEvent(
                    eventId = c.getLong(0),
                    begin = c.getLong(1),
                    end = c.getLong(2),
                    title = c.getString(3)?.trim().orEmpty(),
                    allDay = c.getInt(4) != 0,
                    color = c.getInt(5),
                )
            }
        }
        return events
    }

    /** Calendars the device can add events to, the default (first writable, usually the Google one) first. */
    fun writableCalendars(context: Context): List<WritableCalendar> {
        if (!hasWriteAccess(context)) return emptyList()
        val projection = arrayOf(Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR)
        val selection = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${Calendars.VISIBLE} = 1"
        val calendars = ArrayList<WritableCalendar>()
        context.contentResolver.query(Calendars.CONTENT_URI, projection, selection, null, "${Calendars.IS_PRIMARY} DESC")?.use { c ->
            while (c.moveToNext()) {
                calendars += WritableCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3))
            }
        }
        return calendars
    }

    /** Writes a new event into [calendarId]; returns its id, or null if it could not be written. */
    fun insertEvent(context: Context, calendarId: Long, title: String, beginMillis: Long, endMillis: Long, allDay: Boolean): Long? {
        if (!hasWriteAccess(context)) return null
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.DTSTART, beginMillis)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            if (allDay) {
                put(Events.DTEND, endMillis)
                put(Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(Events.DTEND, endMillis)
                put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        val uri = context.contentResolver.insert(Events.CONTENT_URI, values) ?: return null
        return ContentUris.parseId(uri)
    }

    fun deleteEvent(context: Context, eventId: Long): Boolean {
        if (!hasWriteAccess(context)) return false
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        return context.contentResolver.delete(uri, null, null) > 0
    }
}
