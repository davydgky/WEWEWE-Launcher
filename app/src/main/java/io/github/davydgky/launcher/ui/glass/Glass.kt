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
    /** How strong the glass is drawn, as the user set it. */
    val style: GlassStyle = GlassStyle(),
)

/**
 * The user's Liquid Glass settings, each a factor on the launcher's own look (1 = as designed):
 * [blur] of what lies behind, [tint] (how opaque surfaces and the dimming behind panels are),
 * [refraction] of the lens at the edges, the [highlight] rim, [pageFill] of the boxes and cards on the
 * organizer and the App Library, and [statusBarBlur] under the status bar on those pages (0 = off).
 */
@Immutable
data class GlassStyle(
    val blur: Float = 1f,
    val tint: Float = 1f,
    val refraction: Float = 1f,
    val highlight: Boolean = true,
    val pageFill: Float = 1f,
    val statusBarBlur: Float = 1f,
) {
    companion object {
        /** Plain translucent surfaces: no blur, no lens, no rim, a little more opaque to stay readable. */
        val Off = GlassStyle(blur = 0f, tint = 1.4f, refraction = 0f, highlight = false, pageFill = 1f, statusBarBlur = 0f)
        val Subtle = GlassStyle(blur = 0.6f, tint = 0.8f, refraction = 0.4f, highlight = true, pageFill = 0.7f, statusBarBlur = 0.5f)
        val Default = GlassStyle()
        val Strong = GlassStyle(blur = 1.6f, tint = 1.2f, refraction = 1.6f, highlight = true, pageFill = 1.6f, statusBarBlur = 1.4f)

        const val MAX_FACTOR = 2f
        const val MAX_PAGE_FILL = 3f
    }
}

/** The Liquid Glass settings as saved. */
fun io.github.davydgky.launcher.data.LauncherPrefs.glassStyle() = GlassStyle(
    blur = glassBlur,
    tint = glassTint,
    refraction = glassRefraction,
    highlight = glassHighlight,
    pageFill = glassPageFill,
    statusBarBlur = glassStatusBar,
)

/** The Liquid Glass settings in effect; the launcher provides the user's, everything else gets the defaults. */
val LocalGlassStyle = staticCompositionLocalOf { GlassStyle() }

/** [base] alpha of a box or card on the organizer or the App Library, with the user's fill applied. */
@Composable
fun pageFillAlpha(base: Float): Float = (base * LocalGlassStyle.current.pageFill).coerceIn(0f, 1f)

private fun Color.timesAlpha(factor: Float) = copy(alpha = (alpha * factor).coerceIn(0f, 1f))

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
fun glassColors(style: GlassStyle = LocalGlassStyle.current): GlassColors {
    val base = if (isSystemInDarkTheme()) DarkGlass else LightGlass
    return base.copy(surface = base.surface.timesAlpha(style.tint), scrim = base.scrim.timesAlpha(style.tint), style = style)
}

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
        val radius = 8.dp.toPx() * colors.style.blur
        if (radius > 0f) blur(radius)
        val height = (refraction.toPx() * colors.style.refraction).coerceAtMost(size.minDimension / 2f)
        if (height > 0f) lens(refractionHeight = height, refractionAmount = height * 2f, depthEffect = true)
    },
    highlight = if (colors.style.highlight) { { Highlight.Default } } else null,
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
        effects = {
            val radius = 32.dp.toPx() * colors.style.blur
            if (radius > 0f) blur(radius)
        },
        highlight = null,
        shadow = null,
        exportedBackdrop = exportedBackdrop,
        onDrawSurface = { drawRect(colors.scrim) },
    )
