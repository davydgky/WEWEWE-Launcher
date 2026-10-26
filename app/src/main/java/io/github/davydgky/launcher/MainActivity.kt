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
import io.github.davydgky.launcher.search.ResultActions
import io.github.davydgky.launcher.search.SearchViewModel
import io.github.davydgky.launcher.ui.ImeController
import io.github.davydgky.launcher.ui.LauncherScreen
import io.github.davydgky.launcher.ui.LauncherTheme
import io.github.davydgky.launcher.ui.LocalLightWallpaper
import kotlinx.coroutines.flow.MutableSharedFlow

/** The home screen. Stays alive while other apps run, so the search is always one swipe away. */
class MainActivity : ComponentActivity() {

    private val viewModel: SearchViewModel by viewModels()
    private val homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var lightWallpaper by mutableStateOf(false)
    private var searchActive = false
    private val wallpaperManager by lazy { WallpaperManager.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ime = ImeController(window.decorView, launcherApp.perf)
        val actions = ResultActions(this)
        // Back on the home screen does nothing; the search panel handles back itself.
        onBackPressedDispatcher.addCallback(this) { }
        updateWallpaperColors(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
        wallpaperManager.addOnColorsChangedListener(wallpaperListener, Handler(Looper.getMainLooper()))

        setContent {
            CompositionLocalProvider(LocalLightWallpaper provides lightWallpaper) {
                LauncherTheme {
                    LauncherScreen(viewModel, actions, ime, homePressed) { active ->
                        searchActive = active
                        updateSystemBars()
                    }
                }
            }
        }
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
}
