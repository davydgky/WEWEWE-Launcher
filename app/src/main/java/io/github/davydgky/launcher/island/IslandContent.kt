package io.github.davydgky.launcher.island

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.Durations
import io.github.davydgky.launcher.core.IslandPicker
import io.github.davydgky.launcher.core.LiveCandidate
import io.github.davydgky.launcher.core.LiveKind
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.organizer.Todo
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// The island is black like the camera it surrounds; on it, colours as on the iPhone's island.
private val Surface = Color.Black
private val Tile = Color(0xFF1C1C1E)
private val TileHigh = Color(0xFF2C2C2E)
private val Secondary = Color(0xFF8E8E93)
private val Orange = Color(0xFFFF9F0A)
private val Green = Color(0xFF34C759)
private val Blue = Color(0xFF0A84FF)
private val Yellow = Color(0xFFFFD60A)
private val Indigo = Color(0xFF5E5CE6)

/** One thing the island can show next to the camera. */
private sealed interface Shown {
    val key: String

    data class Live(val item: LiveItem) : Shown {
        override val key get() = "live:" + item.key
    }

    data class Media(val info: MediaInfo) : Shown {
        override val key get() = "media:" + info.packageName
    }

    data class Event(val event: SoonEvent) : Shown {
        override val key get() = "event"
    }

    data object Torch : Shown {
        override val key get() = "torch"
    }

    data object Awake : Shown {
        override val key get() = "awake"
    }

    /** A reminder with a time that is due soon or just was. */
    data class Reminder(val todo: Todo) : Shown {
        override val key get() = "reminder:" + todo.id
    }
}

/** Sizes of the island's shapes in dp, from where the camera is. */
private class IslandSizes(g: IslandGeometry, sideWidth: Dp) {
    val density = g.density
    val pillTop = (g.pillTop / density).dp
    val pillHeight = (g.pillHeight / density).dp
    /** Room left free around the camera, between the left and the right half of the island. */
    val holeGap = ((g.holeWidth / density) + 8f).dp
    val idleWidth = maxOf(pillHeight, holeGap)
    /** Width on each side of the camera; narrow enough by default to leave the status bar's icons free. */
    val side = sideWidth
    val compactWidth = holeGap + side * 2
    val bubble = pillHeight
    val bubbleGap = 6.dp
    /** Widest the window can be while staying centred on the camera. */
    val maxWidth = (2 * min(g.centerX, g.screenWidth - g.centerX) / density).dp
    val peekWidth = minOf(maxWidth - 20.dp, 400.dp)
    val systemPeekWidth = minOf(maxWidth - 20.dp, holeGap + 220.dp)
    val expandedWidth = minOf(maxWidth - 16.dp, 440.dp)
    val corner = 40.dp
}

private enum class PeekStyle { Notification, System }

private fun Peek.style() = if (kind == PeekKind.Notification || kind == PeekKind.Reminder) PeekStyle.Notification else PeekStyle.System

@Composable
fun IslandContent(controller: IslandController) {
    val geometry = controller.geometry ?: return
    val hub = controller.hub
    val context = LocalContext.current
    val sizes = remember(geometry, controller.sideWidth) { IslandSizes(geometry, controller.sideWidth.dp) }

    val live by hub.liveItems.collectAsState()
    val media by hub.media.current.collectAsState()
    val peek by hub.peek.collectAsState()
    val event by hub.nextEvent.collectAsState()
    val keepAwake by hub.keepAwake.collectAsState()
    val torch by context.launcherApp.torch.isOn.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }

    val organizer by context.launcherApp.organizer.organizer.collectAsState()
    val todos = organizer?.todos.orEmpty()
    val shown = remember(live, media, event, torch, keepAwake, todos, now) { shownActivities(live, media, event, torch, keepAwake, todos, now) }
    val primary = shown.firstOrNull()
    val secondary = shown.getOrNull(1)
    val currentPeek = peek
    val mode = when {
        controller.hidden -> IslandMode.Hidden
        controller.expanded -> IslandMode.Expanded
        currentPeek != null -> IslandMode.Peek
        primary != null -> IslandMode.Compact
        else -> IslandMode.Idle
    }

    // The window follows the island: small around the pill, large while open or dragged.
    val large = mode == IslandMode.Expanded || controller.dragging
    val peekStyle = currentPeek?.style()
    val request = remember(mode, large, secondary != null, peekStyle, sizes, geometry) {
        windowRequestFor(mode, large, secondary != null, peekStyle, sizes, geometry)
    }
    LaunchedEffect(request) { controller.windowRequest = request }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A larger window arrives a frame or two after it was asked for. Until then the island keeps the
        // shape it had: drawn into the old, smaller window it would be cut off and jump.
        val fits = request.width <= constraints.maxWidth + 2 && request.height <= constraints.maxHeight + 2
        val last = remember { ShownMode() }
        val display = if (fits || mode == IslandMode.Hidden) mode else last.mode
        SideEffect { last.mode = display }

        if (display == IslandMode.Expanded) {
            // A tap beside the opened island closes it.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { controller.collapse() } })
        }
        if (display != IslandMode.Hidden) {
            val bubble = display == IslandMode.Compact && secondary != null
            // With a second activity, the box is as wide as both on each side, so the pill stays on the camera
            // and the bubble beside it is inside the box (touches outside a layout's bounds do not reach it).
            val width = if (bubble) Modifier.width(sizes.compactWidth + (sizes.bubbleGap + sizes.bubble) * 2) else Modifier
            Box(Modifier.align(Alignment.TopCenter).padding(top = sizes.pillTop).then(width)) {
                IslandSurface(controller, sizes, display, primary, currentPeek, shown, Modifier.align(Alignment.TopCenter))
                if (bubble && secondary != null) {
                    Bubble(secondary, sizes, Modifier.align(Alignment.TopEnd), onTap = controller::expand)
                }
            }
        }
    }
}

