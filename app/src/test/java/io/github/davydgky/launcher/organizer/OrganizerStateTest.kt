package io.github.davydgky.launcher.organizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizerStateTest {

    @Test
    fun aFreshStateHasOneDefaultList() {
        val state = OrganizerState.initial("Reminders")
        assertEquals(1, state.lists.size)
        assertEquals("Reminders", state.lists.single().name)
    }

    @Test
    fun undoneTodosSortBeforeDoneOnes() {
        val listId = 1L
        val todos = listOf(
            Todo(id = 1, listId = listId, title = "a", done = true, order = 0),
            Todo(id = 2, listId = listId, title = "b", done = false, order = 1),
            Todo(id = 3, listId = listId, title = "c", done = false, order = 0),
        )
        val state = OrganizerState(lists = listOf(TodoList(listId, "List", 0, 0)), todos = todos, nextId = 4)
        assertEquals(listOf(3L, 2L, 1L), state.todosOf(listId).map { it.id })
    }

    @Test
    fun encodingAndDecodingRoundTripsEverything() {
        val state = OrganizerState(
            lists = listOf(TodoList(1, "Privat", 0, 0), TodoList(2, "Arbeit", 1, 1)),
            todos = listOf(
                Todo(id = 10, listId = 1, title = "Umzugskartons bestellen", notes = "Baumarkt", dueAtMillis = 1_700_000_000_000L, hasTime = true),
                Todo(id = 11, listId = 2, title = "Ohne Datum"),
            ),
            nextId = 12,
        )
        val decoded = OrganizerCodec.decode(OrganizerCodec.encode(state))
        assertEquals(state, decoded)
    }

    @Test
    fun aTodoWithoutADueDateHasNone() {
        val decoded = OrganizerCodec.decode(OrganizerCodec.encode(OrganizerState.initial("Reminders")))
        assertTrue(decoded.todos.isEmpty())
        assertNull(decoded.todos.firstOrNull()?.dueAtMillis)
    }
}
