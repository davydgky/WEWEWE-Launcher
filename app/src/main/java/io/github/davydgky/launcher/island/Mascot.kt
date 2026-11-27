package io.github.davydgky.launcher.island

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.geometry.Size
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The wewewe.studio mascot (concept 2, the monoline W arch): a lavender dome with two eyes on a W body
 * in lilac, lavender and rosé, drawn from the master SVG's paths in its own 512 × 512 coordinates.
 * The app icon is the same drawing (res/drawable ic_launcher_foreground of the island app).
 *
 * The W's outer strokes are its arms: they are drawn from the master paths with their ends turned about
 * the shoulders, so the outline stays one line while they wave, cheer, throw or droop.
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

    /** The arms turn about these points, low in the W where each outer stroke rises. */
    val LeftShoulder = Offset(156f, 318f)
    val RightShoulder = Offset(322f, 330f)

    private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

    val silhouette = path(
        "M 184 286 C 208 240, 272 240, 298 294 L 342 224 A 42.2 42.2 0 1 1 410 274 C 386 307, 372 344, 348 376 " +
            "C 326 405, 284 412, 256 368 C 230 416, 166 412, 128 334 C 102 281, 92 224, 132 218 C 164 214, 182 246, 184 286 Z",
    )

    /** The pastel areas, a little larger than in the master so they still fill the arms when these turn. */
    val leftArm = path("M 50 140 L 200 140 L 184 286 C 174 314, 144 326, 121 318 L 50 318 Z")
    val rightArm = path("M 298 294 C 286 322, 272 346, 256 368 L 256 470 L 480 470 L 480 130 L 332 130 Z")
    val leftArmFold = path("M 184 286 C 174 314, 144 326, 121 318")
    val dome = path("M 150 278 C 144 166, 190 108, 256 108 C 322 108, 360 156, 354 274 L 256 324 Z")
    val blinkLeft = path("M 225 192 Q 236 198 247 192")
    val blinkRight = path("M 285 190 Q 296 196 307 190")
    val happyLeft = path("M 225 194 Q 236 182 247 194")
    val happyRight = path("M 285 192 Q 296 180 307 192")
    val sleepLeft = path("M 224 190 Q 236 202 248 190")
    val sleepRight = path("M 284 188 Q 296 200 308 188")
    val sadBrowLeft = path("M 222 176 L 246 166")
    val sadBrowRight = path("M 286 164 L 310 174")

    /**
     * The W with its arms turned by [left] and [right] degrees about the shoulders; positive spreads an
     * arm outwards and down, negative lifts it up towards the head. The arm's tip turns fully, the points
     * along it less and less towards the body, so the line bends instead of breaking.
     */
    fun silhouette(left: Float, right: Float): Path {
        if (left == 0f && right == 0f) return silhouette
        // The left arm spreads anticlockwise, the right one clockwise (y points down).
        fun l(x: Float, y: Float, w: Float = 1f) = turn(x, y, LeftShoulder, -left * w)
        fun r(x: Float, y: Float, w: Float = 1f) = turn(x, y, RightShoulder, right * w)
        return Path().apply {
            moveTo(184f, 286f)
            cubicTo(208f, 240f, 272f, 240f, 298f, 294f)
            r(342f, 224f).let { lineTo(it.x, it.y) }
            // The round hand: the half circle on the master's arc, turned with the arm.
            val centre = r(376f, 249f)
            arcTo(Rect(centre, 42.2f), 216.33f + right, 180f, false)
            val c1 = r(386f, 307f, 0.6f)
            val c2 = r(372f, 344f, 0.2f)
            cubicTo(c1.x, c1.y, c2.x, c2.y, 348f, 376f)
            cubicTo(326f, 405f, 284f, 412f, 256f, 368f)
            cubicTo(230f, 416f, 166f, 412f, 128f, 334f)
            val d1 = l(102f, 281f, 0.35f)
            val d2 = l(92f, 224f)
            val d3 = l(132f, 218f)
            cubicTo(d1.x, d1.y, d2.x, d2.y, d3.x, d3.y)
            val e1 = l(164f, 214f)
            val e2 = l(182f, 246f, 0.45f)
            cubicTo(e1.x, e1.y, e2.x, e2.y, 184f, 286f)
            close()
        }
    }

    private fun turn(x: Float, y: Float, pivot: Offset, degrees: Float): Offset {
        if (degrees == 0f) return Offset(x, y)
        val a = degrees * PI.toFloat() / 180f
        val dx = x - pivot.x
        val dy = y - pivot.y
        return Offset(pivot.x + dx * cos(a) - dy * sin(a), pivot.y + dx * sin(a) + dy * cos(a))
    }
}

