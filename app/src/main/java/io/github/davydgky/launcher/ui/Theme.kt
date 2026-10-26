package io.github.davydgky.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Material You colours from the wallpaper, as in Velo.
    val colorScheme = remember(dark, context) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Whether the wallpaper is light enough that labels on it should be dark. */
val LocalLightWallpaper = staticCompositionLocalOf { false }

/** Colour of text drawn directly on the wallpaper. */
@Composable
fun wallpaperTextColor(): Color = if (LocalLightWallpaper.current) Color(0xFF1B1B1F) else Color.White
