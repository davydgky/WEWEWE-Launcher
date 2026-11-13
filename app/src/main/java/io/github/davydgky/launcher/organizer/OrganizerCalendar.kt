package io.github.davydgky.launcher.organizer

import io.github.davydgky.launcher.ui.glass.pageFillAlpha
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.ui.wallpaperTextColor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

/** How much of the calendar shows at once; pinching moves between them, as in the iOS Calendar. */
enum class CalendarScale(val label: Int) {
    Day(R.string.organizer_scale_day),
    Week(R.string.organizer_scale_week),
    Month(R.string.organizer_scale_month),
    Year(R.string.organizer_scale_year);

    /** Fingers apart: closer, down to the hours of one day. */
    fun zoomedIn(): CalendarScale = entries[(ordinal - 1).coerceAtLeast(0)]

    /** Fingers together: further out, up to the whole year. */
    fun zoomedOut(): CalendarScale = entries[(ordinal + 1).coerceAtMost(entries.size - 1)]
}

/** The days the calendar reads events for at [scale] around [date]: the whole year, or a month either side. */
internal fun calendarWindow(scale: CalendarScale, date: LocalDate): Pair<LocalDate, LocalDate> = when (scale) {
    CalendarScale.Year -> LocalDate.of(date.year, 1, 1) to LocalDate.of(date.year, 12, 31)
    else -> YearMonth.from(date).let { it.minusMonths(1).atDay(1) to it.plusMonths(1).atEndOfMonth() }
}

/** Where the arrows lead at [scale]: a day, a week, a month or a year on; in a new month or year, its start (or today). */
internal fun stepDate(scale: CalendarScale, date: LocalDate, forward: Boolean, today: LocalDate = LocalDate.now()): LocalDate {
    val sign = if (forward) 1L else -1L
    return when (scale) {
        CalendarScale.Day -> date.plusDays(sign)
        CalendarScale.Week -> date.plusWeeks(sign)
        CalendarScale.Month -> YearMonth.from(date).plusMonths(sign).let { if (it == YearMonth.from(today)) today else it.atDay(1) }
        CalendarScale.Year -> (date.year + sign.toInt()).let { if (it == today.year) today else LocalDate.of(it, 1, 1) }
    }
}

/**
 * Reports a pinch on the calendar, one [onZoomIn] or [onZoomOut] per step, and keeps those two-finger
 * moves from the page's scroll and pager. A single finger is left alone: it scrolls and taps as before.
 */
private fun Modifier.pinchSteps(onZoomIn: () -> Unit, onZoomOut: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } < 2) continue
            zoom *= event.calculateZoom()
            event.changes.forEach { if (it.positionChanged()) it.consume() }
            when {
                zoom > PINCH_STEP -> {
                    onZoomIn()
                    zoom = 1f
                }
                zoom < 1f / PINCH_STEP -> {
                    onZoomOut()
                    zoom = 1f
                }
            }
        }
    }
}

private const val PINCH_STEP = 1.35f

/**
 * The calendar card: a day's hours, a week, a month or a year, switched with the row of tabs or by
 * pinching. A day with an event gets a dot in the event's colour row, one with a reminder a blue dot.
 */
@Composable
internal fun CalendarCard(
    scale: CalendarScale,
    selectedDate: LocalDate,
    eventsByDay: Map<LocalDate, List<CalendarEvent>>,
    todoDays: Set<LocalDate>,
    onScaleChange: (CalendarScale) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    val color = wallpaperTextColor()
    val locale = currentLocale()
    // The pinch handler outlives recompositions: it reads the scale on screen when the fingers move.
    val currentScale by rememberUpdatedState(scale)
    val currentOnScaleChange by rememberUpdatedState(onScaleChange)
    val firstDow = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val monthFormat = rememberDateFormat("MMMMy")
    val dayFormat = rememberDateFormat("EEEEdMMMM")
    val yearFormat = rememberDateFormat("y")
    val title = when (scale) {
        CalendarScale.Day -> dayFormat.format(selectedDate.toDate())
        CalendarScale.Week, CalendarScale.Month -> monthFormat.format(selectedDate.toDate())
        CalendarScale.Year -> yearFormat.format(selectedDate.toDate())
    }.capitalized(locale)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = pageFillAlpha(CARD_ALPHA)))
            .pinchSteps(
                onZoomIn = { currentOnScaleChange(currentScale.zoomedIn()) },
                onZoomOut = { currentOnScaleChange(currentScale.zoomedOut()) },
            )
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (scale == CalendarScale.Week) {
                    val week = selectedDate.get(WeekFields.of(locale).weekOfWeekBasedYear())
                    Text(stringResource(R.string.organizer_week_number, week), color = color.copy(alpha = 0.55f), fontSize = 12.sp)
                }
            }
            IconButton(onClick = { onSelectDate(stepDate(scale, selectedDate, forward = false)) }) {
                Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.organizer_previous), tint = color)
            }
            IconButton(onClick = { onSelectDate(stepDate(scale, selectedDate, forward = true)) }) {
                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.organizer_next), tint = color)
            }
        }
        ScaleTabs(scale, color, onScaleChange)
        Spacer(Modifier.height(10.dp))
        AnimatedContent(
            targetState = scale,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.94f)) togetherWith fadeOut() },
            label = "calendarScale",
        ) { shown ->
            when (shown) {
                CalendarScale.Day -> DayTimeline(selectedDate, eventsByDay[selectedDate].orEmpty(), color)
                CalendarScale.Week -> Column {
                    WeekdayHeader(firstDow, locale, color)
                    val start = selectedDate.with(TemporalAdjusters.previousOrSame(firstDow))
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { i ->
                            val date = start.plusDays(i.toLong())
                            DayCell(date, selectedDate, eventsByDay, todoDays, color, Modifier.weight(1f), showMonth = date.dayOfMonth == 1, onSelectDate)
                        }
                    }
                }
                CalendarScale.Month -> MonthGrid(YearMonth.from(selectedDate), selectedDate, eventsByDay, todoDays, firstDow, locale, color, onSelectDate)
                CalendarScale.Year -> YearGrid(selectedDate.year, eventsByDay, todoDays, firstDow, color) { month ->
                    onSelectDate(if (month == YearMonth.now()) LocalDate.now() else month.atDay(1))
                    onScaleChange(CalendarScale.Month)
                }
            }
        }
    }
}