/** The shape the island showed last; not state, it only bridges the frames until the window has grown. */
private class ShownMode(var mode: IslandMode = IslandMode.Idle)

/** The window for [mode], in pixels, centred on the island and never wider than the screen allows. */
private fun windowRequestFor(
    mode: IslandMode,
    large: Boolean,
    bubble: Boolean,
    peekStyle: PeekStyle?,
    sizes: IslandSizes,
    geometry: IslandGeometry,
): WindowRequest {
    val d = sizes.density
    fun px(dp: Dp) = (dp.value * d).roundToInt()
    val maxWidth = px(sizes.maxWidth)
    // Generous margins: a finger needs more room than the pill itself, especially around a small camera.
    val margin = 12.dp
    val bottom = sizes.pillTop + margin
    fun request(width: Dp, height: Dp) = WindowRequest(px(width).coerceAtMost(maxWidth), px(height), large = false)
    return when {
        mode == IslandMode.Hidden -> WindowRequest(1, 1, large = false, touchable = false)
        large -> WindowRequest(maxWidth, (geometry.screenHeight * 0.85f).roundToInt(), large = true)
        mode == IslandMode.Peek -> {
            val width = if (peekStyle == PeekStyle.Notification) sizes.peekWidth else sizes.systemPeekWidth
            val height = sizes.pillHeight + if (peekStyle == PeekStyle.Notification) 64.dp else 0.dp
            request(width + margin * 2, bottom + height + margin)
        }
        mode == IslandMode.Compact -> {
            val extra = if (bubble) (sizes.bubbleGap + sizes.bubble) * 2 else 0.dp
            request(sizes.compactWidth + extra + margin * 2, bottom + sizes.pillHeight + margin)
        }
        else -> request(maxOf(sizes.idleWidth + margin * 2, MinTouchWidth), bottom + sizes.pillHeight + margin)
    }
}

/** The idle island is small; its window is at least this wide, so it is easy to hit. */
private val MinTouchWidth = 72.dp

/** Running before paused, calls before timers before music, as [IslandPicker] decides. */
private fun shownActivities(
    live: List<LiveItem>,
    media: MediaInfo?,
    event: SoonEvent?,
    torch: Boolean,
    keepAwake: Boolean,
    todos: List<Todo>,
    now: Long,
): List<Shown> {
    val all = buildList<Pair<Shown, LiveCandidate>> {
        live.forEach { add(Shown.Live(it) to LiveCandidate(it.key, it.kind, it.updatedAt, it.running)) }
        // Paused music stays next to the camera for two minutes, then only in the opened island.
        if (media != null && (media.playing || now - media.changedAt < PAUSED_MEDIA_MS)) {
            add(Shown.Media(media) to LiveCandidate("media", LiveKind.Media, media.changedAt, media.playing))
        }
        if (event != null && event.begin - now <= SOON_EVENT_MS) add(Shown.Event(event) to LiveCandidate("event", LiveKind.Event, event.begin))
        // A reminder sits next to the camera from 15 minutes before its time until 10 minutes after.
        todos.filter { !it.done && it.hasTime && (it.dueAtMillis ?: 0L) - now in -REMINDER_AFTER_MS..SOON_EVENT_MS }
            .minByOrNull { it.dueAtMillis!! }
            ?.let { add(Shown.Reminder(it) to LiveCandidate("reminder:" + it.id, LiveKind.Event, it.dueAtMillis!!)) }
        if (torch) add(Shown.Torch to LiveCandidate("torch", LiveKind.Other, 0))
        if (keepAwake) add(Shown.Awake to LiveCandidate("awake", LiveKind.Other, 0))
    }
    val byKey = all.associate { (shown, candidate) -> candidate.key to shown }
    return IslandPicker.order(all.map { it.second }).mapNotNull { byKey[it.key] }
}

