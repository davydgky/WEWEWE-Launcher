package io.github.davydgky.launcher.organizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderAlarmsTest {

    private val now = 1_000_000_000L

    private fun todo(id: Long, due: Long?, hasTime: Boolean = true, done: Boolean = false) =
        Todo(id = id, listId = 1, title = "T$id", dueAtMillis = due, hasTime = hasTime, done = done)

    @Test
    fun theNextAlarmIsTheEarliestOpenReminderWithATimeStillToCome() {
        val todos = listOf(
            todo(1, now - 60_000),
            todo(2, now + 120_000),
            todo(3, now + 60_000, done = true),
            todo(4, now + 30_000, hasTime = false),
            todo(5, now + 300_000),
        )
        assertEquals(now + 120_000, ReminderAlarms.nextDue(todos, now))
        assertNull(ReminderAlarms.nextDue(listOf(todo(1, now - 1)), now))
    }

    @Test
    fun anAlarmAnnouncesWhatIsDueThenAlsoIfItCameALittleLate() {
        val todos = listOf(todo(1, now), todo(2, now - 90_000), todo(3, now - 5 * 60_000), todo(4, now + 60_000), todo(5, now, done = true))
        assertEquals(listOf(1L, 2L), ReminderAlarms.dueAt(todos, now).map { it.id })
    }
}
