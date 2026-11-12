package io.github.davydgky.launcher.organizer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.ui.wallpaperTextColor
import kotlin.math.roundToInt

private val CARD_HEIGHT = 60.dp
private val CARD_GAP = 10.dp

/**
 * The organizer page being arranged: each part as a card that is dragged up or down into another
 * place, and hidden or shown again with the button on its right. A hidden part stays here, dimmed.
 */
@Composable
internal fun OrganizerEditor(
    sections: List<String>,
    hidden: Set<String>,
    onMove: (from: Int, to: Int) -> Unit,
    onSetHidden: (section: String, hidden: Boolean) -> Unit,
) {
    val color = wallpaperTextColor()
    val haptics = LocalHapticFeedback.current
    val slot = with(LocalDensity.current) { (CARD_HEIGHT + CARD_GAP).toPx() }
    // The drag outlives recompositions: it reads the order on screen as the finger moves.
    val order by rememberUpdatedState(sections)
    val move by rememberUpdatedState(onMove)
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Text(stringResource(R.string.organizer_edit_hint), color = color.copy(alpha = 0.6f), fontSize = 13.sp)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        sections.forEach { section ->
            // Keyed, so a card keeps its drag while it changes places.
            key(section) {
                val isDragged = dragged == section
                val isHidden = section in hidden
                val lift by animateFloatAsState(if (isDragged) 1.03f else 1f, label = "lift")
                Row(
                    Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) dragOffset else 0f
                            scaleX = lift
                            scaleY = lift
                        }
                        .fillMaxWidth()
                        .height(CARD_HEIGHT)
                        .clip(RoundedCornerShape(16.dp))
                        .background(color.copy(alpha = if (isDragged) 0.16f else 0.08f))
                        .pointerInput(section) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragged = section
                                    dragOffset = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                    val current = order.indexOf(section)
                                    if (current < 0) return@detectVerticalDragGestures
                                    val target = (current + (dragOffset / slot).roundToInt()).coerceIn(0, order.lastIndex)
                                    if (target != current) {
                                        move(current, target)
                                        // The card is in its new place now: the finger's offset is measured from there.
                                        dragOffset -= (target - current) * slot
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDragEnd = { dragged = null },
                                onDragCancel = { dragged = null },
                            )
                        }
                        .padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DragHandle(color.copy(alpha = 0.5f))
                    Spacer(Modifier.width(14.dp))
                    Icon(painterResource(iconOf(section)), null, tint = color.copy(alpha = if (isHidden) 0.35f else 0.9f), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(labelOf(section)),
                        color = color.copy(alpha = if (isHidden) 0.4f else 1f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onSetHidden(section, !isHidden) }) {
                        Icon(
                            painterResource(if (isHidden) R.drawable.ic_add else R.drawable.ic_hide),
                            stringResource(if (isHidden) R.string.organizer_show else R.string.organizer_hide),
                            tint = color.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

/** Three short lines, the usual sign for "drag me". */
@Composable
private fun DragHandle(color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { Box(Modifier.width(16.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(color)) }
    }
}

private fun labelOf(section: String): Int = when (section) {
    OrganizerSections.CALENDAR -> R.string.organizer_section_calendar
    OrganizerSections.AGENDA -> R.string.organizer_section_agenda
    OrganizerSections.SUGGESTIONS -> R.string.organizer_suggestions
    else -> R.string.organizer_tasks
}

private fun iconOf(section: String): Int = when (section) {
    OrganizerSections.CALENDAR -> R.drawable.ic_event
    OrganizerSections.AGENDA -> R.drawable.ic_history
    OrganizerSections.SUGGESTIONS -> R.drawable.ic_sparkle
    else -> R.drawable.ic_check
}
