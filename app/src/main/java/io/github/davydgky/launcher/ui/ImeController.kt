package io.github.davydgky.launcher.ui

import android.graphics.Insets
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
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

    private var measureFrom = 0L
    private var measuring = false

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
        if (controller != null || requested) return
        val insetsController = view.windowInsetsController ?: return
        requested = true
        val signal = CancellationSignal()
        cancellation = signal
        // Duration -1: the animation has no fixed length, it follows the finger.
        insetsController.controlWindowInsetsAnimation(WindowInsets.Type.ime(), -1, null, signal, listener)
    }

    fun setFraction(value: Float) {
        fraction = value.coerceIn(0f, 1f)
        apply()
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
            shown -> view.windowInsetsController?.show(WindowInsets.Type.ime())
            else -> view.windowInsetsController?.hide(WindowInsets.Type.ime())
        }
    }

    /** Drops control without moving the keyboard, e.g. when the activity goes away. */
    fun cancel() {
        fraction = 0f
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
        if (measuring && bottom > hidden) {
            measuring = false
            val start = measureFrom
            // The new keyboard position reaches the screen with the next frame.
            Choreographer.getInstance().postFrameCallback {
                perf.record(Metric.Keyboard, (SystemClock.uptimeMillis() - start).toDouble())
            }
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
            val wanted = pendingFinish ?: (fraction > 0.5f)
            Log.d(TAG, "Keyboard control refused; showing it normally: $wanted")
            reset()
            // The system refused control (another animation was running); fall back to a plain request.
            if (wanted) view.windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }
}
