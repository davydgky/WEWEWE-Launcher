package io.github.davydgky.launcher.organizer

import org.junit.Assert.assertEquals
import org.junit.Test

class OrganizerCodecTest {

    @Test
    fun theTimeOfTheLastChangeTravelsWithTheReminders() {
        val state = OrganizerState.initial("Erinnerungen").copy(
            todos = listOf(Todo(id = 2, listId = 1, title = "Zahnarzt", dueAtMillis = 1_000L, hasTime = true)),
            nextId = 3,
            modifiedAt = 123_456L,
        )
        val decoded = OrganizerCodec.decode(OrganizerCodec.encode(state))
        assertEquals(123_456L, decoded.modifiedAt)
        assertEquals(state.todos, decoded.todos.map { it.copy(createdAtMillis = state.todos[0].createdAtMillis) })
    }

    @Test
    fun remindersSavedBeforeSharingCountAsOldest() {
        val old = OrganizerCodec.decode("""{"version":1,"nextId":2,"lists":[{"id":1,"name":"A"}],"todos":[]}""")
        assertEquals(0L, old.modifiedAt)
    }
}