private const val PAUSED_MEDIA_MS = 120_000L
private const val SOON_EVENT_MS = 15 * 60_000L
private const val REMINDER_AFTER_MS = 10 * 60_000L

@Composable
private fun IslandSurface(
    controller: IslandController,
    sizes: IslandSizes,
    mode: IslandMode,
    primary: Shown?,
    peek: Peek?,
    shown: List<Shown>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(sizes.corner)
    val idleInvisible = mode == IslandMode.Idle && !controller.idleVisible
    val color by animateColorAsState(if (idleInvisible) Color.Transparent else Surface, label = "islandColor")
    val key = when (mode) {
        IslandMode.Compact -> "compact:" + primary?.key
        IslandMode.Peek -> "peek:" + peek?.id
        else -> mode.name
    }
    val density = sizes.density
    // The gesture outlives a change of shape (pulling the island down opens it under the finger), so it
    // reads what is shown now instead of being restarted with it.
    val currentMode by rememberUpdatedState(mode)
    val currentPeek by rememberUpdatedState(peek)
    val currentPrimary by rememberUpdatedState(primary)
    // The black surface takes its size from what it shows and springs between sizes on its own; the
    // content inside only fades. One layout animation instead of two keeps the frames even.
    Box(
        modifier
            .graphicsLayer {
                // Pulled like rubber: it stretches a little, ever less the further it goes.
                val travel = controller.drag.value
                val max = 220f * density
                val pull = if (travel >= 0f) max * (travel / max) / (1f + travel / max) else -max * (-travel / max) / (1f + -travel / max)
                val height = size.height.coerceAtLeast(1f)
                val ratio = (pull / height).coerceIn(-0.15f, 0.35f)
                scaleY = 1f + ratio * 0.4f
                scaleX = 1f + ratio * 0.06f
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .clip(shape)
            .background(color)
            .islandGestures(controller, currentMode = { currentMode }, onTap = { started ->
                when (started) {
                    IslandMode.Peek -> currentPeek?.let { openPeek(controller, it) }
                    IslandMode.Expanded, IslandMode.Hidden -> Unit
                    else -> controller.expand()
                }
            }, onLongPress = { started ->
                when (started) {
                    IslandMode.Idle -> controller.openSearch()
                    IslandMode.Compact -> currentPrimary?.let { openShown(controller, it) }
                    IslandMode.Peek -> controller.expand()
                    else -> Unit
                }
            })
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 420f, visibilityThreshold = IntSize.VisibilityThreshold)),
    ) {
    AnimatedContent(
        targetState = key,
        transitionSpec = { fadeIn(tween(180, delayMillis = 70)) togetherWith fadeOut(tween(80)) using null },
        contentAlignment = Alignment.TopCenter,
        label = "island",
    ) { target ->
        when {
            target == IslandMode.Idle.name || target == IslandMode.Hidden.name -> Box(Modifier.size(sizes.idleWidth, sizes.pillHeight))
            target == IslandMode.Expanded.name -> ExpandedIsland(controller, sizes)
            target.startsWith("compact:") -> {
                val item = shown.firstOrNull { "compact:" + it.key == target }
                if (item != null) CompactIsland(item, sizes) else Box(Modifier.size(sizes.compactWidth, sizes.pillHeight))
            }
            target.startsWith("peek:") -> {
                val current = peek?.takeIf { "peek:" + it.id == target }
                if (current != null) PeekIsland(current, sizes) else Box(Modifier.size(sizes.idleWidth, sizes.pillHeight))
            }
        }
    }
    }
}

/**
 * Tap, long press and vertical drags on the island. Taps on buttons inside it stay theirs; a drag works
 * anywhere, also over buttons, and a sideways swipe is left to the tray.
 */
