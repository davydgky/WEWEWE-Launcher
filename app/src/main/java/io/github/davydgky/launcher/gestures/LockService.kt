package io.github.davydgky.launcher.gestures

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import io.github.davydgky.launcher.BuildConfig
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.island.IslandActions
import io.github.davydgky.launcher.island.IslandController
import io.github.davydgky.launcher.island.QuickToggle
import android.provider.MediaStore
import io.github.davydgky.launcher.island.IslandWindow
import io.github.davydgky.launcher.island.QuickNoteActivity
import io.github.davydgky.launcher.island.ScreenText
import io.github.davydgky.launcher.island.ScreenTextActivity
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.permissions.Permission
import io.github.davydgky.launcher.permissions.SystemPages
import io.github.davydgky.launcher.ui.SearchActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** The island app is installed: the launcher leaves the island to it, so there are never two. */
    private val islandAppInstalled = MutableStateFlow(false)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            islandAppInstalled.value = isIslandAppInstalled()
        }
    }

    override fun onServiceConnected() {
        instance = this
        val app = launcherApp
        val window = IslandWindow(this, app.island, this)
        island = window
        if (BuildConfig.IS_LAUNCHER) {
            islandAppInstalled.value = isIslandAppInstalled()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            registerReceiver(packageReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            // The island app has no home screen that would look for updates; the service runs all along.
            app.updater.onAppStarted()
        }
        scope.launch {
            combine(app.settings.prefs, islandAppInstalled) { prefs, otherApp ->
                IslandPrefs(
                    prefs.islandEnabled && !otherApp, prefs.islandNotifications, prefs.islandSystemEvents, prefs.islandIdleVisible, prefs.islandWidth,
                    QuickToggle.fromIds(prefs.islandToggles ?: QuickToggle.DEFAULT.map { it.id }), prefs.islandPillHeight, prefs.islandOffset, prefs.islandMascot,
                )
            }
                .distinctUntilChanged()
                .collect { prefs ->
                    app.island.notificationPeeks = prefs.notifications
                    app.island.systemPeeks = prefs.systemEvents
                    window.controller.idleVisible = prefs.idleVisible
                    window.controller.toggles = prefs.toggles
                    window.controller.mascot = prefs.mascot
                    window.setTuning(prefs.pillHeight, prefs.offset)
                    window.controller.sideWidth = when (prefs.width) {
                        2 -> IslandController.SIDE_WIDE
                        1 -> IslandController.SIDE_NORMAL
                        else -> IslandController.SIDE_NARROW
                    }
                    // After a crash loop the app starts in safe mode: then the island stays away until the next start.
                    if (prefs.enabled && !app.crashGuard.safeMode) window.show() else window.hide()
                }
        }
    }

    private fun isIslandAppInstalled(): Boolean =
        runCatching { packageManager.getPackageInfo(BuildConfig.ISLAND_PACKAGE, 0) }.isSuccess

    private data class IslandPrefs(
        val enabled: Boolean,
        val notifications: Boolean,
        val systemEvents: Boolean,
        val idleVisible: Boolean,
        val width: Int,
        val toggles: List<QuickToggle>,
        val pillHeight: Float,
        val offset: Float,
        val mascot: Boolean,
    )

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
        if (BuildConfig.IS_LAUNCHER) runCatching { unregisterReceiver(packageReceiver) }
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

    override fun perform(toggle: QuickToggle) {
        when (toggle) {
            QuickToggle.Camera -> {
                // Over the lock screen the camera opens without unlocking, as with the power button.
                val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                startSafely(Intent(if (locked) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            }
            QuickToggle.Screenshot -> {
                // The island closes first, so it is not in the picture.
                island?.hideForScreenshot(true)
                main.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                    main.postDelayed({ island?.hideForScreenshot(false) }, SCREENSHOT_DELAY_MS)
                }, SCREENSHOT_DELAY_MS)
            }
            QuickToggle.ScreenText -> readScreen()
            QuickToggle.Reminder -> quickNote()
            QuickToggle.Lock -> lock()
            QuickToggle.Wifi -> startSafely(Intent(Settings.Panel.ACTION_WIFI))
            QuickToggle.Bluetooth -> startSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            QuickToggle.Volume -> getSystemService(android.media.AudioManager::class.java)
                .adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI)
            QuickToggle.Timer -> startSafely(Intent(android.provider.AlarmClock.ACTION_SET_TIMER))
            QuickToggle.Alarm -> startSafely(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
            QuickToggle.QuickSettings -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            QuickToggle.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            // Switches of the island itself, handled there.
            QuickToggle.Torch, QuickToggle.DoNotDisturb, QuickToggle.Awake -> Unit
        }
    }

    override fun openReminders() = startSafely(
        Intent(this, io.github.davydgky.launcher.island.IslandRemindersActivity::class.java)
            .putExtra(io.github.davydgky.launcher.island.IslandRemindersActivity.EXTRA_MODE, io.github.davydgky.launcher.island.IslandRemindersActivity.MODE_ALL),
    )

    override fun openNotificationAccess() {
        SystemPages.open(this, Permission.NotificationAccess)
    }

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

    }
}
