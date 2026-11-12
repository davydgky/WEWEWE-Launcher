package io.github.davydgky.launcher.organizer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarEventDaysTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")
    private val from = LocalDate.of(2026, 10, 1)
    private val to = LocalDate.of(2026, 12, 31)

    private fun millis(dateTime: LocalDateTime, zone: ZoneId) = dateTime.atZone(zone).toInstant().toEpochMilli()
    private fun utcMidnight(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun event(begin: Long, end: Long, allDay: Boolean) = CalendarEvent(1, begin, end, "x", allDay, 0)

    @Test
    fun anAllDayEventIsOnItsOwnDayInEveryTimeZone() {
        val day = LocalDate.of(2026, 11, 12)
        val event = event(utcMidnight(day), utcMidnight(day.plusDays(1)), allDay = true)
        assertEquals(listOf(day), event.days(from, to, berlin))
        // Read in local time, midnight UTC would be the evening before in New York.
        assertEquals(listOf(day), event.days(from, to, newYork))
    }

    @Test
    fun aTimedEventEndingAtMidnightStaysOnItsDay() {
        val day = LocalDate.of(2026, 11, 12)
        val event = event(millis(day.atTime(22, 0), berlin), millis(day.plusDays(1).atStartOfDay(), berlin), allDay = false)
        assertEquals(listOf(day), event.days(from, to, berlin))
    }

    @Test
    fun aMultiDayEventShowsOnEveryDayItCovers() {
        val start = LocalDate.of(2026, 11, 12)
        val event = event(millis(start.atTime(18, 0), berlin), millis(start.plusDays(2).atTime(10, 0), berlin), allDay = false)
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), event.days(from, to, berlin))
    }

    @Test
    fun daysOutsideTheWindowAreLeftOut() {
        val event = event(utcMidnight(LocalDate.of(2026, 9, 1)), utcMidnight(LocalDate.of(2027, 3, 1)), allDay = true)
        val days = event.days(from, to, berlin)
        assertEquals(from, days.first())
        assertEquals(to, days.last())
        assertEquals(92, days.size)
    }
}
