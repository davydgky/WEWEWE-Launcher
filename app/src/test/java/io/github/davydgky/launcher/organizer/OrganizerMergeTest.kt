package io.github.davydgky.launcher.organizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizerMergeTest {

    private val t0 = 1_700_000_000_000L

    private fun state(vararg todos: Todo, lists: List<TodoList> = listOf(TodoList(1, "Erinnerungen", 0, 0, uid = "list:erinnerungen")), modifiedAt: Long = 0) =
        OrganizerState(lists = lists, todos = todos.toList(), nextId = (todos.maxOfOrNull { it.id } ?: 1L) + 1, modifiedAt = modifiedAt)

    private fun todo(id: Long, uid: String, title: String, updatedAt: Long = t0, done: Boolean = false, listId: Long = 1) =
        Todo(id = id, listId = listId, title = title, uid = uid, updatedAt = updatedAt, done = done)

    @Test
    fun anEmptyNewerAppNeverWipesTheOther() {
        // The island app, fresh and changed a moment ago, against the launcher with its reminders.
        val island = state(modifiedAt = t0 + 10_000)
        val launcher = state(todo(5, "a", "Zahnarzt"), todo(6, "b", "Milch"), modifiedAt = t0)
        assertEquals(listOf("Zahnarzt", "Milch"), OrganizerMerge.merge(island, launcher, t0).todos.map { it.title })
        assertEquals(listOf("Zahnarzt", "Milch"), OrganizerMerge.merge(launcher, island, t0).todos.map { it.title })
    }

    @Test
    fun theNewerCopyOfTheSameReminderWins() {
        val here = state(todo(2, "a", "Zahnarzt", updatedAt = t0))
        val there = state(todo(9, "a", "Zahnarzt", updatedAt = t0 + 1, done = true))
        val merged = OrganizerMerge.merge(here, there, t0)
        assertEquals(1, merged.todos.size)
        assertTrue(merged.todos.single().done)
        // This app's number stays.
        assertEquals(2L, merged.todos.single().id)
    }

    @Test
    fun aRemovedReminderStaysRemovedUnlessChangedLater() {
        val before = state(todo(2, "a", "Zahnarzt"), todo(3, "b", "Milch"))
        val afterRemove = OrganizerMerge.stamp(before, before.copy(todos = before.todos.filter { it.uid != "a" }), t0 + 5)
        val other = state(todo(7, "a", "Zahnarzt", updatedAt = t0), todo(8, "b", "Milch"))
        assertEquals(listOf("Milch"), OrganizerMerge.merge(afterRemove, other, t0 + 6).todos.map { it.title })
        assertEquals(listOf("Milch"), OrganizerMerge.merge(other, afterRemove, t0 + 6).todos.map { it.title })
        // Edited there after the removal here: it comes back.
        val editedLater = state(todo(7, "a", "Zahnarzt um 9", updatedAt = t0 + 10))
        assertEquals(setOf("Milch", "Zahnarzt um 9"), OrganizerMerge.merge(afterRemove, editedLater, t0 + 11).todos.map { it.title }.toSet())
    }

    @Test
    fun remindersFromTheOtherAppGetNewNumbersAndKeepTheirList() {
        val here = state(todo(2, "a", "Hier"))
        val work = TodoList(4, "Arbeit", 1, 1, uid = "list:arbeit", updatedAt = t0)
        val there = state(todo(2, "b", "Dort", listId = 4), lists = listOf(TodoList(1, "Erinnerungen", 0, 0, uid = "list:erinnerungen"), work))
        val merged = OrganizerMerge.merge(here, there, t0)
        val dort = merged.todos.single { it.title == "Dort" }
        assertTrue("no two reminders share a number", merged.todos.map { it.id }.toSet().size == 2)
        assertEquals("Arbeit", merged.lists.single { it.id == dort.listId }.name)
        assertTrue(merged.nextId > merged.todos.maxOf { it.id })
    }

    @Test
    fun mergingTwiceChangesNothing() {
        val here = state(todo(2, "a", "Hier"))
        val there = state(todo(3, "b", "Dort"))
        val once = OrganizerMerge.merge(here, there, t0)
        assertEquals(once, OrganizerMerge.merge(once, there, t0))
    }

    @Test
    fun remindersSavedBeforeSharingHaveStableNames() {
        val json = """{"version":1,"nextId":3,"lists":[{"id":1,"name":"Erinnerungen"}],"todos":[{"id":2,"listId":1,"title":"Milch","createdAt":5}]}"""
        val a = OrganizerCodec.decode(json)
        val b = OrganizerCodec.decode(json)
        assertEquals(a.todos.single().uid, b.todos.single().uid)
        assertEquals("list:erinnerungen", a.lists.single().uid)
        assertEquals("list:erinnerungen", OrganizerState.initial("Erinnerungen").lists.single().uid)
    }
}
