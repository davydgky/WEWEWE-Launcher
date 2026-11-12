package io.github.davydgky.launcher.organizer

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarScaleTest {
    private val today = LocalDate.of(2026, 11, 12)

    @Test
    fun pinchingStopsAtTheDayAndTheYear() {
        assertEquals(CalendarScale.Day, CalendarScale.Day.zoomedIn())
        assertEquals(CalendarScale.Day, CalendarScale.Week.zoomedIn())
        assertEquals(CalendarScale.Month, CalendarScale.Week.zoomedOut())
        assertEquals(CalendarScale.Year, CalendarScale.Year.zoomedOut())
    }

    @Test
    fun theArrowsStepByTheScaleOnScreen() {
        assertEquals(today.plusDays(1), stepDate(CalendarScale.Day, today, forward = true, today))
        assertEquals(today.minusWeeks(1), stepDate(CalendarScale.Week, today, forward = false, today))
        assertEquals(LocalDate.of(2026, 12, 1), stepDate(CalendarScale.Month, today, forward = true, today))
        assertEquals(LocalDate.of(2025, 1, 1), stepDate(CalendarScale.Year, today, forward = false, today))
        // Back in the current month or year: today, not its first day.
        assertEquals(today, stepDate(CalendarScale.Month, LocalDate.of(2026, 10, 1), forward = true, today))
        assertEquals(today, stepDate(CalendarScale.Year, LocalDate.of(2027, 1, 1), forward = false, today))
    }

    @Test
    fun theYearReadsEventsForAllOfIt() {
        assertEquals(LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 12, 31), calendarWindow(CalendarScale.Year, today))
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 12, 31), calendarWindow(CalendarScale.Week, today))
    }

    @Test
    fun overlappingEventsGoSideBySide() {
        val dayStart = 0L
        val dayEnd = 24 * 60 * 60_000L
        fun event(id: Long, fromHour: Int, toHour: Int) = CalendarEvent(id, fromHour * 3_600_000L, toHour * 3_600_000L, "e$id", false, 0)
        val placement = placeInLanes(listOf(event(1, 9, 11), event(2, 10, 12), event(3, 11, 13)), dayStart, dayEnd)
        assertEquals(2, placement.lanes)
        assertEquals(listOf(0, 1, 0), placement.items.map { it.lane })
        assertEquals(9 * 60, placement.items.first().startMinute)
    }

    @Test
    fun anEventFromTheDayBeforeStartsAtMidnight() {
        val dayStart = 24 * 60 * 60_000L
        val event = CalendarEvent(1, dayStart - 60 * 60_000L, dayStart + 60 * 60_000L, "late", false, 0)
        val placed = placeInLanes(listOf(event), dayStart, dayStart * 2).items.single()
        assertEquals(0, placed.startMinute)
        assertEquals(60, placed.endMinute)
    }
}
