package io.github.davydgky.launcher.ui

import android.graphics.Insets
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import io.github.davydgky.launcher.perf.Metric
import io.github.davydgky.launcher.perf.PerfStats
import kotlin.math.roundToInt

/**
 * Moves the keyboard with the user's finger.
 *
 * [begin] takes control of the keyboard through [android.view.WindowInsetsController.controlWindowInsetsAnimation];
 * from then on every [setFraction] call places it between hidden (0) and shown (1) in the same frame
 * as the search panel. The text field already has focus while the home screen shows (without a
 * keyboard), so the connection to the keyboard app exists before the gesture starts and only the
 * keyboard window has to appear.
 */
class ImeController(private val view: View, private val perf: PerfStats) {

    private var controller: WindowInsetsAnimationController? = null
    private var cancellation: CancellationSignal? = null
    private var requested = false
    private var fraction = 0f
    /** finish() was called before the keyboard was ready; holds the requested end state. */
    private var pendingFinish: Boolean? = null
    /** Where the keyboard is headed once the finger let go; null while it follows the finger. */
    private var goal: Boolean? = null
    /** We gave up control ourselves; the system's cancel callback must not move the keyboard. */
    private var cancelledByUs = false

    private var measureFrom = 0L
    private var measuring = false

    init {
        // Also measures when the system refused control and the keyboard came up on its own.
        view.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                if (measuring && insets.getInsets(WindowInsets.Type.ime()).bottom > 0) recordVisible()
                return insets
            }
        })
    }

    val isKeyboardVisible: Boolean
        get() = view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    /**
     * Takes control of the keyboard, starting at [startFraction]. [gestureUptimeMs] is when the
     * swipe was recognised; the time until the keyboard is visible is recorded as [Metric.Keyboard].
     */
    fun begin(startFraction: Float, gestureUptimeMs: Long? = null) {
        if (gestureUptimeMs != null) {
            measureFrom = gestureUptimeMs
            measuring = true
        }
        fraction = startFraction
        pendingFinish = null
        goal = null
        if (controller != null || requested) return
        val insetsController = view.windowInsetsController ?: return
        requested = true
        cancelledByUs = false
        val signal = CancellationSignal()
        cancellation = signal
        // Duration -1: the animation has no fixed length, it follows the finger.
        try {
            insetsController.controlWindowInsetsAnimation(WindowInsets.Type.ime(), -1, null, signal, listener)
        } catch (e: IllegalStateException) {
            // A keyboard animation is being cancelled right now: no control this time, the plain
            // request in finish() still brings the keyboard.
            Log.w(TAG, "Keyboard busy", e)
            reset()
        }
    }

    fun setFraction(value: Float) {
        fraction = value.coerceIn(0f, 1f)
        apply()
    }

    /** The finger let go; the keyboard will end up [shown] or hidden, whatever happens on the way. */
    fun aim(shown: Boolean) {
        goal = shown
    }

    /**
     * Shows or hides the keyboard with the system's own animation, for opening and closing without a
     * finger (button, home, back): no waiting for control, nothing to jump.
     */
    fun animate(shown: Boolean) {
        if (controller != null || requested) cancel()
        if (shown) request { show(WindowInsets.Type.ime()) } else if (isKeyboardVisible) request { hide(WindowInsets.Type.ime()) }
    }

    /** Ends the controlled animation with the keyboard [shown] or hidden. */
    fun finish(shown: Boolean) {
        fraction = if (shown) 1f else 0f
        if (!shown) measuring = false
        val current = controller
        when {
            current != null -> {
                apply()
                current.finish(shown)
                controller = null
            }
            requested -> pendingFinish = shown
            shown -> request { show(WindowInsets.Type.ime()) }
            else -> request { hide(WindowInsets.Type.ime()) }
        }
    }

    /** Drops control without moving the keyboard, e.g. when the activity goes away. */
    fun cancel() {
        fraction = 0f
        cancelledByUs = true
        cancellation?.cancel()
        reset()
        measuring = false
    }

    private fun apply() {
        val current = controller ?: return
        if (current.isCancelled || current.isFinished) return
        val hidden = current.hiddenStateInsets.bottom
        val shown = current.shownStateInsets.bottom
        val bottom = (hidden + (shown - hidden) * fraction).roundToInt()
        current.setInsetsAndAlpha(Insets.of(0, 0, 0, bottom), 1f, fraction)
        if (measuring && bottom > hidden) recordVisible()
    }

    private fun recordVisible() {
        measuring = false
        val start = measureFrom
        // The new keyboard position reaches the screen with the next frame.
        Choreographer.getInstance().postFrameCallback {
            perf.record(Metric.Keyboard, (SystemClock.uptimeMillis() - start).toDouble())
        }
    }

    /**
     * A plain show or hide. The system throws if an animation of the keyboard is being cancelled at
     * that moment; the keyboard then just stays where it is instead of taking the launcher down.
     */
    private inline fun request(action: android.view.WindowInsetsController.() -> Unit) {
        val insets = view.windowInsetsController ?: return
        try {
            insets.action()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Keyboard busy", e)
        }
    }

    private companion object {
        const val TAG = "LauncherIme"
    }

    private fun reset() {
        controller = null
        cancellation = null
        requested = false
        pendingFinish = null
        goal = null
    }

    private val listener = object : WindowInsetsAnimationControlListener {
        override fun onReady(controller: WindowInsetsAnimationController, types: Int) {
            requested = false
            this@ImeController.controller = controller
            Log.d(TAG, "Keyboard control ready: ${controller.hiddenStateInsets.bottom} -> ${controller.shownStateInsets.bottom}")
            val finish = pendingFinish
            pendingFinish = null
            if (finish != null) finish(finish) else apply()
        }

        override fun onFinished(controller: WindowInsetsAnimationController) {
            if (this@ImeController.controller === controller || this@ImeController.controller == null) reset()
        }

        override fun onCancelled(controller: WindowInsetsAnimationController?) {
            if (cancelledByUs) {
                cancelledByUs = false
                return
            }
            // Where the user was headed: a closing search never brings the keyboard back.
            val wanted = pendingFinish ?: goal ?: (fraction > 0.5f)
            Log.d(TAG, "Keyboard control refused; showing it normally: $wanted")
            reset()
            // The system refused control (another animation was running); fall back to a plain request.
            // Not from inside this callback: the cancelled animation is still being torn down, and
            // starting a new one now throws (Android 17). Right after it, the request is accepted.
            view.post {
                if (controller != null || requested) return@post
                if (wanted) request { show(WindowInsets.Type.ime()) } else if (isKeyboardVisible) request { hide(WindowInsets.Type.ime()) }
            }
        }
    }
}
