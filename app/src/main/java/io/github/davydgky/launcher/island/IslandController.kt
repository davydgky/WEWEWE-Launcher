package io.github.davydgky.launcher.island

import android.app.PendingIntent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** What the island can do outside itself; the accessibility service that hosts it carries it out. */
interface IslandActions {
    fun openSearch()
    fun send(intent: PendingIntent?, fallbackPackage: String? = null)
    fun launchApp(packageName: String)
    fun openUrl(url: String)
    fun startIntent(intent: android.content.Intent)
    fun lock()
    fun readScreen()
    fun quickNote()
    fun toggleTorch()
    fun openNotificationAccess()
    fun openReminders()

    /** Runs a shortcut of the opened island that is not a switch of the island itself. */
    fun perform(toggle: QuickToggle)
}

/** Where the island is on the screen, in pixels; computed from the display cutout. */
data class IslandGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    /** Horizontal centre of the island: the camera's, or the screen's if the camera is off-centre. */
    val centerX: Int,
    /** The camera cutout's size; 0 on screens without one (the island then sits in the status bar). */
    val holeWidth: Int,
    val holeHeight: Int,
    /** Top of the pill and its height, so that it surrounds the camera evenly. */
    val pillTop: Int,
    val pillHeight: Int,
    val density: Float,
)

/**
 * The window around the island, horizontally centred on it: just around the pill while it is small, so
 * the status bar beside it stays usable, and large while it is open or dragged ([large]).
 */
data class WindowRequest(val width: Int, val height: Int, val large: Boolean, val touchable: Boolean = true)

/** The island's shape at a given moment. */
enum class IslandMode {
    /** Landscape, a full-screen app, or a screenshot being taken: not drawn, not touchable. */
    Hidden,

    /** Nothing to show: a small ring around the camera (or nothing, if switched off). */
    Idle,

    /** A live activity next to the camera: artwork left, time or bars right. */
    Compact,

    /** A short message for a few seconds: a notification, charging, the ringer, headphones. */
    Peek,

    /** Opened by a tap or pulled down: everything, with controls and the search. */
    Expanded,
}

/**
 * The island's state and its gestures: a tap opens it, pulling it down opens it and pulling further
 * opens the search; pushing it up closes it, as does a tap anywhere else.
 */
@Stable
class IslandController(
    val hub: IslandHub,
    val actions: IslandActions,
    private val scope: CoroutineScope,
) {
    var geometry by mutableStateOf<IslandGeometry?>(null)

    /** Hidden by orientation, a full-screen app or a screenshot; see [IslandMode.Hidden]. */
    var hidden by mutableStateOf(false)

    var expanded by mutableStateOf(false)
        private set

    /** A finger drags the island; while it does, the window is large enough for the stretch. */
    var dragging by mutableStateOf(false)
        private set

    /** Finger travel of the current drag in px (positive: down), springing back after release. */
    val drag = Animatable(0f)

    /** The island is shown as the small ring when idle (else invisible but still touchable). */
    var idleVisible by mutableStateOf(true)

    /** Width of the compact island on each side of the camera, in dp (a setting: narrow, normal, wide). */
    var sideWidth by mutableStateOf(SIDE_NARROW)

    /** The shortcuts of the opened island, in order. */
    var toggles by mutableStateOf(QuickToggle.DEFAULT)

    /** The size the window needs for what the island shows; set by the island's content. */
    var windowRequest by mutableStateOf<WindowRequest?>(null)

    fun expand() {
        if (hidden) return
        hub.dismissPeek()
        expanded = true
    }

    fun collapse() {
        expanded = false
    }

    fun toggle() = if (expanded) collapse() else expand()

    fun openSearch() {
        collapse()
        actions.openSearch()
    }

    /** True while a downward drag opened the island; letting go too early closes it again. */
    private var openedByDrag = false

    /** A drag began on the island showing [mode]; pulling down opens it right away and follows the finger. */
    fun beginDrag(downwards: Boolean, mode: IslandMode) {
        dragging = true
        hub.holdPeek()
        openedByDrag = downwards && mode != IslandMode.Expanded
        if (openedByDrag) expand()
    }

    fun dragBy(dy: Float) {
        // Undispatched: the stretch follows the finger in the same frame, not one later.
        scope.launch(AndroidUiDispatcher.Main, start = CoroutineStart.UNDISPATCHED) { drag.snapTo(drag.value + dy) }
    }

    /**
     * The finger let go after a drag; [velocity] in px/s, positive downwards. [mode] is what the island
     * showed when the drag began.
     */
    fun endDrag(velocity: Float, mode: IslandMode) {
        val density = geometry?.density ?: 1f
        val travel = drag.value
        val open = OPEN_DP * density
        when {
            // Pulled far enough, from any state: the search.
            travel > SEARCH_DP * density -> openSearch()
            mode == IslandMode.Expanded && (travel < -open || velocity < -FLING_DP * density) -> collapse()
            mode == IslandMode.Peek && (travel < -open / 2 || velocity < -FLING_DP * density) -> hub.dismissPeek()
            // Opened by this drag and pushed back up before letting go: closed again. A short pull that
            // ends going down keeps it open; closing it then would look like it shut by itself.
            openedByDrag && (travel < 0f || velocity < -FLING_DP * density) -> collapse()
        }
        openedByDrag = false
        if (mode == IslandMode.Peek) hub.releasePeek()
        // The spring needs the frame clock of the UI thread: without it the animation throws, and the island
        // app crashed every time a drag was let go.
        scope.launch(AndroidUiDispatcher.Main) {
            // Also when the spring is interrupted (a new touch): a drag that never ends would keep the
            // window large, and the large window would swallow every touch meant for the app below.
            try {
                drag.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium), initialVelocity = velocity)
            } finally {
                dragging = false
            }
        }
    }

    /** The drag is far enough that letting go opens the search. */
    fun searchArmed(): Boolean = drag.value > SEARCH_DP * (geometry?.density ?: 1f)

    companion object {
        const val SIDE_NARROW = 34f
        const val SIDE_NORMAL = 46f
        const val SIDE_WIDE = 64f

        /** Travel that opens or closes the island. */
        const val OPEN_DP = 36f

        /** Travel that opens the search instead. */
        const val SEARCH_DP = 150f

        const val FLING_DP = 900f
    }
}