private fun Modifier.islandGestures(
    controller: IslandController,
    currentMode: () -> IslandMode,
    onTap: (IslandMode) -> Unit,
    onLongPress: (IslandMode) -> Unit,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // What the island showed when the finger came down decides what the gesture means.
        val mode = currentMode()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        if (mode == IslandMode.Peek) controller.hub.holdPeek()
        var dragging = false
        var ignored = false
        var longPressed = false
        var total = Offset.Zero
        var last = down.uptimeMillis
        while (true) {
            val waiting = !dragging && !longPressed && !ignored
            val event = if (waiting) {
                withTimeoutOrNull((longPress - (last - down.uptimeMillis)).coerceAtLeast(1L)) { awaitPointerEvent(PointerEventPass.Main) }
            } else {
                awaitPointerEvent(PointerEventPass.Main)
            }
            if (event == null) {
                longPressed = true
                if (mode != IslandMode.Expanded) onLongPress(mode)
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            last = change.uptimeMillis
            if (!change.pressed) {
                when {
                    dragging -> controller.endDrag(tracker.calculateVelocity().y, mode)
                    !longPressed && !ignored && !change.isConsumed -> onTap(mode)
                    mode == IslandMode.Peek -> controller.hub.releasePeek()
                }
                break
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            val delta = change.position - change.previousPosition
            total += delta
            if (!dragging && !ignored && !longPressed) {
                if (abs(total.x) > slop && abs(total.x) > abs(total.y)) {
                    ignored = true
                } else if (abs(total.y) > slop) {
                    dragging = true
                    controller.beginDrag(downwards = total.y > 0, mode = mode)
                }
            }
            if (dragging) {
                controller.dragBy(delta.y)
                change.consume()
            }
        }
    }
}

private fun openPeek(controller: IslandController, peek: Peek) {
    if (peek.kind == PeekKind.Reminder) {
        controller.hub.dismissPeek()
        controller.actions.openReminders()
    } else if (peek.kind == PeekKind.Notification) {
        controller.actions.send(peek.contentIntent)
        if (peek.autoCancel) peek.notificationKey?.let(controller.hub::cancelNotification)
        controller.hub.dismissPeek()
    } else {
        controller.expand()
    }
}

private fun openShown(controller: IslandController, shown: Shown) {
    when (shown) {
        is Shown.Live -> controller.actions.send(shown.item.contentIntent, shown.item.packageName)
        is Shown.Media -> controller.actions.send(shown.info.sessionActivity, shown.info.packageName)
        is Shown.Event -> controller.actions.startIntent(calendarIntent(shown.event.begin))
        Shown.Torch -> controller.actions.toggleTorch()
        Shown.Awake -> controller.hub.keepAwake.value = false
        is Shown.Reminder -> controller.actions.openReminders()
    }
}

private fun calendarIntent(time: Long): Intent = Intent(Intent.ACTION_VIEW)
    .setData(ContentUris.withAppendedId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(), time))

// region Compact

@Composable
private fun CompactIsland(shown: Shown, sizes: IslandSizes) {
    SplitRow(sizes, Modifier.width(sizes.compactWidth).height(sizes.pillHeight), startPadding = 6.dp, endPadding = 8.dp, left = {
        Leading(shown, size = (sizes.pillHeight - 8.dp).coerceAtMost(22.dp))
    }, right = {
        Trailing(shown)
    })
}

/** Left and right of the camera, with the camera itself left free. */
@Composable
private fun SplitRow(
    sizes: IslandSizes,
    modifier: Modifier,
    startPadding: Dp = 8.dp,
    endPadding: Dp = 11.dp,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = startPadding), contentAlignment = Alignment.CenterStart) { left() }
        Spacer(Modifier.width(sizes.holeGap))
        Box(Modifier.weight(1f).fillMaxHeight().padding(end = endPadding), contentAlignment = Alignment.CenterEnd) { right() }
    }
}

@Composable
private fun Leading(shown: Shown, size: Dp) {
    when (shown) {
        is Shown.Media -> Artwork(shown.info.art, shown.info.packageName, size, corner = 6.dp)
        is Shown.Live -> GlyphOf(shown.item, size)
        is Shown.Event -> Glyph(R.drawable.ic_event, Color(shown.event.color).takeIf { shown.event.color != 0 } ?: Orange, size)
        Shown.Torch -> Glyph(R.drawable.ic_flashlight_filled, Yellow, size)
        Shown.Awake -> Glyph(R.drawable.ic_coffee, Orange, size)
        is Shown.Reminder -> Glyph(R.drawable.ic_bell, Yellow, size)
    }
}

