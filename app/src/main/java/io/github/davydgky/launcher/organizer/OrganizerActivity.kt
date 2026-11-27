package io.github.davydgky.launcher.organizer

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.davydgky.launcher.data.LauncherPrefs
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.ui.LauncherTheme
import io.github.davydgky.launcher.ui.LocalLightWallpaper
import io.github.davydgky.launcher.ui.glass.LocalGlassStyle
import io.github.davydgky.launcher.ui.glass.LocalWallpaperBlur
import io.github.davydgky.launcher.ui.glass.glassStyle
import io.github.davydgky.launcher.ui.glass.sidePageScrim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The organizer, as on the launcher's page left of the home screen: calendar, agenda, the reminders
 * Gemini Nano suggests from messages, and the reminder lists. Here as a screen of its own, over the
 * blurred wallpaper, for the island app and for the island's "All reminders".
 */
class OrganizerActivity : ComponentActivity() {

    private var lightWallpaper by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherApp.organizer.start()
        val blur = windowManager.isCrossWindowBlurEnabled
        if (blur) window.setBackgroundBlurRadius((24 * resources.displayMetrics.density).toInt())
        lifecycleScope.launch {
            val colors = withContext(Dispatchers.IO) { WallpaperManager.getInstance(this@OrganizerActivity).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }
            lightWallpaper = colors != null && colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
            val style = if (lightWallpaper) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
        enableEdgeToEdge()
        setContent {
            val prefs by launcherApp.settings.prefs.collectAsStateWithLifecycle(initialValue = LauncherPrefs())
            CompositionLocalProvider(
                LocalGlassStyle provides prefs.glassStyle(),
                LocalLightWallpaper provides lightWallpaper,
                LocalWallpaperBlur provides blur,
            ) {
                LauncherTheme {
                    val scrim = sidePageScrim()
                    val content = if (lightWallpaper) Color(0xFF1B1B1F) else Color.White
                    CompositionLocalProvider(LocalContentColor provides content) {
                        Box(Modifier.fillMaxSize().background(scrim)) {
                            OrganizerPage(launcherApp.organizer)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        launcherApp.organizer.syncWithPeer()
        // As on the launcher's page: Gemini Nano drafts reminders from new messages while this is open.
        lifecycleScope.launch {
            val prefs = launcherApp.settings.prefs.first()
            if (prefs.aiAnswers && prefs.todoSuggestions && !launcherApp.crashGuard.safeMode) launcherApp.todoSuggester.resume()
        }
    }

    override fun onPause() {
        launcherApp.todoSuggester.pause()
        super.onPause()
    }
}