/** What the mascot is doing; each has its pose, its eyes and, for a state of the phone, its colours. */
enum class MascotMood {
    /** Breathes, blinks, glances aside; now and then waves or stretches. */
    Idle,

    /** Bobs along with the music, pumping its arms to the beat. */
    Music,

    /** Glad: the island opened, something was saved. Cheers with both arms. */
    Happy,

    /** A notification: it winds up and throws the card into the island. */
    Throw,

    /** Charging: mint fills it from the feet up to the battery's level while it flexes. */
    Charging,

    /** The battery is low: rosé-red, arms hanging, sad eyes. */
    Low,

    /** Do not disturb: dimmed to dusky lilac, eyes shut, little z's rising. */
    Sleepy,

    /** A call: mint-green, one arm at its ear. */
    Calling,

    /** A reminder is due: yellow, waving both arms over its head, eyes wide. */
    Alert,
}

private enum class Eyes { Open, Blink, Happy, Closed, Sad, Wide }

/** The four pastel areas of the figure. */
private data class Palette(val dome: Color, val left: Color, val belly: Color, val right: Color)

private val Brand = Palette(MascotArt.Dome, MascotArt.LeftArm, MascotArt.Belly, MascotArt.RightArm)

private fun paletteOf(mood: MascotMood) = when (mood) {
    MascotMood.Low -> Palette(Color(0xFFF9D3D9), Color(0xFFF4B0BA), Color(0xFFF0B8C2), Color(0xFFFF8E99))
    MascotMood.Sleepy -> Palette(Color(0xFFC9C6E6), Color(0xFFA7A2D6), Color(0xFFB4ABD6), Color(0xFFC2B0D8))
    MascotMood.Calling -> Palette(Color(0xFFD6F6E6), Color(0xFFA9E8C8), Color(0xFFBEEFD6), Color(0xFF8EDDB6))
    MascotMood.Alert -> Palette(Color(0xFFFFF2C6), Color(0xFFFFE08C), Color(0xFFFFE9A8), Color(0xFFFFC58C))
    else -> Brand
}

/** Where the arms rest in each mood, in degrees (positive spread out and down, negative up). */
private fun restOf(mood: MascotMood): Pair<Float, Float> = when (mood) {
    MascotMood.Low -> 26f to 26f
    MascotMood.Sleepy -> 10f to 10f
    MascotMood.Calling -> 4f to -10f
    MascotMood.Alert -> 6f to 6f
    MascotMood.Charging -> 3f to 3f
    MascotMood.Music -> 6f to 6f
    else -> 0f to 0f
}

/** The charge: a fresher mint than the brand's, so it reads as filling up. */
private val Fill = Color(0xFF8FE8C2)
private val Empty = Color(0xFFFF6B6B)

/**
 * The animated mascot. [bounce] starts a gesture each time it changes: a cheer, or the throw in
 * [MascotMood.Throw]; [box] is the part of the drawing shown ([MascotArt.IslandBox] in the island).
 * [level] (0 to 1) is the battery's for [MascotMood.Charging] and [MascotMood.Low]. [reach] lifts both
 * arms as if holding on (0 to 1), [lean] stretches the left one out as if climbing out (0 to 1). All
 * motion is slow and springy, as the brand asks.
 */
