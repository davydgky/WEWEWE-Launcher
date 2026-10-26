package io.github.davydgky.launcher.core

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictorTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        // 2026-10-05 is a Monday.
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun prefersAppsUsedAtThisTimeOfDay() {
        val events = (5..9).flatMap { day ->
            listOf(UsageEvent("news", at(day, 7, 30)), UsageEvent("music", at(day, 18, 0)))
        }
        val morning = Predictor().rank(events, at(12, 7, 45), zone)
        val evening = Predictor().rank(events, at(12, 18, 15), zone)
        assertEquals("news", morning.first().key)
        assertEquals("music", evening.first().key)
    }

    @Test
    fun frequentUseWinsWhenTimeDoesNotDecide() {
        val events = (1..10).map { UsageEvent("chat", at(5, 8 + it)) } + UsageEvent("rare", at(5, 12))
        assertEquals("chat", Predictor().rank(events, at(6, 12), zone).first().key)
    }

    @Test
    fun oldUsageFades() {
        val events = listOf(UsageEvent("old", at(1, 9)), UsageEvent("old", at(1, 9, 5)), UsageEvent("new", at(29, 9)))
        assertEquals("new", Predictor().rank(events, at(30, 9), zone).first().key)
    }

    @Test
    fun weekendsAreSeparatedFromWorkDays() {
        // Saturday 10 Oct vs Monday-Friday 5..9 Oct, both at 10:00.
        val events = (5..9).map { UsageEvent("work", at(it, 10)) } +
            listOf(UsageEvent("hike", at(10, 10)), UsageEvent("hike", at(11, 10)), UsageEvent("hike", at(10, 10, 30)))
        assertEquals("hike", Predictor().rank(events, at(17, 10), zone).first().key)
        assertEquals("work", Predictor().rank(events, at(19, 10), zone).first().key)
    }

    @Test
    fun timeOfDayWrapsAroundMidnight() {
        assertEquals(1.0, Predictor.circularDistance(23.5, 0.5), 1e-9)
        assertEquals(12.0, Predictor.circularDistance(6.0, 18.0), 1e-9)
    }

    @Test
    fun futureEventsAreIgnored() {
        assertTrue(Predictor().rank(listOf(UsageEvent("x", at(20, 9))), at(19, 9), zone).isEmpty())
    }

    @Test
    fun engagementSaturates() {
        val now = at(10, 12)
        val scores = Engagement.scores(List(3) { UsageEvent("a", now) } + List(100) { UsageEvent("b", now) }, now)
        assertEquals(0.5f, scores.getValue("a"), 1e-4f)
        assertTrue(scores.getValue("b") > 0.95f)
    }
}
