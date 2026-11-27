package io.github.davydgky.launcher.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The wewewe.studio mascot (concept 2, the monoline W arch): a lavender dome with two eyes on a W body
 * in lilac, lavender and rosé, drawn from the master SVG's paths in its own 512 × 512 coordinates.
 * The app icon is the same drawing (res/drawable ic_launcher_foreground of the island app).
 */
object MascotArt {
    val Charcoal = Color(0xFF2D2B38)
    val Mint = Color(0xFFC9F7E4)
    val Dome = Color(0xFFDDD0F8)
    val LeftArm = Color(0xFFC5B4F4)
    val Belly = Color(0xFFD3B1EC)
    val RightArm = Color(0xFFF9A8B4)

    /** The full drawing (the app icon's square) and the tight crop around the figure for the island. */
    val FullBox = Rect(0f, 0f, 512f, 512f)
    val IslandBox = Rect(76f, 92f, 76f + 360f, 92f + 330f)

    /** Anchor of the breathing: bottom centre of the figure. */
    val BreathPivot = Offset(256f, 402f)

    private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

    val silhouette = path(
        "M 184 286 C 208 240, 272 240, 298 294 L 342 224 A 42.2 42.2 0 1 1 410 274 C 386 307, 372 344, 348 376 " +
            "C 326 405, 284 412, 256 368 C 230 416, 166 412, 128 334 C 102 281, 92 224, 132 218 C 164 214, 182 246, 184 286 Z",
    )
    val leftArm = path("M 80 190 L 195 190 L 184 286 C 174 314, 144 326, 121 318 L 80 310 Z")
    val rightArm = path("M 298 294 C 286 322, 272 346, 256 368 L 256 440 L 450 440 L 450 180 L 320 180 Z")
    val leftArmFold = path("M 184 286 C 174 314, 144 326, 121 318")
    val dome = path("M 150 278 C 144 166, 190 108, 256 108 C 322 108, 360 156, 354 274 L 256 324 Z")
    val blinkLeft = path("M 225 192 Q 236 198 247 192")
    val blinkRight = path("M 285 190 Q 296 196 307 190")
    val happyLeft = path("M 225 194 Q 236 182 247 194")
    val happyRight = path("M 285 192 Q 296 180 307 192")
}

/** What the mascot is doing. */
enum class MascotMood {
    /** Breathes, blinks now and then, glances aside. */
    Idle,

    /** Bobs along with the music. */
    Music,

    /** Glad: a notification came, the island opened. Happy eyes for a moment. */
    Happy,
}

private enum class Eyes { Open, Blink, Happy }

/**
 * The animated mascot. [bounce] starts the squash and stretch each time it changes (a notification, the
 * island opening); [box] is the part of the drawing shown ([MascotArt.IslandBox] in the island). All
 * motion is slow and springy, as the brand asks: breathing over 3.6 s, a blink every 4 to 7 s.
 */
