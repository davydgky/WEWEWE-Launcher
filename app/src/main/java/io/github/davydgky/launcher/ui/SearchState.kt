package io.github.davydgky.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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

    fun open() {
        if (progress.value >= 1f && isActive) return
        if (!isActive) {
            isActive = true
            onOpening()
        }
        ime.begin(progress.value)
        settle(true, 0f)
    }

    fun close() {
        if (!isActive) return
        // Only drive the keyboard down if it is up; the user may have hidden it already.
        if (ime.isKeyboardVisible) ime.begin(progress.value)
        settle(false, 0f)
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
        settleJob = scope.launch {
            progress.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                initialVelocity = velocity,
            ) { ime.setFraction(value) }
            ime.finish(open)
            if (!open) {
                query.clearText()
                isActive = false
            }
        }
    }

    companion object {
        /** Finger travel for a fully open panel. */
        const val DRAG_DISTANCE_DP = 160f
        private const val OPEN_THRESHOLD = 0.35f
        private const val FLING_VELOCITY = 2.5f
    }
}

/**
 * Opens the search with a downward swipe anywhere on the home screen except the status bar, whose edge
 * belongs to the notification shade. Horizontal swipes go to the pager; [enabled] is false while an
 * element is held, dragged or edited.
 */
fun Modifier.searchGesture(
    state: SearchState,
    distancePx: Float,
    topInsetPx: Float,
    enabled: () -> Boolean,
): Modifier = pointerInput(state, distancePx, topInsetPx) {
    val config: ViewConfiguration = viewConfiguration
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (state.isActive || !enabled() || down.position.y < topInsetPx) continue
            var total = Offset.Zero
            var dragging = false
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (dragging) {
                        change.consume()
                        state.onDragEnd(tracker.calculateVelocity().y / distancePx)
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
                    if (total.y < -slop) break // swipe up: not ours
                    if (total.y > slop && total.y > abs(total.x)) {
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
