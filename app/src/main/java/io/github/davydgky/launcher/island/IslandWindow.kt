package io.github.davydgky.launcher.island

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Display
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The island's window: an accessibility overlay, the only kind of window an app may place over the
 * status bar and around the camera. It is only as large as what the island shows, so the status bar
 * beside it keeps working (a swipe there still opens the notification shade); while the island is open
 * it grows to catch the tap that closes it.
 */
class IslandWindow(
    private val service: AccessibilityService,
    private val hub: IslandHub,
    actions: IslandActions,
) : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val scope = lifecycleRegistry.coroutineScope
    val controller = IslandController(hub, actions, scope)

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: IslandRoot? = null
    private var jobs = emptyList<Job>()
    private var statusBarSeen = false
    private var statusBarVisible = true
    private var screenshotHidden = false

    private val params = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = "WEWEWE Island"
        // Opens and closes with the island's own animation, not the window's.
        windowAnimations = 0
    }

    val isShowing: Boolean get() = root != null

    fun show() {
        if (root != null) return
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            savedState.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val view = IslandRoot(service) { controller.collapse() }
        val compose = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { IslandContent(controller) }
        }
        view.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        // A full-screen app hides the status bar: the island goes with it, as the camera is all that is left.
        view.setOnApplyWindowInsetsListener { _, insets ->
            val visible = insets.isVisible(WindowInsets.Type.statusBars())
            if (visible) statusBarSeen = true
            main.removeCallbacks(fullScreenCheck)
            if (visible) {
                statusBarVisible = true
                updateHidden()
            } else {
                // Apps hide the status bar for a moment while they switch (camera, games, video players):
                // the island only goes once it stays hidden.
                main.postDelayed(fullScreenCheck, FULL_SCREEN_DELAY_MS)
            }
            insets
        }
        updateGeometry()
        root = view
        runCatching { windowManager.addView(view, params) }.onFailure {
            root = null
            return
        }
        hub.start()
        jobs = listOf(
            scope.launch { snapshotFlow { controller.windowRequest }.collectLatest { request -> request?.let { applyRequest(it) } } },
            scope.launch {
                hub.keepAwake.collect { on ->
                    params.flags = params.flags.with(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, on)
                    relayout()
                }
            },
        )
    }

    fun hide() {
        val view = root ?: return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        main.removeCallbacks(fullScreenCheck)
        root = null
        runCatching { windowManager.removeViewImmediate(view) }
        hub.stop()
        controller.collapse()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    fun destroy() {
        hide()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    fun onConfigurationChanged() {
        updateGeometry()
        updateHidden()
    }

    /** Hides the island for a screenshot of what lies below it, and shows it again. */
    fun hideForScreenshot(hidden: Boolean) {
        screenshotHidden = hidden
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        updateHidden()
    }

    private val main = Handler(Looper.getMainLooper())

    private val fullScreenCheck = Runnable {
        // While the island is open or in the hand, it stays: it must never close under the finger.
        if (controller.expanded || controller.dragging) return@Runnable
        statusBarVisible = false
        updateHidden()
    }

    private fun updateHidden() {
        val landscape = service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Only trusted once the status bar has been seen: some systems never report it to overlays.
        val fullScreen = statusBarSeen && !statusBarVisible
        val hidden = landscape || fullScreen || screenshotHidden
        if (hidden && !controller.hidden) controller.collapse()
        controller.hidden = hidden
    }

    private fun updateGeometry() {
        val density = service.resources.displayMetrics.density
        val metrics = runCatching { windowManager.currentWindowMetrics }.getOrNull()
        val width = metrics?.bounds?.width() ?: service.resources.displayMetrics.widthPixels
        val height = metrics?.bounds?.height() ?: service.resources.displayMetrics.heightPixels
        val insets = metrics?.windowInsets
        val statusBar = insets?.getInsets(WindowInsets.Type.statusBars())?.top?.takeIf { it > 0 } ?: statusBarHeight() ?: (32 * density).roundToInt()
        val cutout = insets?.displayCutout ?: service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.cutout
        val hole = cutout?.boundingRectTop?.takeIf { !it.isEmpty && it.top < statusBar }
        // A camera in a corner cannot be surrounded by the island: then it sits in the middle, as on Macs without a notch.
        val centred = hole != null && abs(hole.centerX() - width / 2) < 48 * density
        // The reported cutout is often larger than the camera itself (on Pixels nearly the whole status bar):
        // the island stays a slim pill around the lens, as tall as the status bar's icons, never taller.
        val pad = (4 * density).roundToInt()
        val maxHole = (MAX_HOLE_DP * density).roundToInt()
        val holeHeight = if (centred) minOf(hole.height(), hole.width(), maxHole) else 0
        val holeWidth = if (centred) minOf(hole.width(), maxHole) else 0
        val pillHeight = (holeHeight + 2 * pad).coerceIn((MIN_PILL_DP * density).roundToInt(), (MAX_PILL_DP * density).roundToInt())
        val centerY = if (centred) hole.centerY() else statusBar / 2
        controller.geometry = IslandGeometry(
            screenWidth = width,
            screenHeight = height,
            centerX = if (centred) hole.centerX() else width / 2,
            holeWidth = holeWidth,
            holeHeight = holeHeight,
            pillTop = (centerY - pillHeight / 2).coerceAtLeast((2 * density).roundToInt()),
            pillHeight = pillHeight,
            density = density,
        )
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int? {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) service.resources.getDimensionPixelSize(id) else null
    }

    /**
     * Sizes the window around the island, centred on it. A larger window comes at once, before the island
     * grows into it; a smaller one only once the island has shrunk, so nothing is cut off on the way.
     */
    private suspend fun applyRequest(request: WindowRequest) {
        val geometry = controller.geometry ?: return
        val width = request.width.coerceAtMost(geometry.screenWidth)
        val shrinking = width < params.width || request.height < params.height
        if (shrinking) delay(SHRINK_DELAY_MS)
        params.width = width
        params.height = request.height
        params.x = (geometry.centerX - width / 2).coerceIn(0, geometry.screenWidth - width)
        params.y = 0
        params.flags = params.flags
            .with(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, !request.touchable)
            // Only the opened island needs to hear of touches elsewhere (they close it).
            .with(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, request.large)
        relayout()
    }

    private fun relayout() {
        val view = root ?: return
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private companion object {
        /** Longer than the island's spring takes to settle, so the window never cuts it off while it shrinks. */
        const val SHRINK_DELAY_MS = 650L

        /** A front camera's lens is about 3 to 4 mm; a larger cutout is padding around it. */
        const val MAX_HOLE_DP = 24f
        const val MIN_PILL_DP = 24f
        const val MAX_PILL_DP = 30f

        /** A full-screen app keeps the status bar hidden at least this long before the island goes. */
        const val FULL_SCREEN_DELAY_MS = 600L
    }
}

private fun Int.with(flag: Int, on: Boolean) = if (on) this or flag else this and flag.inv()

/** Catches a touch outside the window while the island is open: it closes the island. */
@SuppressLint("ViewConstructor")
private class IslandRoot(context: Context, private val onOutside: () -> Unit) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return false
        }
        return super.dispatchTouchEvent(event)
    }
}
