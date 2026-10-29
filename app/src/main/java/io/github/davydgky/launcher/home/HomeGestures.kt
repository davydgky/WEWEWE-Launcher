package io.github.davydgky.launcher.home

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/** Callbacks of [homeItemGestures]; positions are in root coordinates. */
class HomeItemGestureCallbacks(
    /** Null for elements that handle taps themselves (widgets, the icons inside a folder). */
    val onTap: (() -> Unit)?,
    val onLongPress: () -> Unit,
    /**
     * [grab] is where inside the element the finger holds it. From here on [homeDragTracker] follows the
     * finger, so the drag goes on even if this element is recomposed elsewhere or removed meanwhile.
     */
    val onDragStart: (pointer: Offset, grab: Offset, pointerId: PointerId) -> Unit,
    /** A finger went down on the element (before anyone else decides what the gesture is). */
    val onDown: () -> Unit = {},
    /** The gesture on the element is over, whatever it was. */
    val onRelease: () -> Unit = {},
    /** In edit mode an element moves at once, without a long press, and keeps every touch to itself. */
    val editing: () -> Boolean = { false },
    /** A tap in edit mode (e.g. a widget's size menu); apps do not open then. */
    val onEditTap: (() -> Unit)? = null,
)

private const val LONG_PRESS = 0
private const val TAP = 1
private const val TAKEN = 2

private class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Tap, long press and long press followed by dragging, as on a home screen. Events are watched before
 * the element's children see them, but only taken once the long press fires, so a widget or a folder's
 * icons still receive ordinary taps and scrolls. In edit mode the element takes the whole gesture and
 * moves as soon as the finger does, as on iOS.
 */
fun Modifier.homeItemGestures(key: Any, callbacks: HomeItemGestureCallbacks): Modifier = composed {
    val holder = remember { CoordinatesHolder() }
    // The gesture outlives recompositions; always call the latest callbacks.
    val current by rememberUpdatedState(callbacks)
    this
        .onGloballyPositioned { holder.coordinates = it }
        .pointerInput(key) {
            val timeout = viewConfiguration.longPressTimeoutMillis
            val slop = viewConfiguration.touchSlop
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    current.onDown()
                    if (current.editing()) {
                        try {
                            down.consume()
                            var dragging = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) {
                                    if (!dragging) current.onEditTap?.invoke()
                                    break
                                }
                                val coordinates = holder.coordinates ?: continue
                                if (!dragging && (change.position - down.position).getDistance() > slop) {
                                    dragging = true
                                    current.onDragStart(coordinates.localToRoot(change.position), down.position, down.id)
                                }
                            }
                        } finally {
                            current.onRelease()
                        }
                        continue
                    }
                    try {
                        var outcome = LONG_PRESS
                        withTimeoutOrNull(timeout) {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || change.isConsumed) {
                                    // Someone else (the pager, the search swipe) took the gesture.
                                    outcome = TAKEN
                                    break
                                }
                                if (!change.pressed) {
                                    outcome = TAP
                                    break
                                }
                                if ((change.position - down.position).getDistance() > slop) {
                                    outcome = TAKEN
                                    break
                                }
                            }
                        }
                        when (outcome) {
                            TAP -> current.onTap?.invoke()
                            TAKEN -> Unit
                            else -> {
                                current.onLongPress()
                                var dragging = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    // From here on the gesture is ours: children and the pager get nothing.
                                    change.consume()
                                    if (!change.pressed) break
                                    val coordinates = holder.coordinates ?: continue
                                    if (!dragging && (change.position - down.position).getDistance() > slop) {
                                        dragging = true
                                        current.onDragStart(coordinates.localToRoot(change.position), down.position, down.id)
                                    }
                                }
                            }
                        }
                    } finally {
                        current.onRelease()
                    }
                }
            }
        }
}

/**
 * Follows the finger that drags a home screen element, from the root of the screen: the drag goes on
 * when the element it started on is recomposed, moves to another page or makes room for others, and
 * ends when the finger lifts or the system cancels the touch.
 */
fun Modifier.homeDragTracker(controller: HomeController): Modifier = composed {
    val holder = remember { CoordinatesHolder() }
    this
        .onGloballyPositioned { holder.coordinates = it }
        .pointerInput(controller) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val drag = controller.drag ?: continue
                    val change = event.changes.firstOrNull { it.id == drag.pointerId }
                    if (change == null || !change.pressed) {
                        controller.endDrag()
                        continue
                    }
                    val coordinates = holder.coordinates ?: continue
                    if (change.position != change.previousPosition) controller.moveDrag(coordinates.localToRoot(change.position))
                }
            }
        }
}
