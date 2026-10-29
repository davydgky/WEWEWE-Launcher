package io.github.davydgky.launcher

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.davydgky.launcher.search.ResultActions
import io.github.davydgky.launcher.search.SearchViewModel
import io.github.davydgky.launcher.ui.ImeController
import io.github.davydgky.launcher.ui.LauncherScreen
import io.github.davydgky.launcher.ui.LauncherTheme
import io.github.davydgky.launcher.ui.LocalLightWallpaper
import io.github.davydgky.launcher.widgets.WidgetAdder
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The home screen. Stays alive while other apps run, so the search is always one swipe away. */
class MainActivity : ComponentActivity() {

    private val viewModel: SearchViewModel by viewModels()
    private val homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var lightWallpaper by mutableStateOf(false)
    private var searchActive = false
    private lateinit var widgetAdder: WidgetAdder
    private var wallpaperBlur = -1
    private val wallpaperManager by lazy { WallpaperManager.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ime = ImeController(window.decorView, launcherApp.perf)
        val actions = ResultActions(this)
        widgetAdder = WidgetAdder(this)
        // Back on the home screen does nothing; the search panel handles back itself.
        onBackPressedDispatcher.addCallback(this) { }
        // Asks the wallpaper service, which may first extract the colours: never on the main thread.
        lifecycleScope.launch {
            updateWallpaperColors(withContext(Dispatchers.IO) { wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) })
        }
        wallpaperManager.addOnColorsChangedListener(wallpaperListener, Handler(Looper.getMainLooper()))

        setContent {
            CompositionLocalProvider(LocalLightWallpaper provides lightWallpaper) {
                LauncherTheme {
                    LauncherScreen(
                        viewModel = viewModel,
                        actions = actions,
                        ime = ime,
                        homePressed = homePressed,
                        addWidget = widgetAdder::add,
                        onSearchActive = { active ->
                            searchActive = active
                            updateSystemBars()
                        },
                        onSearchProgress = ::blurWallpaper,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        launcherApp.widgets.startListening()
        launcherApp.updater.isAppVisible = true
    }

    override fun onResume() {
        super.onResume()
        // Checks at most every few hours; the home screen resumes often.
        launcherApp.updater.onAppStarted()
        launcherApp.updater.onAppResumed()
        // Gemini Nano describes new apps for the search, only while the home screen is in use.
        lifecycleScope.launch { if (launcherApp.settings.prefs.first().aiAnswers) launcherApp.appKnowledge.resume() }
    }

    override fun onPause() {
        launcherApp.appKnowledge.pause()
        super.onPause()
    }

    override fun onStop() {
        launcherApp.widgets.stopListening()
        launcherApp.updater.isAppVisible = false
        super.onStop()
    }

    @Deprecated("Widget configuration screens still report through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        if (!widgetAdder.onActivityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Blurs the wallpaper behind the search with the system's cross-window blur. Every new radius makes
     * the window lay itself out again, so it changes in a few steps only, not on every frame.
     */
    private fun blurWallpaper(progress: Float) {
        if (!windowManager.isCrossWindowBlurEnabled) return
        val step = ceil(progress.coerceIn(0f, 1f) * BLUR_STEPS)
        val radius = (step / BLUR_STEPS * MAX_WALLPAPER_BLUR_DP * resources.displayMetrics.density).toInt()
        if (radius == wallpaperBlur) return
        wallpaperBlur = radius
        window.setBackgroundBlurRadius(radius)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing home while the home screen shows: close the search or go back to the first page.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) homePressed.tryEmit(Unit)
    }

    override fun onDestroy() {
        wallpaperManager.removeOnColorsChangedListener(wallpaperListener)
        super.onDestroy()
    }

    private val wallpaperListener = WallpaperManager.OnColorsChangedListener { colors, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) updateWallpaperColors(colors)
    }

    /** Status bar icons and labels turn dark on light wallpapers. */
    private fun updateWallpaperColors(colors: WallpaperColors?) {
        lightWallpaper = colors != null && colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        updateSystemBars()
    }

    /** Over the wallpaper the bars follow the wallpaper; over the search panel they follow the theme. */
    private fun updateSystemBars() {
        val lightBackground = if (searchActive) !resources.configuration.isNightModeActive else lightWallpaper
        val style = if (lightBackground) {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private companion object {
        const val MAX_WALLPAPER_BLUR_DP = 30f
        const val BLUR_STEPS = 4f
    }
}