@Composable
fun Mascot(
    mood: MascotMood,
    modifier: Modifier = Modifier,
    bounce: Any? = null,
    box: Rect = MascotArt.IslandBox,
) {
    val idle = rememberInfiniteTransition(label = "mascot")
    val breath by idle.animateFloat(0.97f, 1.02f, infiniteRepeatable(tween(1_800, easing = EaseInOut), RepeatMode.Reverse), label = "breath")
    // About 115 beats a minute: a gentle bob, not a dance.
    val bob by idle.animateFloat(-1f, 1f, infiniteRepeatable(tween(520, easing = EaseInOut), RepeatMode.Reverse), label = "bob")

    var eyes by remember { mutableStateOf(Eyes.Open) }
    val look = remember { Animatable(0f) }
    val squashX = remember { Animatable(1f) }
    val squashY = remember { Animatable(1f) }
    val lift = remember { Animatable(0f) }

    // Blinks every 4 to 7 seconds, now and then twice.
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(4_000, 7_000))
            if (eyes != Eyes.Open) continue
            eyes = Eyes.Blink
            delay(120)
            eyes = Eyes.Open
            if (Random.nextFloat() < 0.25f) {
                delay(160)
                eyes = Eyes.Blink
                delay(120)
                eyes = Eyes.Open
            }
        }
    }
    // Glances aside (towards the camera or a notification) and back.
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(5_000, 11_000))
            look.animateTo(if (Random.nextBoolean()) 6f else -6f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
            delay(Random.nextLong(900, 1_800))
            look.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
        }
    }
    // Squash and stretch, the head lifting, happy eyes: when a notification comes or the island opens.
    LaunchedEffect(bounce) {
        if (bounce == null) return@LaunchedEffect
        launch {
            eyes = Eyes.Happy
            delay(600)
            eyes = Eyes.Open
        }
        launch {
            lift.animateTo(-8f, tween(120))
            lift.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 300f))
        }
        launch { squashX.animateTo(1.08f, tween(90)) }
        squashY.animateTo(0.90f, tween(90))
        launch { squashX.animateTo(0.95f, tween(160)) }
        squashY.animateTo(1.07f, tween(160))
        launch { squashX.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
        squashY.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
    }
    val shownEyes = if (mood == MascotMood.Happy && eyes == Eyes.Open && bounce == null) Eyes.Happy else eyes

    Canvas(modifier) {
        val scale = minOf(size.width / box.width, size.height / box.height)
        val dx = (size.width - box.width * scale) / 2f - box.left * scale
        val dy = (size.height - box.height * scale) / 2f - box.top * scale
        translate(dx, dy) {
            scale(scale, scale, pivot = Offset.Zero) {
                val pivot = MascotArt.BreathPivot
                val music = mood == MascotMood.Music
                translate(0f, if (music) bob * 4f else 0f) {
                    rotate(if (music) bob * 3f else 0f, pivot) {
                        scale(squashX.value, squashY.value * if (music) 1f else breath, pivot) {
                            drawMascot(shownEyes, look.value, lift.value)
                        }
                    }
                }
            }
        }
    }
}

private val Line = Stroke(width = 13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val EyeLine = Stroke(width = 8f, cap = StrokeCap.Round)

/** The master SVG, in its order: head (dome, eyes), then the W body over it. */
private fun DrawScope.drawMascot(eyes: Eyes, look: Float, lift: Float) {
    translate(0f, lift) {
        drawPath(MascotArt.dome, MascotArt.Dome)
        drawPath(MascotArt.dome, MascotArt.Charcoal, style = Line)
        translate(look, 0f) {
            when (eyes) {
                Eyes.Open -> {
                    drawCircle(MascotArt.Charcoal, 10.5f, Offset(236f, 192f))
                    drawCircle(MascotArt.Charcoal, 10.5f, Offset(296f, 190f))
                }
                Eyes.Blink -> {
                    drawPath(MascotArt.blinkLeft, MascotArt.Charcoal, style = EyeLine)
                    drawPath(MascotArt.blinkRight, MascotArt.Charcoal, style = EyeLine)
                }
                Eyes.Happy -> {
                    drawPath(MascotArt.happyLeft, MascotArt.Charcoal, style = EyeLine)
                    drawPath(MascotArt.happyRight, MascotArt.Charcoal, style = EyeLine)
                }
            }
        }
    }
    // The pastel areas, clipped to the W's silhouette, with no lines between lilac and rosé.
    clipPath(MascotArt.silhouette) {
        drawRect(MascotArt.Belly, topLeft = Offset(70f, 180f), size = androidx.compose.ui.geometry.Size(380f, 260f))
        drawPath(MascotArt.leftArm, MascotArt.LeftArm)
        drawPath(MascotArt.rightArm, MascotArt.RightArm)
    }
    drawPath(MascotArt.leftArmFold, MascotArt.Charcoal, style = Line)
    drawPath(MascotArt.silhouette, MascotArt.Charcoal, style = Line)
}

/** Pastel sound waves for music beside the camera, in the mascot's colours. */
val MascotWaveColors = listOf(MascotArt.LeftArm, MascotArt.RightArm, MascotArt.Mint, MascotArt.LeftArm)
