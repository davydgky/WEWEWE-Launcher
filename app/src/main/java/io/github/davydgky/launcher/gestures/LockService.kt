package io.github.davydgky.launcher.gestures

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.island.IslandActions
import io.github.davydgky.launcher.island.IslandWindow
import io.github.davydgky.launcher.island.QuickNoteActivity
import io.github.davydgky.launcher.island.ScreenText
import io.github.davydgky.launcher.island.ScreenTextActivity
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.ui.SearchActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The accessibility service of WEWEWE. It turns the screen off on a double tap on the home screen, and
 * it hosts the Dynamic Island: Android lets only accessibility services draw over the status bar,
 * around the camera. It reads nothing on the screen and receives no events; a screenshot is only taken
 * when "Text on screen" is tapped in the island.
 */
class LockService : AccessibilityService(), IslandActions {

    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private var island: IslandWindow? = null

    override fun onServiceConnected() {
        instance = this
        val app = launcherApp
        val window = IslandWindow(this, app.island, this)
        island = window
        scope.launch {
            app.settings.prefs
                .map { IslandPrefs(it.islandEnabled, it.islandNotifications, it.islandSystemEvents, it.islandIdleVisible) }
                .distinctUntilChanged()
                .collect { prefs ->
                    app.island.notificationPeeks = prefs.notifications
                    app.island.systemPeeks = prefs.systemEvents
                    window.controller.idleVisible = prefs.idleVisible
                    if (prefs.enabled) window.show() else window.hide()
                }
        }
    }

    private data class IslandPrefs(val enabled: Boolean, val notifications: Boolean, val systemEvents: Boolean, val idleVisible: Boolean)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        island?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        if (instance === this) instance = null
        island?.destroy()
        island = null
        scope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // region What the island asks for

    override fun openSearch() {
        val options = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0).toBundle()
        startSafely(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_FROM_ISLAND, true), options)
    }

    override fun send(intent: PendingIntent?, fallbackPackage: String?) {
        val sent = intent != null && runCatching {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
                .toBundle()
            intent.send(this, 0, null, null, null, null, options)
        }.onFailure { Log.w(TAG, "Cannot open", it) }.isSuccess
        if (!sent && fallbackPackage != null) launchApp(fallbackPackage)
    }

    override fun launchApp(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { startSafely(it) }
    }

    override fun openUrl(url: String) = startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    override fun startIntent(intent: Intent) = startSafely(intent)

    override fun lock() {
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    override fun toggleTorch() {
        launcherApp.torch.toggle()
    }

    override fun quickNote() = startSafely(Intent(this, QuickNoteActivity::class.java))

    override fun openNotificationAccess() = startSafely(launcherApp.notifications.accessIntent())

    /** Photographs the screen without the island, reads its text on the device and shows it to select. */
    override fun readScreen() {
        val window = island ?: return
        window.hideForScreenshot(true)
        // The island closes and hides first, so it is not in the picture.
        main.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    window.hideForScreenshot(false)
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (bitmap == null) return failed()
                    launcherApp.island.confirm(getString(R.string.island_reading), R.drawable.ic_text_scan)
                    ScreenText.recognize(bitmap) { text ->
                        bitmap.recycle()
                        if (text.isNullOrBlank()) {
                            Toast.makeText(this@LockService, R.string.screen_text_none, Toast.LENGTH_SHORT).show()
                        } else {
                            ScreenText.last = text
                            startSafely(Intent(this@LockService, ScreenTextActivity::class.java))
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    window.hideForScreenshot(false)
                    Log.w(TAG, "Screenshot failed: $errorCode")
                    failed()
                }
            })
        }, SCREENSHOT_DELAY_MS)
    }

    private fun failed() {
        Toast.makeText(this, R.string.screen_text_failed, Toast.LENGTH_SHORT).show()
    }

    private fun startSafely(intent: Intent, options: android.os.Bundle? = null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent, options) }.onFailure { Log.w(TAG, "Cannot start $intent", it) }
    }

    // endregion

    companion object {
        private const val TAG = "LockService"
        private const val SCREENSHOT_DELAY_MS = 350L

        @Volatile private var instance: LockService? = null

        /** Locks the screen; false if the service is not switched on. */
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(context, LockService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** The system page where the service is switched on. */
        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