internal const val CARD_ALPHA = 0.05f

@Composable
private fun ScaleTabs(scale: CalendarScale, color: Color, onScaleChange: (CalendarScale) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(color.copy(alpha = pageFillAlpha(0.06f))).padding(2.dp),
    ) {
        CalendarScale.entries.forEach { entry ->
            val selected = entry == scale
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) color.copy(alpha = pageFillAlpha(0.16f)) else Color.Transparent)
                    .clickable { onScaleChange(entry) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(entry.label),
                    color = if (selected) color else color.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun WeekdayHeader(firstDow: DayOfWeek, locale: Locale, color: Color) {
    Row(Modifier.fillMaxWidth()) {
        repeat(7) { i ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    firstDow.plus(i.toLong()).getDisplayName(TextStyle.SHORT, locale).take(2),
                    color = color.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/** One day in the week strip or the month grid: its number, a ring for today, a dot for events and reminders. */
@Composable
private fun DayCell(
    date: LocalDate,
    selectedDate: LocalDate,
    eventsByDay: Map<LocalDate, List<CalendarEvent>>,
    todoDays: Set<LocalDate>,
    color: Color,
    modifier: Modifier,
    showMonth: Boolean,
    onSelectDate: (LocalDate) -> Unit,
) {
    val isSelected = date == selectedDate
    val isToday = date == LocalDate.now()
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (isSelected) color.copy(alpha = 0.9f) else Color.Transparent)
                .clickable { onSelectDate(date) },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val textColor = when {
                    isSelected -> if (color == Color.White) Color.Black else Color.White
                    isToday -> TODAY_COLOR
                    else -> color.copy(alpha = 0.85f)
                }
                if (showMonth) {
                    Text(date.month.getDisplayName(TextStyle.SHORT, currentLocale()), color = textColor, fontSize = 8.sp, lineHeight = 8.sp)
                }
                Text(
                    date.dayOfMonth.toString(),
                    color = textColor,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                )
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            eventsByDay[date]?.firstOrNull()?.let { event -> Box(Modifier.size(4.dp).clip(CircleShape).background(Color(event.color))) }
            if (date in todoDays) Box(Modifier.size(4.dp).clip(CircleShape).background(TODO_DOT))
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    eventsByDay: Map<LocalDate, List<CalendarEvent>>,
    todoDays: Set<LocalDate>,
    firstDow: DayOfWeek,
    locale: Locale,
    color: Color,
    onSelectDate: (LocalDate) -> Unit,
) {
    Column {
        WeekdayHeader(firstDow, locale, color)
        val leading = (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
        val rows = (leading + month.lengthOfMonth() + 6) / 7
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNum = row * 7 + col - leading + 1
                    if (dayNum in 1..month.lengthOfMonth()) {
                        DayCell(month.atDay(dayNum), selectedDate, eventsByDay, todoDays, color, Modifier.weight(1f), showMonth = false, onSelectDate)
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The twelve months in small; days with something on them stand out. A tap opens that month. */
@Composable
private fun YearGrid(
    year: Int,
    eventsByDay: Map<LocalDate, List<CalendarEvent>>,
    todoDays: Set<LocalDate>,
    firstDow: DayOfWeek,
    color: Color,
    onPickMonth: (YearMonth) -> Unit,
) {
    val locale = currentLocale()
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (quarter in 0 until 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (i in 0 until 3) {
                    val month = YearMonth.of(year, quarter * 3 + i + 1)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onPickMonth(month) }.padding(2.dp)) {
                        Text(
                            month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).capitalized(locale),
                            color = if (month == YearMonth.from(today)) TODAY_COLOR else color,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        val leading = (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
                        val rows = (leading + month.lengthOfMonth() + 6) / 7
                        for (row in 0 until rows) {
                            Row(Modifier.fillMaxWidth()) {
                                for (col in 0 until 7) {
                                    val dayNum = row * 7 + col - leading + 1
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        if (dayNum in 1..month.lengthOfMonth()) {
                                            val date = month.atDay(dayNum)
                                            val busy = eventsByDay.containsKey(date) || date in todoDays
                                            Text(
                                                dayNum.toString(),
                                                color = when {
                                                    date == today -> TODAY_COLOR
                                                    busy -> color
                                                    else -> color.copy(alpha = 0.5f)
                                                },
                                                fontSize = 8.sp,
                                                lineHeight = 10.sp,
                                                fontWeight = if (busy || date == today) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val HOUR_HEIGHT = 44.dp
private val HOUR_LABEL_WIDTH = 52.dp

/** One day by the hour, its own scrolling window starting at the morning (or the current hour, today). */
@Composable
private fun DayTimeline(date: LocalDate, events: List<CalendarEvent>, color: Color) {
    val density = LocalDensity.current
    val timeFormat = rememberTimeFormat()
    val (allDay, timed) = events.partition { it.allDay }
    Column {
        allDay.forEach { event ->
            Text(
                event.title.ifBlank { stringResource(R.string.event_untitled) },
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(event.color).copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        val scroll = rememberScrollState()
        LaunchedEffect(date) {
            val firstHour = if (date == LocalDate.now()) (LocalTime.now().hour - 1).coerceAtLeast(0) else 7
            scroll.scrollTo(with(density) { (HOUR_HEIGHT * firstHour).roundToPx() })
        }
        Box(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 7).verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24)) {
                for (hour in 0 until 24) {
                    Row(Modifier.offset(y = HOUR_HEIGHT * hour), verticalAlignment = Alignment.Top) {
                        Text(
                            timeFormat.format(Date(date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli())),
                            color = color.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                            modifier = Modifier.width(HOUR_LABEL_WIDTH).offset(y = (-5).dp),
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(color.copy(alpha = 0.1f)))
                    }
                }
                val dayStart = date.startOfDayMillis()
                val dayEnd = date.plusDays(1).startOfDayMillis()
                val placed = remember(timed, dayStart) { placeInLanes(timed, dayStart, dayEnd) }
                BoxWithConstraints(Modifier.padding(start = HOUR_LABEL_WIDTH).fillMaxSize()) {
                    val laneWidth = maxWidth / placed.lanes.coerceAtLeast(1)
                    placed.items.forEach { item ->
                        val top = HOUR_HEIGHT * (item.startMinute / 60f)
                        val height = HOUR_HEIGHT * ((item.endMinute - item.startMinute).coerceAtLeast(20) / 60f)
                        Column(
                            Modifier
                                .offset(x = laneWidth * item.lane, y = top)
                                .width(laneWidth)
                                .height(height)
                                .padding(end = 2.dp, bottom = 1.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(item.event.color).copy(alpha = 0.85f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                item.event.title.ifBlank { stringResource(R.string.event_untitled) },
                                color = Color.White,
                                fontSize = 12.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (date == LocalDate.now()) {
                        val now = LocalTime.now()
                        Box(Modifier.offset(y = HOUR_HEIGHT * ((now.hour * 60 + now.minute) / 60f)).fillMaxWidth().height(2.dp).background(TODAY_COLOR))
                    }
                }
            }
        }
    }
}

internal class PlacedEvent(val event: CalendarEvent, val startMinute: Int, val endMinute: Int, val lane: Int)
internal class Placement(val items: List<PlacedEvent>, val lanes: Int)

/**
 * Lays timed events out over one day (minutes from [dayStart]), side by side where they overlap: each
 * goes into the first lane that is free at its start.
 */
internal fun placeInLanes(events: List<CalendarEvent>, dayStart: Long, dayEnd: Long): Placement {
    val dayMinutes = ((dayEnd - dayStart) / 60_000L).toInt()
    val laneEnds = ArrayList<Int>()
    val items = events
        .map { e ->
            val start = ((e.begin.coerceIn(dayStart, dayEnd) - dayStart) / 60_000L).toInt()
            val end = ((e.end.coerceIn(dayStart, dayEnd) - dayStart) / 60_000L).toInt().coerceAtLeast(start)
            Triple(e, start, end)
        }
        .sortedWith(compareBy({ it.second }, { -it.third }))
        .map { (event, start, end) ->
            var lane = laneEnds.indexOfFirst { it <= start }
            if (lane < 0) {
                laneEnds += end
                lane = laneEnds.size - 1
            } else {
                laneEnds[lane] = end
            }
            PlacedEvent(event, start.coerceAtMost(dayMinutes), end.coerceAtMost(dayMinutes), lane)
        }
    return Placement(items, laneEnds.size)
}

private val TODAY_COLOR = Color(0xFFFF453A)
private val TODO_DOT = Color(0xFF0A84FF)
