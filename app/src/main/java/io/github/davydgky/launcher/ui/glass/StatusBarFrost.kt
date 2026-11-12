package io.github.davydgky.launcher.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import io.github.davydgky.launcher.ui.LocalLightWallpaper

/** How far below the status bar the blur fades out. */
private val FADE = 20.dp

/**
 * Content that scrolls up to the top edge of the screen, behind the status bar. Under the clock and the
 * status icons it turns blurred and slightly tinted, fading back to sharp just below the bar, so they
 * stay readable over whatever passes beneath.
 *
 * The sharp content fades out at the top exactly as its blurred copy fades in: the home screen is
 * transparent around its text (the wallpaper is another window), so a blurred copy over it alone would
 * still let the sharp text show through.
 */
@Composable
fun BehindStatusBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tint = if (LocalLightWallpaper.current) Color.White.copy(alpha = 0.32f) else Color.Black.copy(alpha = 0.22f)
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawTopFade(statusBar, BlendMode.DstOut)
                }
                // Inside the fade above: the copy the blur samples is the whole, sharp content.
                .layerBackdrop(backdrop),
        ) { content() }
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusBar + FADE)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawTopFade(statusBar, BlendMode.DstIn)
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = { blur(14.dp.toPx()) },
                    highlight = null,
                    shadow = null,
                    onDrawSurface = { drawRect(tint) },
                ),
        )
    }
}

/** Fully there over the status bar, gone [FADE] below it; [mode] keeps (DstIn) or removes (DstOut) that part. */
private fun ContentDrawScope.drawTopFade(statusBar: Dp, mode: BlendMode) {
    val solid = statusBar.toPx()
    val end = solid + FADE.toPx()
    drawRect(
        Brush.verticalGradient(0f to Color.Black, (solid / end) to Color.Black, 1f to Color.Transparent, endY = end),
        size = size.copy(height = minOf(end, size.height)),
        blendMode = mode,
    )
}
