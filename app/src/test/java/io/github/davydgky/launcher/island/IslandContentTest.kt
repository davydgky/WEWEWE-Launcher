package io.github.davydgky.launcher.island

import android.app.PendingIntent
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import io.github.davydgky.launcher.LauncherApplication
import io.github.davydgky.launcher.core.LiveKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The island in every shape it takes, with taps and drags, as on a phone (minus the overlay window). */
@RunWith(RobolectricTestRunner::class)
class IslandContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<LauncherApplication>()

    private class Recorder : IslandActions {
        var searches = 0
        override fun openSearch() { searches++ }
        override fun send(intent: PendingIntent?, fallbackPackage: String?) = Unit
        override fun launchApp(packageName: String) = Unit
        override fun openUrl(url: String) = Unit
        override fun startIntent(intent: Intent) = Unit
        override fun lock() = Unit
        override fun readScreen() = Unit
        override fun quickNote() = Unit
        override fun toggleTorch() = Unit
        override fun openNotificationAccess() = Unit
        override fun openReminders() = Unit
    }

    private fun controller(actions: IslandActions = Recorder()): IslandController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return IslandController(app.island, actions, scope).apply {
            geometry = IslandGeometry(
                screenWidth = 1080, screenHeight = 2400, centerX = 540,
                holeWidth = 70, holeHeight = 70, pillTop = 20, pillHeight = 84, density = 2.625f,
            )
        }
    }

    private fun timer(key: String = "timer") = LiveItem(
        key = key, kind = LiveKind.Timer, packageName = "com.google.android.deskclock", title = "Timer", text = "",
        icon = null, color = 0, progress = null, chronometer = System.currentTimeMillis() + 60_000, countDown = true,
        updatedAt = System.currentTimeMillis(),
    )

    private fun download() = LiveItem(
        key = "download", kind = LiveKind.Progress, packageName = "com.android.chrome", title = "Datei.pdf", text = "2 MB",
        icon = null, color = 0xFF2196F3.toInt(), progress = 0.4f, updatedAt = System.currentTimeMillis(),
    )

    @Test
    fun everyShapeRendersAndTheGesturesWork() {
        val actions = Recorder()
        val controller = controller(actions)
        compose.setContent { IslandContent(controller) }
        compose.waitForIdle()

        // Compact with a second activity beside it.
        app.island.setLiveForTest(listOf(timer(), download()))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)

        // A notification and a system peek.
        app.island.showPeek(Peek(1, PeekKind.Notification, "Anna", "Bin gleich da", durationMs = 60_000))
        compose.mainClock.advanceTimeBy(1_000)
        app.island.showPeek(Peek(2, PeekKind.Charging, "Lädt", "54 %", iconRes = io.github.davydgky.launcher.R.drawable.ic_bolt, tint = -1, level = 54, durationMs = 60_000))
        compose.mainClock.advanceTimeBy(1_000)
        app.island.dismissPeek()

        // Opened, pulled further, pushed closed, pulled into the search.
        controller.expand()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { swipeDown(startY = top + 40f, endY = top + 200f) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { swipeUp(startY = top + 300f, endY = top + 40f) }
        compose.mainClock.advanceTimeBy(1_000)
        controller.collapse()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("the drag has ended", !controller.dragging)
    }

    @Test
    fun remindersNextToTheCameraAndInTheOpenedIsland() {
        val organizer = app.organizer
        organizer.start()
        // The reminders are read on a background thread.
        val deadline = System.currentTimeMillis() + 10_000
        while (organizer.organizer.value == null && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        val list = organizer.organizer.value!!.lists.first()
        val soon = organizer.addTodo(list.id, "Zahnarzt anrufen", System.currentTimeMillis() + 5 * 60_000, hasTime = true)
        organizer.addTodo(list.id, "Milch kaufen", null, hasTime = false)
        val controller = controller()
        compose.setContent { IslandContent(controller) }
        compose.mainClock.advanceTimeBy(1_000)
        controller.expand()
        compose.mainClock.advanceTimeBy(1_000)
        organizer.toggleDone(soon)
        compose.mainClock.advanceTimeBy(1_000)
        controller.collapse()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun hiddenAndBackAgain() {
        val controller = controller()
        compose.setContent { IslandContent(controller) }
        controller.expand()
        compose.mainClock.advanceTimeBy(500)
        controller.hidden = true
        controller.collapse()
        compose.mainClock.advanceTimeBy(500)
        controller.hidden = false
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }
}
