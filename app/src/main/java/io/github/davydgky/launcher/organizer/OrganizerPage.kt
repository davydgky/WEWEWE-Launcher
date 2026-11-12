@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.davydgky.launcher.organizer

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.ui.wallpaperTextColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val zone = ZoneId.systemDefault()
private fun LocalDate.startOfDayMillis(): Long = atStartOfDay(zone).toInstant().toEpochMilli()
private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

// Material3's DatePicker always works in UTC days, regardless of the device's zone.
private fun LocalDate.toUtcMidnightMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private val LIST_PALETTE = listOf(
    Color(0xFF0A84FF), Color(0xFFFF9F0A), Color(0xFF30D158), Color(0xFFFF375F), Color(0xFFBF5AF2), Color(0xFF64D2FF),
)

/**
 * The organizing page to the left of the home screen: a monthly calendar (overlaid with the device's
 * synced calendars, Google Calendar included) and, below it, an Apple Reminders-style todo list with one
 * tab per list. A reminder due on a day shows there in the calendar too.
 */
@Composable
fun OrganizerPage(repository: OrganizerRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val organizer by repository.organizer.collectAsStateWithLifecycle()
    var hasReadAccess by rememberSaveable { mutableStateOf(CalendarEvents.hasReadAccess(context)) }
    var hasWriteAccess by rememberSaveable { mutableStateOf(CalendarEvents.hasWriteAccess(context)) }
    val readPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasReadAccess = it }
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasWriteAccess = it }

    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now()) }

    val events by produceState(emptyList<CalendarEvent>(), month, hasReadAccess) {
        value = withContext(Dispatchers.IO) {
            val from = month.minusMonths(1).atDay(1).startOfDayMillis()
            val to = month.plusMonths(1).atEndOfMonth().plusDays(1).startOfDayMillis()
            CalendarEvents.between(context, from, to)
        }
    }
    val eventDays = remember(events) { events.mapTo(HashSet()) { it.begin.toLocalDate() } }

    val state = organizer ?: return
    var selectedTab by rememberSaveable { mutableStateOf(state.lists.firstOrNull()?.id ?: 1L) }
    LaunchedEffect(state.lists) { if (state.lists.none { it.id == selectedTab }) selectedTab = state.lists.firstOrNull()?.id ?: 1L }
    val todoDays = remember(state.todos) {
        state.todos.mapNotNullTo(HashSet()) { it.dueAtMillis?.toLocalDate() }
    }

    var showAddEvent by remember { mutableStateOf(false) }
    var editingTodo by remember { mutableStateOf<Todo?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.organizer_title),
            color = wallpaperTextColor(),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

        if (!hasReadAccess) {
            PermissionBanner(stringResource(R.string.organizer_connect_calendar)) { readPermission.launch(Manifest.permission.READ_CALENDAR) }
            Spacer(Modifier.height(12.dp))
        }

        MonthCalendar(
            month = month,
            selectedDate = selectedDate,
            eventDays = eventDays,
            todoDays = todoDays,
            onMonthChange = { month = it },
            onSelectDate = { selectedDate = it },
        )

        Spacer(Modifier.height(18.dp))
        AgendaSection(
            date = selectedDate,
            events = events.filter { it.begin.toLocalDate() == selectedDate },
            todos = state.todos.filter { it.dueAtMillis?.toLocalDate() == selectedDate },
            onAddEvent = {
                if (!hasWriteAccess) writePermission.launch(Manifest.permission.WRITE_CALENDAR)
                showAddEvent = true
            },
            onToggleTodo = repository::toggleDone,
            onEditTodo = { editingTodo = it },
        )

        Spacer(Modifier.height(26.dp))
        TodoSection(
            state = state,
            repository = repository,
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            onEditTodo = { editingTodo = it },
        )
        Spacer(Modifier.height(32.dp))
    }

    if (showAddEvent) {
        AddEventSheet(
            initialDate = selectedDate,
            hasWriteAccess = hasWriteAccess,
            onRequestWriteAccess = { writePermission.launch(Manifest.permission.WRITE_CALENDAR) },
            onDismiss = { showAddEvent = false },
            onSave = { title, date, time ->
                val calendar = CalendarEvents.writableCalendars(context).firstOrNull()
                if (calendar != null) {
                    val begin = date.atStartOfDay(zone).let { if (time != null) it.with(time) else it }.toInstant().toEpochMilli()
                    val end = begin + if (time != null) 60 * 60 * 1000L else 24 * 60 * 60 * 1000L
                    CalendarEvents.insertEvent(context, calendar.id, title, begin, end, allDay = time == null)
                }
                showAddEvent = false
            },
        )
    }

    val editing = editingTodo
    if (editing != null) {
        TodoEditSheet(
            todo = editing,
            lists = state.lists,
            onDismiss = { editingTodo = null },
            onSave = { title, notes, listId, due, hasTime ->
                repository.updateTodo(editing.id) { it.copy(title = title, notes = notes, listId = listId, dueAtMillis = due, hasTime = hasTime) }
                editingTodo = null
            },
            onDelete = {
                repository.removeTodo(editing.id)
                editingTodo = null
            },
        )
    }
}

