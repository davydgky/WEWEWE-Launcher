@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.davydgky.launcher.organizer

import android.Manifest
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.ui.glass.BehindStatusBar
import io.github.davydgky.launcher.ui.wallpaperTextColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Read on every use: the user can change the time zone while the launcher keeps running.
internal val zone: ZoneId get() = ZoneId.systemDefault()
internal fun LocalDate.startOfDayMillis(): Long = atStartOfDay(zone).toInstant().toEpochMilli()
private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

// Material3's DatePicker always works in UTC days, regardless of the device's zone.
private fun LocalDate.toUtcMidnightMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

// Reading java.util.Locale.getDefault() directly would not recompose if the user changes the
// system language while the app is open; this reads it from the (observable) configuration instead.
@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * A date format in the user's language from an ICU skeleton ("dMMM" → "12. Nov." in German, "Nov 12" in
 * English), rather than a fixed pattern that only reads right in one language.
 */
@Composable
internal fun rememberDateFormat(skeleton: String): android.icu.text.DateFormat {
    val locale = currentLocale()
    return remember(skeleton, locale) { android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale) }
}

/** Times as the user set them up: 24-hour or with AM/PM. */
@Composable
internal fun rememberTimeFormat(): java.text.DateFormat {
    val context = LocalContext.current
    val locale = currentLocale()
    return remember(locale) { android.text.format.DateFormat.getTimeFormat(context) }
}

internal fun LocalDate.toDate(): Date = Date(startOfDayMillis())
internal fun String.capitalized(locale: Locale): String = replaceFirstChar { it.titlecase(locale) }

private val LIST_PALETTE = listOf(
    Color(0xFF0A84FF), Color(0xFFFF9F0A), Color(0xFF30D158), Color(0xFFFF375F), Color(0xFFBF5AF2), Color(0xFF64D2FF),
)

/**
 * The organizing page to the left of the home screen: a monthly calendar (overlaid with the device's
 * synced calendars, Google Calendar included) and, below it, an Apple Reminders-style todo list with one
 * tab per list. A reminder due on a day shows there in the calendar too.
 */