@Composable
private fun Trailing(shown: Shown) {
    when (shown) {
        is Shown.Media -> if (shown.info.playing) {
            Equalizer(accentOf(shown.info))
        } else {
            Glyph(R.drawable.ic_pause, Secondary, 16.dp)
        }
        is Shown.Live -> LiveTrailing(shown.item)
        is Shown.Event -> IslandText(shortUntil(shown.event.begin), colorOf(shown.event), 12.sp, weight = FontWeight.SemiBold)
        Shown.Torch -> IslandText(stringResource(R.string.island_on), Yellow, 12.sp, weight = FontWeight.SemiBold)
        Shown.Awake -> IslandText(stringResource(R.string.island_awake_short), Orange, 12.sp, weight = FontWeight.SemiBold)
        is Shown.Reminder -> IslandText(shortUntil(shown.todo.dueAtMillis ?: 0L), Yellow, 12.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun LiveTrailing(item: LiveItem) {
    val accent = accentOf(item)
    when {
        item.chronometer != null && item.running -> IslandText(chronometerText(item), accent, 12.sp, weight = FontWeight.SemiBold, tabular = true)
        item.progress != null -> ProgressRing(item.progress, accent, 18.dp)
        item.indeterminate -> ProgressRing(null, accent, 18.dp)
        else -> IslandText(item.shortText ?: item.text.ifBlank { item.title }, accent, 12.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun chronometerText(item: LiveItem): String {
    val base = item.chronometer ?: return ""
    val now by produceState(System.currentTimeMillis(), base) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000 - value % 1_000)
        }
    }
    return Durations.clock(if (item.countDown) base - now else now - base)
}

@Composable
private fun shortUntil(begin: Long): String {
    val german = LocalConfiguration.current.locales[0].language == "de"
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    return Durations.until(begin - now, german = german)
}

@Composable
private fun Bubble(shown: Shown, sizes: IslandSizes, modifier: Modifier, onTap: () -> Unit) {
    Box(
        modifier
            .size(sizes.bubble)
            .clip(CircleShape)
            .background(Surface)
            .pointerInput(Unit) { detectTapGestures { onTap() } },
        contentAlignment = Alignment.Center,
    ) {
        Leading(shown, (sizes.bubble - 10.dp).coerceAtMost(18.dp))
    }
}

/** Four bars that move to the music, in the colour of the artwork. */
@Composable
private fun Equalizer(color: Color) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val bars = listOf(420, 300, 520, 360).map { duration ->
        transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(duration), RepeatMode.Reverse), label = "bar")
    }
    Canvas(Modifier.size(width = 18.dp, height = 14.dp)) {
        val width = size.width / 7f
        bars.forEachIndexed { index, bar ->
            val h = size.height * bar.value
            drawLine(
                color = color,
                start = Offset(width * (index * 2 + 0.5f), size.height / 2 + h / 2),
                end = Offset(width * (index * 2 + 0.5f), size.height / 2 - h / 2),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ProgressRing(progress: Float?, color: Color, size: Dp) {
    val spin = if (progress == null) {
        val transition = rememberInfiniteTransition(label = "spin")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1_000)), label = "spinAngle").value
    } else {
        0f
    }
    Canvas(Modifier.size(size)) {
        val stroke = 2.6.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
        val sweep = if (progress == null) 90f else 360f * progress
        drawArc(color, -90f + spin, sweep, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

// endregion

// region Peek

@Composable
private fun PeekIsland(peek: Peek, sizes: IslandSizes) {
    if (peek.style() == PeekStyle.Notification) {
        Column(Modifier.width(sizes.peekWidth)) {
            SplitRow(sizes, Modifier.fillMaxWidth().height(sizes.pillHeight + 6.dp), left = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PeekImage(peek, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    IslandText(peek.title, Color.White, 14.sp, weight = FontWeight.SemiBold)
                }
            }, right = {
                IslandText(stringResource(R.string.island_now), Secondary, 12.sp)
            })
            if (peek.text.isNotBlank()) {
                Text(
                    peek.text,
                    color = Color.White.copy(alpha = 0.88f),
                    style = TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
    } else {
        val tint = Color(peek.tint)
        SplitRow(sizes, Modifier.width(sizes.systemPeekWidth).height(sizes.pillHeight), left = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(peek.iconRes, tint, 18.dp)
                Spacer(Modifier.width(6.dp))
                IslandText(peek.title, Color.White, 13.sp, weight = FontWeight.SemiBold)
            }
        }, right = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (peek.text.isNotBlank()) IslandText(peek.text, tint, 13.sp, weight = FontWeight.SemiBold)
                if (peek.level != null && peek.level >= 0) {
                    Spacer(Modifier.width(6.dp))
                    ProgressRing(peek.level / 100f, tint, 16.dp)
                }
            }
        })
    }
}

@Composable
private fun PeekImage(peek: Peek, size: Dp) {
    val image = rememberImage(peek.image)
    if (image != null) {
        Image(image, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Glyph(R.drawable.ic_bell, Color.White, size)
    }
}

// endregion

// region Expanded

@Composable
private fun ExpandedIsland(controller: IslandController, sizes: IslandSizes) {
    val hub = controller.hub
    val media by hub.media.current.collectAsState()
    val live by hub.liveItems.collectAsState()
    val event by hub.nextEvent.collectAsState()
    val shelf by hub.shelf.collectAsState()
    val locked by hub.locked.collectAsState()
    // Black of its own as well: whatever the surface around it does, the opened island is never see-through.
    Column(Modifier.width(sizes.expandedWidth).background(Surface).padding(bottom = 14.dp)) {
        // The row around the camera stays empty: the status bar's clock and battery lie below it.
        Spacer(Modifier.height(sizes.pillHeight + 6.dp))
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // A handful at most; more would push the toggles off the screen.
            live.take(MAX_LIVE_ROWS).forEach { LiveRow(controller, it) }
            // Reminders before everything else that can wait: they are what the island is opened for most.
            if (!locked) RemindersCard(controller)
            media?.let { MediaCard(controller, it) }
            event?.let { e -> EventRow(e) { controller.collapse(); controller.actions.startIntent(calendarIntent(e.begin)) } }
            if (!locked && shelf.isNotEmpty()) ShelfRow(controller, shelf)
            Toggles(controller, locked)
            if (!locked) SearchBar(controller)
        }
    }
}

private const val MAX_LIVE_ROWS = 4

/**
 * The open reminders of the organizer, as on the launcher's page: overdue and today's first, then the
 * next ones; a tap on the circle ticks one off, "+" adds one, "All" opens the organizer.
 */
@Composable
private fun RemindersCard(controller: IslandController) {
    val context = LocalContext.current
    val repository = context.launcherApp.organizer
    // Opened: the latest reminders, also those changed in the other app.
    LaunchedEffect(Unit) { repository.syncWithPeer() }
    val state by repository.organizer.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val todos = state?.todos.orEmpty()
    val endOfToday = remember(now) { java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val open = remember(todos) {
        todos.filter { !it.done }.sortedWith(compareBy<Todo>({ it.dueAtMillis == null }, { it.dueAtMillis ?: Long.MAX_VALUE }, { it.order }))
    }
    val dueCount = open.count { (it.dueAtMillis ?: Long.MAX_VALUE) < endOfToday }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Tile).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_bell, Yellow, 16.dp)
            Spacer(Modifier.width(8.dp))
            IslandText(stringResource(R.string.reminders_label), Color.White, 15.sp, weight = FontWeight.SemiBold)
            if (dueCount > 0) {
                Spacer(Modifier.width(8.dp))
                IslandText(dueCount.toString(), Yellow, 13.sp, weight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            RoundButton(R.drawable.ic_add, stringResource(R.string.reminder_add), 36.dp, Color.Transparent, Yellow) {
                controller.collapse()
                controller.actions.quickNote()
            }
            Text(
                stringResource(R.string.reminders_all),
                color = Yellow,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = {
                        controller.collapse()
                        controller.actions.openReminders()
                    })
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (open.isEmpty()) {
            IslandText(stringResource(R.string.reminders_none), Secondary, 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
        }
        open.take(MAX_REMINDERS).forEach { todo ->
            ReminderRow(todo, now, onToggle = { repository.toggleDone(todo.id) }) {
                controller.collapse()
                controller.actions.openReminders()
            }
        }
        if (open.size > MAX_REMINDERS) {
            IslandText(
                stringResource(R.string.reminders_more, open.size - MAX_REMINDERS),
                Secondary, 12.sp, modifier = Modifier.padding(start = 34.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun ReminderRow(todo: Todo, now: Long, onToggle: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val due = todo.dueAtMillis
    val overdue = due != null && (if (todo.hasTime) due < now else due < java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
    val label = remember(due, todo.hasTime) { due?.let { dueLabel(context, it, todo.hasTime) } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The circle ticks it off, as in Apple Reminders and on the organizer page.
        Box(
            Modifier.size(28.dp).clip(CircleShape).combinedClickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(18.dp)) {
                drawCircle(if (overdue) Color(0xFFFF453A) else Secondary, style = Stroke(1.6.dp.toPx()))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(todo.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            IslandText(label, if (overdue) Color(0xFFFF453A) else Secondary, 12.sp, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/** "14:30", "Morgen 9:00", "Fr., 12:00", "28. Nov.": short enough for one line beside the title. */
private fun dueLabel(context: android.content.Context, due: Long, hasTime: Boolean): String {
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    val time = if (hasTime) android.text.format.DateFormat.getTimeFormat(context).format(due) else ""
    val day = when (date) {
        today -> if (hasTime) "" else context.getString(R.string.reminder_today)
        today.plusDays(1) -> context.getString(R.string.reminder_tomorrow)
        else -> android.text.format.DateUtils.formatDateTime(
            context, due,
            if (date.isBefore(today.plusDays(7)) && date.isAfter(today)) android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY
            else android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH or android.text.format.DateUtils.FORMAT_NO_YEAR,
        )
    }
    return listOf(day, time).filter { it.isNotBlank() }.joinToString(" ")
}

private const val MAX_REMINDERS = 5

@Composable
private fun MediaCard(controller: IslandController, info: MediaInfo) {
    val accent = accentOf(info)
    val controls = info.controller.transportControls
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Tile).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = {
                controller.collapse()
                controller.actions.send(info.sessionActivity, info.packageName)
            })) {
                Artwork(info.art, info.packageName, 52.dp, corner = 12.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                IslandText(info.title, Color.White, 15.sp, weight = FontWeight.SemiBold)
                if (info.artist.isNotBlank()) IslandText(info.artist, Secondary, 13.sp)
            }
            if (info.playing) Equalizer(accent)
        }
        if (info.duration > 0) {
            val position by produceState(info.positionNow(SystemClock.elapsedRealtime()), info) {
                while (true) {
                    value = info.positionNow(SystemClock.elapsedRealtime())
                    delay(500)
                }
            }
            Spacer(Modifier.height(10.dp))
            Bar(position.toFloat() / info.duration, accent)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                IslandText(Durations.clock(position), Secondary, 11.sp, tabular = true)
                Spacer(Modifier.weight(1f))
                IslandText("-" + Durations.clock(info.duration - position), Secondary, 11.sp, tabular = true)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            RoundButton(R.drawable.ic_skip_previous, stringResource(R.string.island_previous), 40.dp, Color.Transparent, Color.White) { controls.skipToPrevious() }
            Spacer(Modifier.width(18.dp))
            RoundButton(
                if (info.playing) R.drawable.ic_pause else R.drawable.ic_play,
                stringResource(if (info.playing) R.string.island_pause else R.string.island_play),
                48.dp, Color.White, Color.Black,
            ) { if (info.playing) controls.pause() else controls.play() }
            Spacer(Modifier.width(18.dp))
            RoundButton(R.drawable.ic_skip_next, stringResource(R.string.island_next), 40.dp, Color.Transparent, Color.White) { controls.skipToNext() }
        }
    }
}

@Composable
private fun LiveRow(controller: IslandController, item: LiveItem) {
    val accent = accentOf(item)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Tile)
            .combinedClickable(onClick = {
                controller.collapse()
                controller.actions.send(item.contentIntent, item.packageName)
            })
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                GlyphOf(item, 18.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                IslandText(item.title, Color.White, 14.sp, weight = FontWeight.SemiBold)
                val detail = item.text.ifBlank { controller.hub.appLabel(item.packageName) }
                IslandText(detail, Secondary, 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            when {
                item.chronometer != null && item.running -> IslandText(chronometerText(item), accent, 20.sp, weight = FontWeight.SemiBold, tabular = true)
                item.progress != null -> IslandText("${(item.progress * 100).roundToInt()} %", accent, 15.sp, weight = FontWeight.SemiBold, tabular = true)
                item.shortText != null -> IslandText(item.shortText, accent, 15.sp, weight = FontWeight.SemiBold)
            }
        }
        if (item.progress != null) {
            Spacer(Modifier.height(8.dp))
            Bar(item.progress, accent)
        }
    }
}

@Composable
private fun EventRow(event: SoonEvent, onClick: () -> Unit) {
    val color = colorOf(event)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Tile)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Glyph(R.drawable.ic_event, color, 18.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            IslandText(event.title.ifBlank { stringResource(R.string.island_event) }, Color.White, 14.sp, weight = FontWeight.SemiBold)
            IslandText(stringResource(R.string.island_next_event), Secondary, 12.sp)
        }
        IslandText(shortUntil(event.begin), color, 14.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun ShelfRow(controller: IslandController, shelf: List<ShelfItem>) {
    val hub = controller.hub
    val copied = stringResource(R.string.island_copied)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) {
            IslandText(stringResource(R.string.island_shelf), Secondary, 12.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.island_shelf_clear),
                color = Secondary,
                fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = hub::clearShelf).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shelf.forEach { item ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Tile)
                        .combinedClickable(
                            onClick = {
                                if (item.isLink) {
                                    controller.collapse()
                                    controller.actions.openUrl(item.text)
                                } else {
                                    hub.copy(item.text)
                                    hub.confirm(copied, R.drawable.ic_copy)
                                    controller.collapse()
                                }
                            },
                            onLongClick = {
                                hub.copy(item.text)
                                hub.confirm(copied, R.drawable.ic_copy)
                                controller.collapse()
                            },
                        )
                        .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(if (item.isLink) R.drawable.ic_link else R.drawable.ic_note, Secondary, 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        shelfLabel(item),
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp),
                    )
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).combinedClickable(onClick = { hub.removeFromShelf(item.id) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph(R.drawable.ic_close, Secondary, 14.dp)
                    }
                }
            }
        }
    }
}

private fun shelfLabel(item: ShelfItem): String =
    if (item.isLink) item.text.substringAfter("://").removePrefix("www.").trimEnd('/') else item.text.replace('\n', ' ')

@Composable
private fun Toggles(controller: IslandController, locked: Boolean) {
    val hub = controller.hub
    val context = LocalContext.current
    val torch by context.launcherApp.torch.isOn.collectAsState()
    val awake by hub.keepAwake.collectAsState()
    val dnd by hub.doNotDisturb.collectAsState()
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Toggle(R.drawable.ic_flashlight_filled, stringResource(R.string.island_torch), torch, Yellow) { controller.actions.toggleTorch() }
        Toggle(R.drawable.ic_coffee, stringResource(R.string.island_awake), awake, Orange) { hub.keepAwake.value = !awake }
        Toggle(R.drawable.ic_dnd, stringResource(R.string.island_dnd), dnd, Indigo) {
            if (!hub.toggleDoNotDisturb()) {
                controller.collapse()
                controller.actions.openNotificationAccess()
            }
        }
        if (!locked) {
            Toggle(R.drawable.ic_text_scan, stringResource(R.string.island_read_screen), false, Blue) {
                controller.collapse()
                controller.actions.readScreen()
            }
        }
        Toggle(R.drawable.ic_lock_filled, stringResource(R.string.island_lock), false, Color.White) {
            controller.collapse()
            controller.actions.lock()
        }
    }
}

@Composable
private fun Toggle(icon: Int, label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    val background by animateColorAsState(if (active) activeColor else TileHigh, label = "toggle")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(background)
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(), onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), label, Modifier.size(20.dp), tint = if (active) Color.Black else Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Secondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchBar(controller: IslandController) {
    // Pulled far enough, letting go opens the search: the bar says so.
    val armed = controller.searchArmed()
    val background by animateColorAsState(if (armed) Color.White else Tile, label = "searchBar")
    val content = if (armed) Color.Black else Secondary
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .combinedClickable(onClick = controller::openSearch)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, Modifier.size(18.dp), tint = content)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (armed) R.string.island_release_to_search else R.string.island_search),
            color = content,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RoundButton(icon: Int, label: String, size: Dp, background: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background).combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), label, Modifier.size(size * 0.52f), tint = tint)
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
    }
}

