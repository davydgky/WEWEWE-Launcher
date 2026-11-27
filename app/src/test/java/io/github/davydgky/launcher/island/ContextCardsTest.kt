package io.github.davydgky.launcher.island

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.davydgky.launcher.LauncherApplication
import io.github.davydgky.launcher.core.LiveKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The opened island's context cards: they render, swipe both ways, and a picture is saved for a look. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class ContextCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<LauncherApplication>()

    private object NoActions : IslandActions {
        override fun openSearch() = Unit
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
        override fun perform(toggle: QuickToggle) = Unit
    }

    @Test
    fun cardsRenderAndSwipe() {
        val controller = IslandController(app.island, NoActions, CoroutineScope(SupervisorJob() + Dispatchers.Main)).apply {
            geometry = IslandGeometry(1080, 2400, 540, 70, 70, 20, 80, 2.625f)
        }
        app.island.setLiveForTest(
            listOf(
                LiveItem("timer", LiveKind.Timer, "com.google.android.deskclock", "Timer", "Nudeln", null, 0, null,
                    chronometer = System.currentTimeMillis() + 300_000, countDown = true, updatedAt = System.currentTimeMillis()),
            ),
        )
        app.island.addToShelf("https://example.com/rezept")
        compose.setContent {
            Box(Modifier.size(411.dp, 760.dp).background(Color(0xFF6B8F5E)).testTag("screen")) { IslandContent(controller) }
        }
        controller.expand()
        compose.mainClock.advanceTimeBy(1_500)
        save("cards-1.png")
        // On the cards themselves: a swipe beside the opened island closes it.
        compose.onNodeWithTag("screen").performTouchInput {
            val y = top + 70.dp.toPx()
            swipe(androidx.compose.ui.geometry.Offset(centerX + 150f, y), androidx.compose.ui.geometry.Offset(centerX - 250f, y), 300)
        }
        compose.mainClock.advanceTimeBy(1_500)
        save("cards-2.png")
        compose.onNodeWithTag("screen").performTouchInput {
            val y = top + 70.dp.toPx()
            swipe(androidx.compose.ui.geometry.Offset(centerX - 150f, y), androidx.compose.ui.geometry.Offset(centerX + 250f, y), 300)
        }
        compose.mainClock.advanceTimeBy(1_500)
        org.junit.Assert.assertTrue("the island stays open while the cards are swiped", controller.expanded)
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    @Test
    fun aNotificationIsThrownIn() {
        val controller = IslandController(app.island, NoActions, CoroutineScope(SupervisorJob() + Dispatchers.Main)).apply {
            geometry = IslandGeometry(1080, 2400, 540, 70, 70, 20, 80, 2.625f)
        }
        compose.setContent {
            Box(Modifier.size(411.dp, 300.dp).background(Color(0xFF6B8F5E)).testTag("screen")) { IslandContent(controller) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("idle.png")
        // The picture mid-throw: the clock only moves when told.
        compose.mainClock.autoAdvance = false
        app.island.showPeek(
            Peek(
                7, PeekKind.Notification, "Carlotta Faßbender", "Das wäre verrückt haha, lass uns das am Wochenende machen!",
                source = "WhatsApp", durationMs = 60_000,
            ),
        )
        compose.mainClock.advanceTimeBy(500)
        save("peek-1-throw.png")
        compose.mainClock.advanceTimeBy(1_500)
        save("peek-2-landed.png")
    }

    @Test
    fun theIslandMakesRoomForSystemChips() {
        val controller = IslandController(app.island, NoActions, CoroutineScope(SupervisorJob() + Dispatchers.Main)).apply {
            geometry = IslandGeometry(1080, 2400, 540, 70, 70, 20, 80, 2.625f)
        }
        fun call(chip: Boolean) = LiveItem("call", LiveKind.Call, "com.google.android.dialer", "Mama", "", null, 0, null,
            chronometer = System.currentTimeMillis() - 323_000, updatedAt = System.currentTimeMillis(), systemChip = chip)
        fun timer(chip: Boolean) = LiveItem("timer", LiveKind.Timer, "com.google.android.deskclock", "Timer", "", null, 0, null,
            chronometer = System.currentTimeMillis() + 300_000, countDown = true, updatedAt = System.currentTimeMillis(), systemChip = chip)
        // Without chips of Android's own: the call beside the camera, its time in full, the timer beside it.
        app.island.setLiveForTest(listOf(call(chip = false), timer(chip = false)))
        compose.setContent {
            Box(Modifier.size(411.dp, 200.dp).background(Color(0xFF6B8F5E)).testTag("screen")) { IslandContent(controller) }
        }
        compose.mainClock.advanceTimeBy(1_500)
        save("call-island.png")
        val wide = controller.windowRequest!!.width
        // Android shows both as chips itself: the island leaves them the room, only the ring stays.
        app.island.setLiveForTest(listOf(call(chip = true), timer(chip = true)))
        compose.mainClock.advanceTimeBy(1_500)
        save("call-system-chips.png")
        org.junit.Assert.assertTrue("the island is small beside system chips", controller.windowRequest!!.width < wide / 2)
        app.island.setLiveForTest(emptyList())
        compose.mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun remindersHaveAPlaceOfTheirOwn() {
        val controller = IslandController(app.island, NoActions, CoroutineScope(SupervisorJob() + Dispatchers.Main)).apply {
            geometry = IslandGeometry(1080, 2400, 540, 70, 70, 20, 80, 2.625f)
        }
        val organizer = app.organizer
        compose.waitUntil(5_000) { organizer.organizer.value != null }
        val list = organizer.organizer.value!!.lists.first().id
        organizer.addTodo(list, "Milch kaufen", System.currentTimeMillis() + 3_600_000, hasTime = true)
        organizer.addTodo(list, "Paket abholen", null, hasTime = false)
        app.island.setLiveForTest(
            listOf(
                LiveItem("timer", LiveKind.Timer, "com.google.android.deskclock", "Uhr", "", null, 0, null,
                    chronometer = System.currentTimeMillis() + 300_000, countDown = true, updatedAt = System.currentTimeMillis()),
            ),
        )
        compose.setContent {
            Box(Modifier.size(411.dp, 760.dp).background(Color(0xFF6B8F5E)).testTag("screen")) { IslandContent(controller) }
        }
        controller.expand()
        compose.mainClock.advanceTimeBy(1_500)
        save("reminders-open.png")
        compose.onNodeWithText("Milch kaufen").assertExists()
        compose.onNodeWithText("Paket abholen").assertExists()
        app.island.setLiveForTest(emptyList())
        controller.collapse()
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun save(name: String) {
        val image = compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap()
        File("build/mascot").apply { mkdirs() }.resolve(name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