@Composable
fun OrganizerPage(
    repository: OrganizerRepository,
    modifier: Modifier = Modifier,
    bottomSpace: Dp = 0.dp,
    /** The page is on screen; arranging it ends when it is swiped away. */
    active: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val organizer by repository.organizer.collectAsStateWithLifecycle()
    var hasReadAccess by rememberSaveable { mutableStateOf(CalendarEvents.hasReadAccess(context)) }
    var hasWriteAccess by rememberSaveable { mutableStateOf(CalendarEvents.hasWriteAccess(context)) }
    val readPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasReadAccess = it }
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasWriteAccess = it }

    // A week by default: the reminders below stay in view. Pinching or the tabs show more or less.
    var scale by rememberSaveable { mutableStateOf(CalendarScale.Week) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now()) }

    // Bumped whenever the Calendar Provider changes (a sync from Google, an event added here), to read again.
    var calendarVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(hasReadAccess) {
        if (!hasReadAccess) return@DisposableEffect onDispose {}
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                calendarVersion++
            }
        }
        context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    val (windowStart, windowEnd) = calendarWindow(scale, selectedDate)
    val events by produceState(emptyList<CalendarEvent>(), windowStart, windowEnd, hasReadAccess, calendarVersion) {
        // A sync reports many changes in a row; a new one restarts this, so it reads once they settle.
        if (calendarVersion > 0) delay(300)
        value = withContext(Dispatchers.IO) {
            CalendarEvents.between(context, windowStart.startOfDayMillis(), windowEnd.plusDays(1).startOfDayMillis())
        }
    }
    // An event shows on every day it covers, and an all-day one on its own (UTC) day in every time zone.
    val eventsByDay = remember(events, windowStart, windowEnd) {
        val byDay = HashMap<LocalDate, MutableList<CalendarEvent>>()
        events.forEach { event -> event.days(windowStart, windowEnd).forEach { byDay.getOrPut(it) { ArrayList() } += event } }
        byDay
    }

    val state = organizer ?: return
    var selectedTab by rememberSaveable { mutableStateOf(state.lists.firstOrNull()?.id ?: 1L) }
    LaunchedEffect(state.lists) { if (state.lists.none { it.id == selectedTab }) selectedTab = state.lists.firstOrNull()?.id ?: 1L }
    val todoDays = remember(state.todos) {
        state.todos.mapNotNullTo(HashSet()) { it.dueAtMillis?.toLocalDate() }
    }

    // Arranging the page: its parts as cards to drag into another order or hide.
    var arranging by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) arranging = false }
    androidx.activity.compose.BackHandler(enabled = arranging && active) { arranging = false }
    var showAddEvent by remember { mutableStateOf(false) }
    var editingTodo by remember { mutableStateOf<Todo?>(null) }

    // With the keyboard up the page ends above it, so the field being typed in scrolls into view. At the
    // top it runs up behind the status bar, blurred there so the clock stays readable.
    BehindStatusBar(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.organizer_title),
                    color = wallpaperTextColor(),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (arranging) {
                    TextButton(onClick = { arranging = false }) {
                        Text(stringResource(R.string.organizer_done), color = wallpaperTextColor(), fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    IconButton(onClick = { arranging = true }) {
                        Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.organizer_edit), tint = wallpaperTextColor())
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (arranging) {
                OrganizerEditor(
                    sections = state.sections,
                    hidden = state.hiddenSections,
                    onMove = repository::moveSection,
                    onSetHidden = repository::setSectionHidden,
                )
            } else {
                if (!hasReadAccess && OrganizerSections.CALENDAR !in state.hiddenSections) {
                    PermissionBanner(stringResource(R.string.organizer_connect_calendar)) { readPermission.launch(Manifest.permission.READ_CALENDAR) }
                    Spacer(Modifier.height(12.dp))
                }
                var first = true
                state.sections.filter { it !in state.hiddenSections }.forEach { section ->
                    // Suggestions only take room while there are some.
                    if (section == OrganizerSections.SUGGESTIONS && state.suggestions.isEmpty()) return@forEach
                    if (!first) Spacer(Modifier.height(if (section == OrganizerSections.AGENDA) 18.dp else 26.dp))
                    first = false
                    when (section) {
                        OrganizerSections.CALENDAR -> CalendarCard(
                            scale = scale,
                            selectedDate = selectedDate,
                            eventsByDay = eventsByDay,
                            todoDays = todoDays,
                            onScaleChange = { scale = it },
                            onSelectDate = { selectedDate = it },
                        )
                        OrganizerSections.AGENDA -> AgendaSection(
                            date = selectedDate,
                            events = eventsByDay[selectedDate].orEmpty(),
                            todos = state.todos.filter { it.dueAtMillis?.toLocalDate() == selectedDate },
                            onAddEvent = {
                                if (!hasWriteAccess) writePermission.launch(Manifest.permission.WRITE_CALENDAR)
                                showAddEvent = true
                            },
                            onToggleTodo = repository::toggleDone,
                            onEditTodo = { editingTodo = it },
                        )
                        OrganizerSections.SUGGESTIONS -> SuggestionsSection(
                            suggestions = state.suggestions,
                            onAccept = { repository.acceptSuggestion(it.id, selectedTab) },
                            onDismiss = { repository.dismissSuggestion(it.id) },
                            onDismissAll = repository::dismissAllSuggestions,
                        )
                        OrganizerSections.TASKS -> TodoSection(
                            state = state,
                            repository = repository,
                            selectedTab = selectedTab,
                            onSelectTab = { selectedTab = it },
                            onEditTodo = { editingTodo = it },
                        )
                        else -> Unit
                    }
                }
            }
            // The page reaches the bottom edge; its end scrolls up above the page dots and navigation bar.
            Spacer(Modifier.height(24.dp + bottomSpace))
        }
    }

    if (showAddEvent) {
        AddEventSheet(
            initialDate = selectedDate,
            hasWriteAccess = hasWriteAccess,
            onRequestWriteAccess = { writePermission.launch(Manifest.permission.WRITE_CALENDAR) },
            onDismiss = { showAddEvent = false },
            onSave = { title, date, time ->
                showAddEvent = false
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        val calendar = CalendarEvents.writableCalendars(context).firstOrNull() ?: return@withContext false
                        val id = if (time != null) {
                            val begin = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                            CalendarEvents.insertEvent(context, calendar.id, title, begin, begin + 60 * 60 * 1000L, allDay = false)
                        } else {
                            // All-day events run from midnight UTC to the next midnight UTC in the provider.
                            CalendarEvents.insertEvent(context, calendar.id, title, date.toUtcMidnightMillis(), date.plusDays(1).toUtcMidnightMillis(), allDay = true)
                        }
                        id != null
                    }
                    if (saved) calendarVersion++ else Toast.makeText(context, R.string.organizer_event_not_saved, Toast.LENGTH_SHORT).show()
                }
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
            .background(color.copy(alpha = 0.09f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = color)
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
    val locale = currentLocale()
    val dayFormat = rememberDateFormat("EEEEdMMMM")
    val timeFormat = rememberTimeFormat()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            dayFormat.format(date.toDate()).capitalized(locale),
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
                            timeFormat.format(Date(event.begin)),
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

/**
 * Reminders Gemini Nano drafted from messages, newest first: each says where it came from, and goes
 * into the list shown below with the check mark, or away with the cross.
 */
@Composable
private fun SuggestionsSection(
    suggestions: List<TodoSuggestion>,
    onAccept: (TodoSuggestion) -> Unit,
    onDismiss: (TodoSuggestion) -> Unit,
    onDismissAll: () -> Unit,
) {
    val color = wallpaperTextColor()
    val dateFormat = rememberDateFormat("dMMM")
    val timeFormat = rememberTimeFormat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_sparkle), null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.organizer_suggestions), color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (suggestions.size > 1) {
            TextButton(onClick = onDismissAll) { Text(stringResource(R.string.organizer_dismiss_all), color = color.copy(alpha = 0.7f), fontSize = 13.sp) }
        }
    }
    Spacer(Modifier.height(4.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = CARD_ALPHA)).padding(vertical = 4.dp),
    ) {
        suggestions.asReversed().forEach { suggestion ->
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(suggestion.title, color = color, fontSize = 15.sp)
                    val due = suggestion.dueAtMillis?.let { millis ->
                        val day = dateFormat.format(Date(millis))
                        if (suggestion.hasTime) "$day, ${timeFormat.format(Date(millis))}" else day
                    }
                    val source = listOfNotNull(suggestion.app.ifBlank { null }, suggestion.sender.ifBlank { null }, due).joinToString(" · ")
                    if (source.isNotEmpty()) Text(source, color = color.copy(alpha = 0.55f), fontSize = 12.sp)
                    if (suggestion.excerpt.isNotBlank()) {
                        Text(
                            "„${suggestion.excerpt}“",
                            color = color.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { onAccept(suggestion) }) {
                    Icon(painterResource(R.drawable.ic_check), stringResource(R.string.organizer_accept), tint = LIST_PALETTE[0])
                }
                IconButton(onClick = { onDismiss(suggestion) }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.organizer_dismiss), tint = color.copy(alpha = 0.6f))
                }
            }
        }
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
    // A long press on a list's tab: rename it or delete it (its reminders move to another list).
    var menuList by remember { mutableStateOf<TodoList?>(null) }
    var renaming by remember { mutableStateOf<Pair<TodoList, String>?>(null) }

    Text(stringResource(R.string.organizer_tasks), color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.lists, key = { it.id }) { list ->
            val selected = list.id == selectedTab
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) LIST_PALETTE[list.colorIndex % LIST_PALETTE.size] else color.copy(alpha = 0.08f))
                        .combinedClickable(onClick = { onSelectTab(list.id) }, onLongClick = { menuList = list })
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(list.name, color = if (selected) Color.White else color, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
                androidx.compose.material3.DropdownMenu(expanded = menuList?.id == list.id, onDismissRequest = { menuList = null }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.organizer_rename_list)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null) },
                        onClick = {
                            menuList = null
                            renaming = list to list.name
                        },
                    )
                    if (state.lists.size > 1) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_trash), null) },
                            onClick = {
                                menuList = null
                                if (selectedTab == list.id) onSelectTab(state.lists.first { it.id != list.id }.id)
                                repository.removeList(list.id)
                            },
                        )
                    }
                }
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

    renaming?.let { (list, name) ->
        NewListDialog(
            name = name,
            title = stringResource(R.string.organizer_rename_list),
            onNameChange = { renaming = list to it },
            onDismiss = { renaming = null },
            onConfirm = {
                if (name.isNotBlank()) repository.renameList(list.id, name.trim())
                renaming = null
            },
        )
    }

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
                val due = Date(todo.dueAtMillis)
                val dateText = rememberDateFormat("dMMM").format(due)
                val text = if (todo.hasTime) "$dateText, ${rememberTimeFormat().format(due)}" else dateText
                Text(text, color = color.copy(alpha = 0.5f), fontSize = 12.sp)
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
private fun NewListDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String = stringResource(R.string.organizer_new_list),
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
    allowNoDate: Boolean = true,
) {
    val context = LocalContext.current
    val dateFormat = rememberDateFormat("dMMMMy")
    val timeFormat = rememberTimeFormat()
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
                Text(date?.let { dateFormat.format(it.toDate()) } ?: stringResource(R.string.organizer_pick_date))
            }
            Spacer(Modifier.weight(1f))
            if (date != null && allowNoDate) TextButton(onClick = { onDateChange(null) }) { Text(stringResource(R.string.organizer_no_date)) }
        }
        if (date != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.organizer_set_time), modifier = Modifier.weight(1f))
                Switch(checked = hasTime, onCheckedChange = onHasTimeChange)
            }
            if (hasTime) {
                TextButton(onClick = { showTimePicker = true }) {
                    Text(timeFormat.format(Date(date.atTime(time).atZone(zone).toInstant().toEpochMilli())))
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
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
    if (showTimePicker) {
        val state = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(state.hour, state.minute))
                    showTimePicker = false
                }) { Text(stringResource(android.R.string.ok)) }
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
                TextButton(enabled = title.isNotBlank(), onClick = {
                    val due = date?.let { d -> if (hasTime) d.atTime(time).atZone(zone).toInstant().toEpochMilli() else d.startOfDayMillis() }
                    onSave(title.trim(), notes, listId, due, hasTime && date != null)
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
                allowNoDate = false,
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
