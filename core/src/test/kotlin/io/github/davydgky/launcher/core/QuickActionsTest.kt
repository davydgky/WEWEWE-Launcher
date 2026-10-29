package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickActionsTest {

    private fun timer(seconds: Int) = QuickAction.Timer(seconds)

    @Test
    fun timers() {
        assertEquals(timer(300), QuickActions.parse("timer 5 min"))
        assertEquals(timer(300), QuickActions.parse("Timer für 5 Minuten"))
        assertEquals(timer(600), QuickActions.parse("10 minuten timer"))
        assertEquals(timer(90), QuickActions.parse("countdown 1:30"))
        assertEquals(timer(5400), QuickActions.parse("timer 1,5 h"))
        assertEquals(timer(5400), QuickActions.parse("timer 1 h 30 min"))
        assertEquals(timer(5400), QuickActions.parse("timer 1 stunde und 30 minuten"))
        assertEquals(timer(45), QuickActions.parse("timer 45 sek"))
        assertEquals(timer(180), QuickActions.parse("timer 3"))
        assertEquals(timer(30), QuickActions.parse("30s timer"))
    }

    @Test
    fun alarms() {
        assertEquals(QuickAction.Alarm(7, 30), QuickActions.parse("wecker 7:30"))
        assertEquals(QuickAction.Alarm(6, 0), QuickActions.parse("Wecker um 6 Uhr"))
        assertEquals(QuickAction.Alarm(6, 45), QuickActions.parse("alarm 6.45"))
        assertEquals(QuickAction.Alarm(19, 0), QuickActions.parse("wake me at 7 pm"))
        assertEquals(QuickAction.Alarm(0, 15), QuickActions.parse("alarm 12:15 am"))
        assertEquals(QuickAction.Alarm(7, 0), QuickActions.parse("weck mich um 7"))
    }

    @Test
    fun searchesStaySearches() {
        assertNull(QuickActions.parse("timer"))
        assertNull(QuickActions.parse("wecker"))
        assertNull(QuickActions.parse("timer app"))
        assertNull(QuickActions.parse("timer 5 min pizza"))
        assertNull(QuickActions.parse("alarm 25:00"))
        assertNull(QuickActions.parse("wecker app"))
        assertNull(QuickActions.parse("5 min"))
        assertNull(QuickActions.parse("timer 0"))
    }
}