// endregion

// region Small pieces

@Composable
private fun IslandText(
    text: String,
    color: Color,
    size: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    tabular: Boolean = false,
) {
    Text(
        text,
        color = color,
        style = TextStyle(fontSize = size, fontWeight = weight, fontFeatureSettings = if (tabular) "tnum" else null),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun Glyph(icon: Int, tint: Color, size: Dp) {
    Icon(painterResource(icon), null, Modifier.size(size), tint = tint)
}

/** The live activity's own icon, or one for its kind if the app gave none. */
@Composable
private fun GlyphOf(item: LiveItem, size: Dp) {
    val accent = accentOf(item)
    val image = rememberImage(item.icon)
    val fallback = when (item.kind) {
        LiveKind.Call -> R.drawable.ic_call_filled
        LiveKind.Navigation -> R.drawable.ic_navigation
        LiveKind.Timer, LiveKind.Stopwatch -> R.drawable.ic_timer_filled
        LiveKind.Progress -> R.drawable.ic_download
        else -> R.drawable.ic_bell
    }
    // Calls, timers and navigation look the same in every app, as on the iPhone.
    if (image != null && item.kind !in setOf(LiveKind.Call, LiveKind.Timer, LiveKind.Stopwatch)) {
        Image(image, null, Modifier.size(size), colorFilter = ColorFilter.tint(accent))
    } else {
        Glyph(fallback, accent, size)
    }
}

@Composable
private fun Artwork(art: Bitmap?, packageName: String, size: Dp, corner: Dp) {
    val context = LocalContext.current
    val bitmap = art ?: remember(packageName) { context.launcherApp.island.appIcon(packageName) }
    val image = rememberImage(bitmap)
    if (image != null) {
        Image(image, null, Modifier.size(size).clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(TileHigh))
    }
}

@Composable
private fun rememberImage(bitmap: Bitmap?): ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }

private fun accentOf(item: LiveItem): Color = when (item.kind) {
    LiveKind.Call -> Green
    LiveKind.Timer, LiveKind.Stopwatch -> Orange
    LiveKind.Navigation -> Blue
    else -> visible(item.color) ?: Color.White
}

private fun accentOf(info: MediaInfo): Color = visible(info.accent) ?: Color.White

private fun colorOf(event: SoonEvent): Color = visible(event.color) ?: Orange

/** [argb] if it shows on black, else null: an app's dark brand colour would vanish on the island. */
private fun visible(argb: Int): Color? {
    if (argb == 0) return null
    val color = Color(argb).copy(alpha = 1f)
    val luminance = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    return if (luminance < 0.25f) null else color
}

// endregion
