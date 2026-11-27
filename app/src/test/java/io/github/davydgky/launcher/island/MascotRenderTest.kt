package io.github.davydgky.launcher.island

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.davydgky.launcher.R
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Draws the mascot as the island and the launcher draw it, and saves the pictures for a look. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(qualifiers = "w1700dp-h400dp-mdpi")
class MascotRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val out = File("build/mascot").apply { mkdirs() }

    @Test
    fun appIconDraws() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = context.getDrawable(R.mipmap.ic_launcher)!!
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 432, 432)
        icon.draw(Canvas(bitmap))
        File(out, "icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bitmap.getPixel(216, 216) != 0)
    }

    @Test
    fun mascotDrawsInEveryMood() {
        compose.setContent {
            Row(Modifier.testTag("row").background(Color.Black)) {
                MascotMood.entries.forEach { mood ->
                    Box(Modifier.size(160.dp)) { Mascot(mood, Modifier.size(160.dp), level = 0.6f) }
                }
                Box(Modifier.size(160.dp)) { Mascot(MascotMood.Idle, Modifier.size(160.dp), box = MascotArt.FullBox) }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        val image = compose.onNodeWithTag("row").captureToImage().asAndroidBitmap()
        File(out, "moods.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The arms through a cheer and a throw, frame by frame, and holding on and climbing out. */
    @Test
    fun armsMove() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Row(Modifier.testTag("row").background(Color.Black)) {
                Box(Modifier.size(160.dp)) { Mascot(MascotMood.Happy, Modifier.size(160.dp), bounce = 1) }
                Box(Modifier.size(160.dp)) { Mascot(MascotMood.Throw, Modifier.size(160.dp), bounce = 1) }
                Box(Modifier.size(160.dp)) { Mascot(MascotMood.Idle, Modifier.size(160.dp), reach = 1f) }
                Box(Modifier.size(160.dp)) { Mascot(MascotMood.Idle, Modifier.size(160.dp), lean = 1f) }
                Box(Modifier.size(160.dp)) { Mascot(MascotMood.Calling, Modifier.size(160.dp)) }
            }
        }
        listOf(60L, 120L, 200L, 320L).forEachIndexed { i, at ->
            compose.mainClock.advanceTimeBy(if (i == 0) at else at - listOf(60L, 120L, 200L, 320L)[i - 1])
            val image = compose.onNodeWithTag("row").captureToImage().asAndroidBitmap()
            File(out, "arms-$i.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
