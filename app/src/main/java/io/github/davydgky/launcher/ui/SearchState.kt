package io.github.davydgky.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.ViewConfiguration
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Open state of the search panel. [progress] runs from 0 (home screen) to 1 (search open); the
 * keyboard, the panel and the dimmed home screen all follow it in the same frame.
 */
@Stable
class SearchState(
    private val scope: CoroutineScope,
    private val ime: ImeController,
    private val onOpening: () -> Unit,
) {
    val progress = Animatable(0f)
    val query = TextFieldState()

    /** True from the start of an opening gesture until the panel is fully closed again. */
    var isActive by mutableStateOf(false)
        private set

    private var settleJob: Job? = null

    /** The swipe was recognised; [uptimeMs] is the time of that event, for the latency measurement. */
    fun onDragStart(uptimeMs: Long) {
        settleJob?.cancel()
        if (!isActive) {
            isActive = true
            onOpening()
        }
        ime.begin(progress.value, gestureUptimeMs = uptimeMs)
    }

    fun onDrag(fraction: Float) {
        val value = (progress.value + fraction).coerceIn(0f, 1f)
        // Undispatched, so the panel moves in the same frame as the keyboard.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(value) }
        ime.setFraction(value)
    }

    /** [velocity] in fractions per second, positive towards open. */
    fun onDragEnd(velocity: Float) {
        val open = when {
            velocity > FLING_VELOCITY -> true
            velocity < -FLING_VELOCITY -> false
            else -> progress.value > OPEN_THRESHOLD
        }
        settle(open, velocity)
    }

    /**
     * Opens without a finger (search button, typing on a hardware keyboard): the keyboard comes with
     * the system's own animation and the panel moves alongside it.
     */
    fun open() {
        if (progress.value >= 1f && isActive) return
        if (!isActive) {
            isActive = true
            onOpening()
        }
        ime.animate(shown = true)
        glide(open = true)
    }

    /** Closes without a finger (home, back, a tap outside), the same way. */
    fun close() {
        if (!isActive) return
        ime.animate(shown = false)
        glide(open = false)
    }

    private fun glide(open: Boolean) {
        settleJob?.cancel()
        settleJob = scope.launch {
            progress.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = if (open) tween(OPEN_MS, easing = EmphasizedDecelerate) else tween(CLOSE_MS, easing = EmphasizedAccelerate),
            )
            if (!open) closed()
        }
    }

    private fun closed() {
        query.clearText()
        isActive = false
    }

    /** Closes without animation, e.g. after an app was launched from the search. */
    fun reset() {
        settleJob?.cancel()
        ime.cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(0f) }
        query.clearText()
        isActive = false
    }

    private fun settle(open: Boolean, velocity: Float) {
        settleJob?.cancel()
        ime.aim(open)
        settleJob = scope.launch {
            progress.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                initialVelocity = velocity,
            ) { ime.setFraction(value) }
            ime.finish(open)
            if (!open) closed()
        }
    }

    companion object {
        /** Finger travel for a fully open panel. */
        const val DRAG_DISTANCE_DP = 160f
        private const val OPEN_THRESHOLD = 0.35f
        private const val FLING_VELOCITY = 2.5f
        // Close to the system's keyboard animation, so field and keyboard arrive together.
        private const val OPEN_MS = 300
        private const val CLOSE_MS = 220
        private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    }
}

/**
 * Opens the search with a downward swipe anywhere on the home screen except the status bar, whose edge
 * belongs to the notification shade; a swipe up calls [onSwipeUp] (the App Library, as the app drawer
 * on Pixel phones). Horizontal swipes go to the pager, and a widget that scrolls keeps its swipe;
 * [enabled] is false while an element is held, dragged or edited.
 */
fun Modifier.searchGesture(
    state: SearchState,
    distancePx: Float,
    topInsetPx: Float,
    swipeUpPx: Float,
    onSwipeUp: () -> Unit,
    enabled: () -> Boolean,
): Modifier = pointerInput(state, distancePx, topInsetPx, swipeUpPx) {
    val config: ViewConfiguration = viewConfiguration
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (state.isActive || !enabled() || down.position.y < topInsetPx) continue
            var total = Offset.Zero
            var dragging = false
            var upward = false
            var previous: PointerInputChange? = null
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // Something below (a scrolling widget) took the last move: the swipe is its own.
                if (upward && previous?.isConsumed == true) break
                previous = change
                if (!change.pressed) {
                    if (dragging) {
                        change.consume()
                        state.onDragEnd(tracker.calculateVelocity().y / distancePx)
                    } else if (upward && total.y < -swipeUpPx && abs(total.y) > abs(total.x) * 2 && enabled()) {
                        onSwipeUp()
                    }
                    break
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.position - change.previousPosition
                total += delta
                if (!dragging) {
                    // A long press on an app started its menu or a drag: not ours.
                    if (!enabled()) break
                    val slop = config.touchSlop
                    if (abs(total.x) > slop && abs(total.x) > abs(total.y)) break // a page swipe
                    if (total.y < -slop) upward = true
                    if (!upward && total.y > slop && total.y > abs(total.x)) {
                        dragging = true
                        state.onDragStart(change.uptimeMillis)
                    }
                }
                if (dragging) {
                    change.consume()
                    state.onDrag(delta.y / distancePx)
                }
            }
        }
    }
}
