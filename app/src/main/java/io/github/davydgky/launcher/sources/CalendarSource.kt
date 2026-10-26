package io.github.davydgky.launcher.sources

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import android.text.format.DateUtils
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.search.EventTarget

/**
 * Calendar events from 30 days ago to a year ahead. A recurring event appears once, with its next
 * occurrence. Needs READ_CALENDAR.
 */
class CalendarSource(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) :
    SearchSource(ID) {

    override fun missing(context: Context): Requirement? =
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) null
        else Requirement.Calendar

    // "Upcoming" ranking depends on the time; re-read now and then.
    override val refreshIntervalMs: Long = 60 * 60 * 1000L

    private class Occurrence(
        val eventId: Long, val begin: Long, val end: Long, val title: String,
        val location: String, val allDay: Boolean, val color: Int,
    )

    override suspend fun load(): List<SearchItem> {
        val now = clock()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - PAST_MS)
            ContentUris.appendId(it, now + FUTURE_MS)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE,
            Instances.EVENT_LOCATION, Instances.ALL_DAY, Instances.DISPLAY_COLOR,
        )
        val chosen = LinkedHashMap<Long, Occurrence>()
        context.contentResolver.query(uri, projection, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val occurrence = Occurrence(
                    eventId = c.getLong(0), begin = c.getLong(1), end = c.getLong(2),
                    title = c.getString(3)?.trim().orEmpty(), location = c.getString(4)?.trim().orEmpty(),
                    allDay = c.getInt(5) != 0, color = c.getInt(6),
                )
                val current = chosen[occurrence.eventId]
                // Sorted by start: keep the first one that has not ended, otherwise the latest past one.
                if (current == null || current.end < now) chosen[occurrence.eventId] = occurrence
            }
        }
        val untitled = context.getString(R.string.event_untitled)
        return chosen.values.map { event ->
            val timeFlag = if (event.allDay) DateUtils.FORMAT_UTC else DateUtils.FORMAT_SHOW_TIME
            val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or timeFlag
            // All-day events end at midnight of the following day.
            val end = if (event.allDay) event.end - 1 else event.end
            val time = DateUtils.formatDateRange(context, event.begin, maxOf(event.begin, end), flags)
            SearchItem(
                key = "event:${event.eventId}",
                kind = SourceKind.Event,
                title = event.title.ifBlank { untitled },
                subtitle = listOf(time, event.location).filter { it.isNotBlank() }.joinToString(" · "),
                keywords = listOfNotNull(event.location.takeIf { it.isNotBlank() }),
                boost = boost(event, now),
                payload = EventTarget(event.eventId, event.begin, event.end, event.allDay, event.color),
            )
        }
    }

    /** Running and soon upcoming events rank higher than far away or past ones. */
    private fun boost(event: Occurrence, now: Long): Float = when {
        event.end < now -> 0f
        event.begin <= now -> 0.8f
        else -> {
            val days = (event.begin - now) / DateUtils.DAY_IN_MILLIS.toFloat()
            if (days > 7f) 0.1f else 0.2f + 0.6f * (1f - days / 7f)
        }
    }

    override fun observe(onChange: () -> Unit) = context.observeUri(CalendarContract.CONTENT_URI, onChange)

    companion object {
        const val ID = "calendar"
        private const val PAST_MS = 30L * 24 * 60 * 60 * 1000
        private const val FUTURE_MS = 365L * 24 * 60 * 60 * 1000
    }
}
