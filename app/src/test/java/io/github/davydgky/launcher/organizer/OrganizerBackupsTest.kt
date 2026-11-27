package io.github.davydgky.launcher.organizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** Reminders are never lost for good: an unreadable file is kept, every day is saved, a copy restores. */
@RunWith(RobolectricTestRunner::class)
class OrganizerBackupsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun idle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!until() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
    }

    @Test
    fun anUnreadableFileIsKeptAndRemindersComeBackFromACopy() {
        File(context.filesDir, "organizer.json").writeText("{ not json")
        val repository = OrganizerRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        repository.start()
        idle { repository.organizer.value != null }
        assertTrue("the unreadable file is kept", repository.backups.list().any { it.kind == OrganizerBackups.Kind.Unreadable })

        val list = repository.organizer.value!!.lists.first().id
        repository.addTodo(list, "Zahnarzt", null, false)
        repository.addTodo(list, "Milch", null, false)
        idle { repository.backups.list().any { it.kind == OrganizerBackups.Kind.Daily && it.reminders == 2 } }
        val daily = repository.backups.list().first { it.kind == OrganizerBackups.Kind.Daily }
        assertEquals(2, daily.openReminders)

        // Both removed, then brought back from the day's copy.
        repository.organizer.value!!.todos.map { it.id }.forEach(repository::removeTodo)
        assertEquals(0, repository.organizer.value!!.todos.size)
        assertTrue(repository.restore(daily))
        assertEquals(setOf("Zahnarzt", "Milch"), repository.organizer.value!!.todos.map { it.title }.toSet())
    }
}