@Composable
fun Mascot(
    mood: MascotMood,
    modifier: Modifier = Modifier,
    bounce: Any? = null,
    box: Rect = MascotArt.IslandBox,
    level: Float? = null,
    reach: Float = 0f,
    lean: Float = 0f,
) {
    val idle = rememberInfiniteTransition(label = "mascot")
    val slowBreath = mood == MascotMood.Sleepy || mood == MascotMood.Low
    val breath by idle.animateFloat(0.97f, 1.02f, infiniteRepeatable(tween(if (slowBreath) 2_600 else 1_800, easing = EaseInOut), RepeatMode.Reverse), label = "breath")
    // About 115 beats a minute: a gentle bob, not a dance.
    val bob by idle.animateFloat(-1f, 1f, infiniteRepeatable(tween(520, easing = EaseInOut), RepeatMode.Reverse), label = "bob")
    val flex by idle.animateFloat(-1f, 1f, infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse), label = "flex")
    val wave by idle.animateFloat(-1f, 1f, infiniteRepeatable(tween(240, easing = EaseInOut), RepeatMode.Reverse), label = "wave")
    val drift by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "drift")

    // The colours and the rest pose follow the mood smoothly.
    val palette = paletteOf(mood)
    val dome by animateColorAsState(palette.dome, tween(500), label = "dome")
    val left by animateColorAsState(palette.left, tween(500), label = "left")
    val belly by animateColorAsState(palette.belly, tween(500), label = "belly")
    val right by animateColorAsState(palette.right, tween(500), label = "right")
    val rest = restOf(mood)
    val restLeft by animateFloatAsState(rest.first, spring(dampingRatio = 0.55f, stiffness = 160f), label = "restLeft")
    val restRight by animateFloatAsState(rest.second, spring(dampingRatio = 0.55f, stiffness = 160f), label = "restRight")
    val beat by animateFloatAsState(if (mood == MascotMood.Music) 1f else 0f, tween(400), label = "beat")
    val power by animateFloatAsState(if (mood == MascotMood.Charging) 1f else 0f, tween(400), label = "power")
    val waving by animateFloatAsState(if (mood == MascotMood.Alert) 1f else 0f, tween(300), label = "waving")
    val fill by animateFloatAsState(
        when (mood) {
            MascotMood.Charging -> (level ?: 0.5f).coerceIn(0.08f, 1f)
            MascotMood.Low -> (level ?: 0.1f).coerceIn(0.06f, 0.3f)
            else -> 0f
        },
        spring(dampingRatio = 0.7f, stiffness = 60f),
        label = "fill",
    )

    var eyes by remember { mutableStateOf(Eyes.Open) }
    val look = remember { Animatable(0f) }
    val squashX = remember { Animatable(1f) }
    val squashY = remember { Animatable(1f) }
    val lift = remember { Animatable(0f) }
    // One-off gestures on top of the rest pose.
    val gestureLeft = remember { Animatable(0f) }
    val gestureRight = remember { Animatable(0f) }

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
    // At rest it does something now and then: waves at the camera, or stretches.
    LaunchedEffect(mood) {
        if (mood != MascotMood.Idle) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(7_000, 14_000))
            if (Random.nextFloat() < 0.6f) {
                // A wave of the right hand: out to the side, three swings, back.
                gestureRight.animateTo(22f, spring(dampingRatio = 0.6f, stiffness = 300f))
                repeat(3) {
                    gestureRight.animateTo(4f, tween(150, easing = EaseInOut))
                    gestureRight.animateTo(24f, tween(150, easing = EaseInOut))
                }
                gestureRight.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 200f))
            } else {
                // A stretch: both arms out, the body long, then a little shake back.
                launch { gestureLeft.animateTo(24f, tween(380, easing = EaseInOut)) }
                launch { squashY.animateTo(1.06f, tween(380, easing = EaseInOut)) }
                gestureRight.animateTo(24f, tween(380, easing = EaseInOut))
                delay(500)
                launch { gestureLeft.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 220f)) }
                launch { squashY.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 220f)) }
                gestureRight.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 220f))
            }
        }
    }
    // Squash and stretch, the head lifting, a gesture: when a notification comes or the island opens.
    LaunchedEffect(bounce) {
        if (bounce == null) return@LaunchedEffect
        if (mood == MascotMood.Throw) {
            // Eyes wide, winds the right arm in, then flings it out as the card leaves its hand.
            eyes = Eyes.Wide
            launch { squashX.animateTo(0.94f, tween(110)) }
            gestureRight.animateTo(-12f, tween(110, easing = EaseInOut))
            launch { squashX.animateTo(1.04f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
            launch { lift.animateTo(-6f, tween(90)) }
            gestureRight.animateTo(34f, spring(dampingRatio = 0.5f, stiffness = 900f))
            launch { squashX.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
            eyes = Eyes.Happy
            launch { lift.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
            delay(260)
            gestureRight.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 180f))
            delay(250)
            eyes = Eyes.Open
            return@LaunchedEffect
        }
        launch {
            eyes = Eyes.Happy
            delay(700)
            eyes = Eyes.Open
        }
        launch {
            lift.animateTo(-8f, tween(120))
            lift.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 300f))
        }
        // Both arms flap in a cheer, out and in, then settle.
        launch {
            gestureLeft.animateTo(22f, spring(dampingRatio = 0.5f, stiffness = 500f))
            repeat(2) {
                gestureLeft.animateTo(-6f, tween(120))
                gestureLeft.animateTo(20f, tween(120))
            }
            gestureLeft.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 200f))
        }
        launch {
            gestureRight.animateTo(22f, spring(dampingRatio = 0.5f, stiffness = 500f))
            repeat(2) {
                gestureRight.animateTo(-6f, tween(120))
                gestureRight.animateTo(20f, tween(120))
            }
            gestureRight.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 200f))
        }
        launch { squashX.animateTo(1.08f, tween(90)) }
        squashY.animateTo(0.90f, tween(90))
        launch { squashX.animateTo(0.95f, tween(160)) }
        squashY.animateTo(1.07f, tween(160))
        launch { squashX.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
        squashY.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
    }
    val shownEyes = when {
        eyes == Eyes.Wide || eyes == Eyes.Happy -> eyes
        reach > 0.4f -> Eyes.Wide
        mood == MascotMood.Sleepy -> Eyes.Closed
        mood == MascotMood.Low -> if (eyes == Eyes.Blink) Eyes.Blink else Eyes.Sad
        mood == MascotMood.Alert -> Eyes.Wide
        mood == MascotMood.Happy && bounce == null -> Eyes.Happy
        else -> eyes
    }

    Canvas(modifier) {
        val scale = minOf(size.width / box.width, size.height / box.height)
        val dx = (size.width - box.width * scale) / 2f - box.left * scale
        val dy = (size.height - box.height * scale) / 2f - box.top * scale
        // The arms: the rest pose, the one-off gesture, and what the mood keeps doing.
        val sway = (breath - 0.995f) * 60f
        val armLeft = restLeft + gestureLeft.value + sway * 0.4f + beat * 12f * bob + power * 7f * flex + waving * 14f * wave -
            reach * 10f + lean * 34f
        val armRight = restRight + gestureRight.value + sway * 0.4f - beat * 12f * bob + power * 7f * flex - waving * 14f * wave -
            reach * 12f
        translate(dx, dy) {
            scale(scale, scale, pivot = Offset.Zero) {
                val pivot = MascotArt.BreathPivot
                val music = mood == MascotMood.Music
                translate(0f, if (music) bob * 4f else 0f) {
                    rotate(if (music) bob * 3f else 0f, pivot) {
                        scale(squashX.value, squashY.value * if (music) 1f else breath, pivot) {
                            drawMascot(
                                Palette(dome, left, belly, right),
                                shownEyes,
                                look.value - lean * 6f,
                                lift.value,
                                armLeft,
                                armRight,
                                fill,
                                if (mood == MascotMood.Low) Empty else Fill,
                                drift,
                            )
                            if (mood == MascotMood.Sleepy) drawSleep(drift)
                        }
                    }
                }
            }
        }
    }
}

