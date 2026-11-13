package io.github.davydgky.launcher.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.ui.glass.GlassStyle
import io.github.davydgky.launcher.ui.glass.glass
import io.github.davydgky.launcher.ui.glass.glassColors
import kotlin.math.roundToInt

/**
 * Liquid Glass as the user wants it: a live preview, four presets, and a slider for each part. Sliders
 * change the preview while they move and are saved when let go.
 */
@Composable
internal fun GlassSettings(saved: GlassStyle, onSave: (GlassStyle) -> Unit) {
    var style by remember(saved) { mutableStateOf(saved) }
    val apply = { next: GlassStyle ->
        style = next
        onSave(next)
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        GlassPreview(style)
        Group(stringResource(R.string.glass_presets)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    R.string.glass_preset_off to GlassStyle.Off,
                    R.string.glass_preset_subtle to GlassStyle.Subtle,
                    R.string.glass_preset_default to GlassStyle.Default,
                    R.string.glass_preset_strong to GlassStyle.Strong,
                ).forEach { (label, preset) ->
                    FilterChip(selected = style == preset, onClick = { apply(preset) }, label = { Text(stringResource(label)) })
                }
            }
        }
        Group(stringResource(R.string.glass_group_glass)) {
            FactorSlider(R.string.glass_blur, R.string.glass_blur_description, style.blur, GlassStyle.MAX_FACTOR, offAtZero = true,
                onChange = { style = style.copy(blur = it) }, onDone = { onSave(style) })
            HorizontalDivider()
            FactorSlider(R.string.glass_tint, R.string.glass_tint_description, style.tint, GlassStyle.MAX_FACTOR,
                onChange = { style = style.copy(tint = it) }, onDone = { onSave(style) })
            HorizontalDivider()
            FactorSlider(R.string.glass_refraction, R.string.glass_refraction_description, style.refraction, GlassStyle.MAX_FACTOR, offAtZero = true,
                onChange = { style = style.copy(refraction = it) }, onDone = { onSave(style) })
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.glass_highlight), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.glass_highlight_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = style.highlight, onCheckedChange = { apply(style.copy(highlight = it)) })
            }
        }
        Group(stringResource(R.string.glass_group_pages)) {
            FactorSlider(R.string.glass_page_fill, R.string.glass_page_fill_description, style.pageFill, GlassStyle.MAX_PAGE_FILL, offAtZero = true,
                onChange = { style = style.copy(pageFill = it) }, onDone = { onSave(style) })
            HorizontalDivider()
            FactorSlider(R.string.glass_status_bar, R.string.glass_status_bar_description, style.statusBarBlur, GlassStyle.MAX_FACTOR, offAtZero = true,
                onChange = { style = style.copy(statusBarBlur = it) }, onDone = { onSave(style) })
        }
        OutlinedButton(onClick = { apply(GlassStyle.Default) }, enabled = style != GlassStyle.Default) {
            Text(stringResource(R.string.glass_reset))
        }
    }
}

/** A factor from 0 to [max] in steps of 10 %, shown as a percentage of the launcher's own look. */
@Composable
private fun FactorSlider(
    title: Int,
    description: Int,
    value: Float,
    max: Float,
    offAtZero: Boolean = false,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                if (offAtZero && value < 0.05f) stringResource(R.string.glass_off) else "${(value * 100).roundToInt()} %",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(stringResource(description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = { onChange((it * 10).roundToInt() / 10f) },
            onValueChangeFinished = onDone,
            valueRange = 0f..max,
            steps = (max * 10).roundToInt() - 1,
        )
    }
}

/**
 * A stand-in for the home screen: a colourful wallpaper with a clock and icons, a glass panel over it
 * drawn exactly as the launcher draws its own, and an App Library box with the page fill.
 */
@Composable
private fun GlassPreview(style: GlassStyle) {
    val backdrop = rememberLayerBackdrop()
    val colors = glassColors(style)
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp))) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFFFF9F0A)))),
        ) {
            Text("12:30", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 14.dp))
            Row(Modifier.align(Alignment.BottomStart).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(Color(0xFF30D158), Color(0xFFFF375F), Color(0xFFFFD60A), Color(0xFF64D2FF)).forEach { tone ->
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(tone))
                }
            }
            Box(Modifier.align(Alignment.TopEnd).offset(x = (-24).dp, y = 22.dp).size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)))
        }
        // An App Library box, with the fill of the organizer and the App Library.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 96.dp, end = 16.dp)
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = (0.11f * style.pageFill).coerceIn(0f, 1f))),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.62f)
                .height(84.dp)
                .glass(backdrop, RoundedRectangle(28.dp), colors),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.glass_preview), color = colors.content, style = MaterialTheme.typography.titleMedium)
        }
    }
}