@Composable
private fun PermissionBanner(text: String, onClick: () -> Unit) {
    val color = wallpaperTextColor()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = color)
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    selectedDate: LocalDate,
    eventDays: Set<LocalDate>,
    todoDays: Set<LocalDate>,
    onMonthChange: (YearMonth) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    val color = wallpaperTextColor()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.08f)).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() } + " ${month.year}",
                color = color,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) { Icon(painterResource(R.drawable.ic_chevron_left), null, tint = color) }
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) { Icon(painterResource(R.drawable.ic_chevron_right), null, tint = color) }
        }
        Row(Modifier.fillMaxWidth()) {
            val firstDow = java.time.DayOfWeek.MONDAY
            repeat(7) { i ->
                val dow = firstDow.plus(i.toLong())
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                        color = color.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val firstOfMonth = month.atDay(1)
        val leading = (firstOfMonth.dayOfWeek.value + 6) % 7 // Monday = 0
        val totalCells = leading + month.lengthOfMonth()
        val rows = (totalCells + 6) / 7
        val today = LocalDate.now()
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIndex = row * 7 + col
                    val dayNum = cellIndex - leading + 1
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (dayNum in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayNum)
                            val isSelected = date == selectedDate
                            val isToday = date == today
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) color.copy(alpha = 0.9f) else Color.Transparent)
                                    .clickable(onClickLabel = date.toString()) { onSelectDate(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        dayNum.toString(),
                                        color = when {
                                            isSelected -> if (wallpaperTextColor() == Color.White) Color.Black else Color.White
                                            isToday -> color
                                            else -> color.copy(alpha = 0.85f)
                                        },
                                        fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                            Row(
                                Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (date in eventDays) Box(Modifier.size(3.dp).clip(CircleShape).background(color.copy(alpha = 0.8f)))
                                if (date in todoDays) Box(Modifier.size(3.dp).clip(CircleShape).background(LIST_PALETTE[0]))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaSection(
    date: LocalDate,
    events: List<CalendarEvent>,
    todos: List<Todo>,
    onAddEvent: () -> Unit,
    onToggleTodo: (Long) -> Unit,
    onEditTodo: (Todo) -> Unit,
) {
    val color = wallpaperTextColor()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.getDefault())).replaceFirstChar { it.uppercase() },
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAddEvent) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.organizer_add_event), tint = color) }
    }
    if (events.isEmpty() && todos.isEmpty()) {
        Text(stringResource(R.string.organizer_nothing_today), color = color.copy(alpha = 0.55f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
    Column(Modifier.fillMaxWidth()) {
        events.forEach { event ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(Color(event.color)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.title.ifBlank { stringResource(R.string.event_untitled) }, color = color, fontSize = 14.sp)
                    if (!event.allDay) {
                        Text(
                            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(event.begin)),
                            color = color.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        todos.forEach { todo -> TodoRow(todo, color, onToggle = { onToggleTodo(todo.id) }, onClick = { onEditTodo(todo) }) }
    }
}

@Composable
private fun TodoSection(
    state: OrganizerState,
    repository: OrganizerRepository,
    selectedTab: Long,
    onSelectTab: (Long) -> Unit,
    onEditTodo: (Todo) -> Unit,
) {
    val color = wallpaperTextColor()
    var newListName by remember { mutableStateOf<String?>(null) }

    Text(stringResource(R.string.organizer_tasks), color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.lists, key = { it.id }) { list ->
            val selected = list.id == selectedTab
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) LIST_PALETTE[list.colorIndex % LIST_PALETTE.size] else color.copy(alpha = 0.12f))
                    .clickable { onSelectTab(list.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(list.name, color = if (selected) Color.White else color, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        item {
            IconButton(onClick = { newListName = "" }) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.organizer_new_list), tint = color) }
        }
    }
    Spacer(Modifier.height(12.dp))
    val todos = state.todosOf(selectedTab)
    Column(Modifier.fillMaxWidth()) {
        todos.forEach { todo ->
            TodoRow(todo, color, onToggle = { repository.toggleDone(todo.id) }, onClick = { onEditTodo(todo) }, showDate = true)
        }
    }
    AddTodoRow(color = color, onAdd = { title -> repository.addTodo(selectedTab, title, dueAtMillis = null, hasTime = false) })

    val pendingName = newListName
    if (pendingName != null) {
        NewListDialog(
            name = pendingName,
            onNameChange = { newListName = it },
            onDismiss = { newListName = null },
            onConfirm = {
                if (pendingName.isNotBlank()) onSelectTab(repository.addList(pendingName.trim()))
                newListName = null
            },
        )
    }
}

@Composable
private fun TodoRow(todo: Todo, color: Color, onToggle: () -> Unit, onClick: () -> Unit, showDate: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (todo.done) LIST_PALETTE[0] else Color.Transparent)
                .clickable(onClickLabel = null, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (todo.done) {
                Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(14.dp))
            } else {
                androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                    drawCircle(
                        color = color.copy(alpha = 0.5f),
                        radius = size.minDimension / 2 - 1.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4.dp.toPx()),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                todo.title,
                color = if (todo.done) color.copy(alpha = 0.45f) else color,
                fontSize = 15.sp,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
            )
            if (showDate && todo.dueAtMillis != null) {
                val d = todo.dueAtMillis.toLocalDate()
                val fmt = if (todo.hasTime) {
                    d.format(java.time.format.DateTimeFormatter.ofPattern("d. MMM, HH:mm", Locale.getDefault()))
                } else {
                    d.format(java.time.format.DateTimeFormatter.ofPattern("d. MMM", Locale.getDefault()))
                }
                Text(fmt, color = color.copy(alpha = 0.5f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AddTodoRow(color: Color, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val placeholder = stringResource(R.string.organizer_new_reminder)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_add), null, tint = LIST_PALETTE[0], modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = color, fontSize = 15.sp),
            cursorBrush = SolidColor(color),
            modifier = Modifier.weight(1f),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                if (text.isNotBlank()) {
                    onAdd(text.trim())
                    text = ""
                }
            }),
            decorationBox = { innerField ->
                Box {
                    if (text.isEmpty()) Text(placeholder, color = color.copy(alpha = 0.4f), fontSize = 15.sp)
                    innerField()
                }
            },
        )
    }
}

