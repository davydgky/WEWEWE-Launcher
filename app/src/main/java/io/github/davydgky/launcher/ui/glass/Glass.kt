package io.github.davydgky.launcher.ui.glass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangularShape

/**
 * Colours of glass surfaces, taken from Velo. Content colours are chosen for the glass itself, not for
 * what lies behind it, so text stays readable on any wallpaper.
 */
@Immutable
data class GlassColors(
    /** Translucent fill drawn over the refracted backdrop. */
    val surface: Color,
    val content: Color,
    val secondaryContent: Color,
    /** Subtle fill for controls placed on glass (rows, tiles). */
    val fill: Color,
    val hairline: Color,
    /** Tint over the blurred home screen behind the search. */
    val scrim: Color,
)

val LightGlass = GlassColors(
    surface = Color.White.copy(alpha = 0.62f),
    content = Color(0xFF111114),
    secondaryContent = Color(0xFF111114).copy(alpha = 0.58f),
    fill = Color(0xFF111114).copy(alpha = 0.06f),
    hairline = Color(0xFF111114).copy(alpha = 0.10f),
    scrim = Color.White.copy(alpha = 0.28f),
)

val DarkGlass = GlassColors(
    surface = Color(0xFF1C1C1E).copy(alpha = 0.58f),
    content = Color.White,
    secondaryContent = Color.White.copy(alpha = 0.62f),
    fill = Color.White.copy(alpha = 0.09f),
    hairline = Color.White.copy(alpha = 0.12f),
    scrim = Color.Black.copy(alpha = 0.36f),
)

val LocalGlassColors = staticCompositionLocalOf { DarkGlass }

@Composable
fun glassColors(): GlassColors = if (isSystemInDarkTheme()) DarkGlass else LightGlass

/**
 * A Liquid Glass surface sampling [backdrop]: vibrancy, light blur and lens refraction at the edges,
 * with a highlight rim and a soft shadow, as in Velo.
 */
fun Modifier.glass(
    backdrop: Backdrop,
    shape: RoundedRectangularShape,
    colors: GlassColors,
    refraction: Dp = 16.dp,
    exportedBackdrop: LayerBackdrop? = null,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(8.dp.toPx())
        val height = refraction.toPx().coerceAtMost(size.minDimension / 2f)
        lens(refractionHeight = height, refractionAmount = height * 2f, depthEffect = true)
    },
    highlight = { Highlight.Default },
    shadow = { Shadow.Default.copy(alpha = 0.6f) },
    exportedBackdrop = exportedBackdrop,
    onDrawSurface = { drawRect(colors.surface) },
)

/** [glass] only while [active]; closed panels are never seen and should cost nothing to draw. */
fun Modifier.glassWhen(
    active: Boolean,
    backdrop: Backdrop,
    shape: RoundedRectangularShape,
    colors: GlassColors,
    refraction: Dp = 16.dp,
): Modifier = if (active) glass(backdrop, shape, colors, refraction) else this

/**
 * Full-screen frosted layer behind the search: the home screen blurred, with the glass scrim on top,
 * faded in by [progress] (0..1). The blur itself stays the same while it fades, so it is computed once
 * and not again on every frame of the animation. Exports itself so the glass panels above can refract it.
 */
fun Modifier.frostedBackdrop(
    backdrop: Backdrop,
    colors: GlassColors,
    progress: () -> Float,
    exportedBackdrop: LayerBackdrop? = null,
): Modifier = this
    .graphicsLayer { alpha = progress().coerceIn(0f, 1f) }
    .drawBackdrop(
        backdrop = backdrop,
        shape = { RectangleShape },
        effects = { blur(32.dp.toPx()) },
        highlight = null,
        shadow = null,
        exportedBackdrop = exportedBackdrop,
        onDrawSurface = { drawRect(colors.scrim) },
    )
