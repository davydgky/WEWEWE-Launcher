package io.github.davydgky.launcher.island

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.davydgky.launcher.LauncherApplication
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The reminders as they open out of the island: writing one, and all of them; pictures for a look. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w411dp-h915dp-xxhdpi")
class IslandRemindersTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<LauncherApplication>()
    private val geometry = IslandGeometry(1080, 2400, 540, 70, 70, 20, 80, 2.625f)

    @Test
    fun writesANewOne() {
        val organizer = app.organizer
        compose.waitUntil(5_000) { organizer.organizer.value != null }
        var saved = false
        compose.setContent {
            Box(Modifier.size(411.dp, 600.dp).background(Color(0xFF6B8F5E)).testTag("screen")) {
                IslandRemindersScreen(organizer, geometry, adding = true, onSaved = { saved = true }, onClose = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("reminder-new-empty.png")
        compose.onNodeWithText("Was steht an?").performTextInput("Zahnarzt anrufen")
        compose.onNodeWithText("Morgen").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Ganztags").assertExists()
        save("reminder-new-filled.png")
        compose.onNodeWithText("Speichern").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(saved)
        assertTrue(organizer.organizer.value!!.todos.any { it.title == "Zahnarzt anrufen" && it.dueAtMillis != null })
    }

    @Test
    fun showsThemAll() {
        val organizer = app.organizer
        compose.waitUntil(5_000) { organizer.organizer.value != null }
        val list = organizer.organizer.value!!.lists.first().id
        val now = System.currentTimeMillis()
        organizer.addTodo(list, "Rechnung bezahlen", now - 3_600_000, hasTime = true)
        organizer.addTodo(list, "Milch kaufen", now + 2 * 3_600_000, hasTime = true)
        organizer.addTodo(list, "Paket abholen", now + 26 * 3_600_000, hasTime = false)
        organizer.addTodo(list, "Geschenk für Mama", null, hasTime = false)
        val done = organizer.addTodo(list, "Auto waschen", null, hasTime = false)
        organizer.toggleDone(done)
        compose.setContent {
            Box(Modifier.size(411.dp, 820.dp).background(Color(0xFF6B8F5E)).testTag("screen")) {
                IslandRemindersScreen(organizer, geometry, adding = false, onSaved = {}, onClose = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("reminders-all.png")
        compose.onNodeWithText("Paket abholen").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Löschen").assertExists()
        save("reminders-edit.png")
    }

    private fun save(name: String) {
        val image = compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap()
        File("build/mascot").apply { mkdirs() }.resolve(name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