@Composable
private fun NewListDialog(name: String, onNameChange: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.organizer_new_list)) },
        text = {
            androidx.compose.material3.OutlinedTextField(value = name, onValueChange = onNameChange, singleLine = true)
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.organizer_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Quick date picks, as Apple's "Today / Tomorrow / This weekend / Custom date" in Reminders. */
@Composable
private fun DueDatePicker(
    date: LocalDate?,
    hasTime: Boolean,
    time: LocalTime,
    onDateChange: (LocalDate?) -> Unit,
    onHasTimeChange: (Boolean) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickDateChip(stringResource(R.string.organizer_today), date == today) { onDateChange(today) }
            QuickDateChip(stringResource(R.string.organizer_tomorrow), date == today.plusDays(1)) { onDateChange(today.plusDays(1)) }
            QuickDateChip(stringResource(R.string.organizer_next_week), date == today.plusWeeks(1)) { onDateChange(today.plusWeeks(1)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showDatePicker = true }) {
                Text(date?.format(java.time.format.DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.getDefault())) ?: stringResource(R.string.organizer_pick_date))
            }
            Spacer(Modifier.weight(1f))
            if (date != null) TextButton(onClick = { onDateChange(null) }) { Text(stringResource(R.string.organizer_no_date)) }
        }
        if (date != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.organizer_set_time), modifier = Modifier.weight(1f))
                Switch(checked = hasTime, onCheckedChange = onHasTimeChange)
            }
            if (hasTime) {
                TextButton(onClick = { showTimePicker = true }) {
                    Text(time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
                }
            }
        }
    }
    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: today).toUtcMidnightMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showDatePicker = false
                }) { Text(stringResource(R.string.organizer_create)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(state.hour, state.minute))
                    showTimePicker = false
                }) { Text(stringResource(R.string.organizer_create)) }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun QuickDateChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val color = androidx.compose.material3.MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) color else color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (selected) Color.White else color, fontSize = 13.sp)
    }
}