private val Line = Stroke(width = 13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val EyeLine = Stroke(width = 8f, cap = StrokeCap.Round)
private val SleepZ = Color(0xFFD9D4F4)
private val Zed = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** The master SVG, in its order: head (dome, eyes), then the W body over it. */
private fun DrawScope.drawMascot(
    palette: Palette,
    eyes: Eyes,
    look: Float,
    lift: Float,
    armLeft: Float,
    armRight: Float,
    fill: Float,
    fillColor: Color,
    drift: Float,
) {
    translate(0f, lift) {
        drawPath(MascotArt.dome, palette.dome)
        drawPath(MascotArt.dome, MascotArt.Charcoal, style = Line)
        translate(look, 0f) {
            when (eyes) {
                Eyes.Open -> {
                    drawCircle(MascotArt.Charcoal, 10.5f, Offset(236f, 192f))
                    drawCircle(MascotArt.Charcoal, 10.5f, Offset(296f, 190f))
                }
                Eyes.Wide -> {
                    drawCircle(MascotArt.Charcoal, 13.5f, Offset(236f, 190f))
                    drawCircle(MascotArt.Charcoal, 13.5f, Offset(296f, 188f))
                    drawCircle(Color.White, 3.6f, Offset(240f, 185f))
                    drawCircle(Color.White, 3.6f, Offset(300f, 183f))
                }
                Eyes.Blink -> {
                    drawPath(MascotArt.blinkLeft, MascotArt.Charcoal, style = EyeLine)
                    drawPath(MascotArt.blinkRight, MascotArt.Charcoal, style = EyeLine)
                }
                Eyes.Happy -> {
                    drawPath(MascotArt.happyLeft, MascotArt.Charcoal, style = EyeLine)
                    drawPath(MascotArt.happyRight, MascotArt.Charcoal, style = EyeLine)
                }
                Eyes.Closed -> {
                    drawPath(MascotArt.sleepLeft, MascotArt.Charcoal, style = EyeLine)
                    drawPath(MascotArt.sleepRight, MascotArt.Charcoal, style = EyeLine)
                }
                Eyes.Sad -> {
                    drawCircle(MascotArt.Charcoal, 9.5f, Offset(236f, 196f))
                    drawCircle(MascotArt.Charcoal, 9.5f, Offset(296f, 194f))
                    drawPath(MascotArt.sadBrowLeft, MascotArt.Charcoal, style = EyeLine)
                    drawPath(MascotArt.sadBrowRight, MascotArt.Charcoal, style = EyeLine)
                }
            }
        }
    }
    val body = MascotArt.silhouette(armLeft, armRight)
    // The pastel areas, clipped to the W's silhouette, with no lines between lilac and rosé. Each arm's
    // colour turns with its arm.
    clipPath(body) {
        drawRect(palette.belly, topLeft = Offset(40f, 120f), size = Size(450f, 360f))
        rotate(-armLeft, MascotArt.LeftShoulder) { drawPath(MascotArt.leftArm, palette.left) }
        rotate(armRight, MascotArt.RightShoulder) { drawPath(MascotArt.rightArm, palette.right) }
        if (fill > 0f) drawLevel(fill, fillColor, drift)
    }
    drawPath(MascotArt.leftArmFold, MascotArt.Charcoal, style = Line)
    drawPath(body, MascotArt.Charcoal, style = Line)
}

/** The battery's level as a colour rising in the body from the feet, its surface gently waving. */
private fun DrawScope.drawLevel(level: Float, color: Color, drift: Float) {
    val bottom = 418f
    val top = 200f
    val surface = bottom - (bottom - top) * level
    val phase = drift * 2f * PI.toFloat()
    val wave = Path().apply {
        moveTo(40f, bottom + 20f)
        lineTo(40f, surface)
        var x = 40f
        while (x <= 480f) {
            lineTo(x, surface + sin(x / 38f + phase) * 6f)
            x += 12f
        }
        lineTo(480f, bottom + 20f)
        close()
    }
    drawPath(wave, color.copy(alpha = 0.88f))
}

/** Two little z's rising beside the head and fading, while it sleeps. */
private fun DrawScope.drawSleep(drift: Float) {
    for (i in 0..1) {
        val t = (drift + i * 0.5f) % 1f
        val x = 368f + t * 30f
        val y = 178f - t * 70f
        val size = 18f + t * 8f
        val alpha = when {
            t < 0.2f -> t / 0.2f
            t > 0.75f -> (1f - t) / 0.25f
            else -> 1f
        }
        val z = Path().apply {
            moveTo(x, y)
            lineTo(x + size, y)
            lineTo(x, y + size)
            lineTo(x + size, y + size)
        }
        // Light, as the island around it is black.
        drawPath(z, SleepZ.copy(alpha = alpha), style = Zed)
    }
}

/** Pastel sound waves for music beside the camera, in the mascot's colours. */
val MascotWaveColors = listOf(MascotArt.LeftArm, MascotArt.RightArm, MascotArt.Mint, MascotArt.LeftArm)