@Composable
private fun TodoEditSheet(
    todo: Todo,
    lists: List<TodoList>,
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String, listId: Long, due: Long?, hasTime: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember { mutableStateOf(todo.title) }
    var notes by remember { mutableStateOf(todo.notes) }
    var listId by remember { mutableStateOf(todo.listId) }
    var date by remember { mutableStateOf(todo.dueAtMillis?.toLocalDate()) }
    var hasTime by remember { mutableStateOf(todo.hasTime) }
    var time by remember { mutableStateOf(todo.dueAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(9, 0)) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.organizer_title_field)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) { Icon(painterResource(R.drawable.ic_trash), stringResource(R.string.delete)) }
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.organizer_notes_field)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lists, key = { it.id }) { list ->
                    QuickDateChip(list.name, list.id == listId) { listId = list.id }
                }
            }
            Spacer(Modifier.height(16.dp))
            DueDatePicker(
                date = date, hasTime = hasTime, time = time,
                onDateChange = { date = it }, onHasTimeChange = { hasTime = it }, onTimeChange = { time = it },
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val due = date?.let { d -> if (hasTime) d.atTime(time).atZone(zone).toInstant().toEpochMilli() else d.startOfDayMillis() }
                    onSave(title, notes, listId, due, hasTime && date != null)
                }) { Text(stringResource(R.string.organizer_save)) }
            }
        }
    }
}

@Composable
private fun AddEventSheet(
    initialDate: LocalDate,
    hasWriteAccess: Boolean,
    onRequestWriteAccess: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (title: String, date: LocalDate, time: LocalTime?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(initialDate) }
    var hasTime by remember { mutableStateOf(true) }
    var time by remember { mutableStateOf(LocalTime.of(9, 0)) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.organizer_add_event), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (!hasWriteAccess) {
                PermissionBanner(stringResource(R.string.organizer_allow_write), onRequestWriteAccess)
                Spacer(Modifier.height(12.dp))
            }
            androidx.compose.material3.OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.organizer_title_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            DueDatePicker(
                date = date, hasTime = hasTime, time = time,
                onDateChange = { date = it ?: date }, onHasTimeChange = { hasTime = it }, onTimeChange = { time = it },
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = title.isNotBlank() && hasWriteAccess, onClick = { onSave(title.trim(), date, if (hasTime) time else null) }) {
                    Text(stringResource(R.string.organizer_save))
                }
            }
        }
    }
}
