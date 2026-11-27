package io.github.davydgky.launcher.island

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.organizer.OrganizerRepository
import io.github.davydgky.launcher.organizer.Todo
import io.github.davydgky.launcher.organizer.TodoList
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DayStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The reminders as they open out of the island: a black panel that grows from the camera, the mascot at
 * its top as in the opened island, pastel chips for the day and the time instead of the system's dialogs.
 * [MODE_ADD] writes a new one, [MODE_ALL] shows them all, grouped by when they are due. It is a screen of
 * its own only so the keyboard can come up; while it is open the island steps aside for it.
 */
open class IslandRemindersActivity : ComponentActivity() {

    protected open val startMode: String get() = intent.getStringExtra(EXTRA_MODE) ?: MODE_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Drawn up to the camera and around it, where the island is.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val app = launcherApp
        app.organizer.start()
        val add = startMode == MODE_ADD
        setContent {
            IslandRemindersScreen(
                repository = app.organizer,
                geometry = app.island.geometry,
                adding = add,
                onSaved = { app.island.confirm(getString(R.string.quick_note_saved), R.drawable.ic_bell) },
                onClose = { finish() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        launcherApp.island.cover(true)
        launcherApp.organizer.syncWithPeer()
    }

    /**
     * The panel takes the island's place around the camera, where the status bar is. The status bar would
     * be drawn over it (an app's window lies below it, the island's above): it goes while this is open, a
     * swipe from the top brings it back for a moment.
     */
    private fun hideStatusBar() {
        window.insetsController?.let {
            it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(android.view.WindowInsets.Type.statusBars())
        }
    }

    override fun onPause() {
        launcherApp.island.cover(false)
        super.onPause()
    }

    override fun finish() {
        super.finish()
        // Its own animation took it back into the island; the window's would slide it away again.
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_ADD = "add"
        const val MODE_ALL = "all"
    }
}

/** A new reminder from the island: the same screen, straight at writing one. */
class QuickNoteActivity : IslandRemindersActivity() {
    override val startMode: String get() = MODE_ADD
}

// region Colours and shapes, as in the island

private val Panel = Color.Black
private val Card = Color(0xFF1C1C1E)
private val CardHigh = Color(0xFF2C2C2E)
private val Muted = Color(0xFF8E8E93)
private val Overdue = Color(0xFFFF6B6B)
private val Accent = MascotArt.Mint
private val OnAccent = MascotArt.Charcoal

/** The lists in the mascot's pastels, by their colour number. */
private val ListColors = listOf(MascotArt.Mint, MascotArt.LeftArm, MascotArt.RightArm, MascotArt.Belly, Color(0xFFFFE08A), Color(0xFFA8D8FF))

private fun TodoList.color() = ListColors[colorIndex.mod(ListColors.size)]

// endregion

@Composable
internal fun IslandRemindersScreen(
    repository: OrganizerRepository,
    geometry: IslandGeometry?,
    adding: Boolean,
    onSaved: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = LocalView.current
    val g = geometry ?: remember(view) { fallbackGeometry(view, density.density) }
    val pillTop = with(density) { g.pillTop.toDp() }
    val pillHeight = with(density) { g.pillHeight.toDp() }
    val holeGap = with(density) { g.holeWidth.toDp() } + 16.dp

    // Grows out of the camera as the island does, and goes back into it.
    val appear = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 380f)) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                appear.animateTo(0f, tween(170))
                onClose()
            }
        }
    }
    var bounce by remember { mutableIntStateOf(0) }

    BackHandler { close() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerTaps(close),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                .padding(top = pillTop, bottom = 10.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val width = minOf(maxWidth - 16.dp, 440.dp)
            Column(
                Modifier
                    .width(width)
                    .heightIn(max = maxHeight)
                    .graphicsLayer {
                        val t = appear.value
                        scaleX = 0.3f + 0.7f * t
                        scaleY = 0.12f + 0.88f * t
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .clip(RoundedCornerShape(36.dp))
                    .background(Panel)
                    // Taps on the panel stay on it; only those beside it close it.
                    .pointerTaps {}
                    .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 500f)),
            ) {
                // The row around the camera, as in the opened island: the mascot left of it, what this is right
                // of it, the camera itself left free.
                Row(
                    Modifier.fillMaxWidth().height(pillHeight + 10.dp).padding(start = 20.dp, end = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        MascotInPill(pillHeight + 6.dp, MascotMood.Happy, bounce = bounce)
                    }
                    Spacer(Modifier.width(holeGap))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (adding) {
                            Text(stringResource(R.string.quick_note_title), color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            val state by repository.organizer.collectAsState()
                            val open = state?.todos.orEmpty().count { !it.done }
                            Text(stringResource(R.string.isr_open, open), color = Muted, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
                if (!adding) {
                    Text(
                        stringResource(R.string.reminders_label),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 22.dp, top = 2.dp, bottom = 12.dp),
                    )
                }
                if (adding) {
                    Composer(repository, onCancel = close) {
                        bounce++
                        onSaved()
                        scope.launch {
                            // The mascot is glad for a moment, then it all goes back into the island.
                            delay(260)
                            close()
                        }
                    }
                } else {
                    AllReminders(repository, onAdded = { bounce++ })
                }
            }
        }
    }
}

private fun Modifier.pointerTaps(onTap: () -> Unit): Modifier =
    this.then(Modifier.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onTap))

/** Where the island would be, from the camera cutout, when the island itself is off. */
private fun fallbackGeometry(view: android.view.View, density: Float): IslandGeometry {
    val metrics = view.resources.displayMetrics
    val insets = view.rootWindowInsets
    val statusBar = insets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top?.takeIf { it > 0 } ?: (32 * density).toInt()
    val hole = insets?.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }
    val pill = (27 * density).toInt()
    val centerY = hole?.centerY() ?: (statusBar / 2)
    return IslandGeometry(
        screenWidth = metrics.widthPixels,
        screenHeight = metrics.heightPixels,
        centerX = hole?.centerX() ?: (metrics.widthPixels / 2),
        holeWidth = minOf(hole?.width() ?: 0, (24 * density).toInt()),
        holeHeight = minOf(hole?.height() ?: 0, (24 * density).toInt()),
        pillTop = (centerY - pill / 2).coerceAtLeast((2 * density).toInt()),
        pillHeight = pill,
        density = density,
    )
}

// region A new reminder

@Composable
private fun Composer(repository: OrganizerRepository, onCancel: () -> Unit, onSaved: () -> Unit) {
    val state by repository.organizer.collectAsState()
    val lists = state?.lists.orEmpty().sortedBy { it.order }
    var text by rememberSaveable { mutableStateOf("") }
    var due by remember { mutableStateOf(Due.None) }
    var listId by rememberSaveable { mutableStateOf<Long?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = {
        val title = text.trim()
        val list = lists.firstOrNull { it.id == listId } ?: lists.firstOrNull()
        if (title.isNotEmpty() && list != null) {
            repository.addTodo(list.id, title, due.millis(), due.hasTime)
            text = ""
            onSaved()
        }
    }
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 18.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(horizontal = 4.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            maxLines = 3,
            decorationBox = { field ->
                Box {
                    if (text.isEmpty()) Text(stringResource(R.string.isr_placeholder), color = Muted, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    field()
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        DuePicker(due) { due = it }
        if (lists.size > 1) {
            Spacer(Modifier.height(10.dp))
            ListPicker(lists, listId ?: lists.first().id) { listId = it }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.quick_note_cancel),
                color = Muted,
                fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onCancel).padding(horizontal = 10.dp, vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            val ready = text.isNotBlank()
            val background by animateColorAsState(if (ready) Accent else CardHigh, label = "save")
            Row(
                Modifier
                    .height(46.dp)
                    .clip(CircleShape)
                    .background(background)
                    .clickable(enabled = ready) { save() }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = if (ready) OnAccent else Muted)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.quick_note_save), color = if (ready) OnAccent else Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// endregion

// region When it is due

/** A day and maybe a time; [None] is a reminder without a date. */
private data class Due(val date: LocalDate?, val minutes: Int?) {
    val hasTime get() = date != null && minutes != null

    fun millis(): Long? {
        val day = date ?: return null
        val zone = ZoneId.systemDefault()
        return if (minutes == null) day.atStartOfDay(zone).toInstant().toEpochMilli() else day.atTime(minutes / 60, minutes % 60).atZone(zone).toInstant().toEpochMilli()
    }

    companion object {
        val None = Due(null, null)

        fun of(todo: Todo): Due {
            val at = todo.dueAtMillis ?: return None
            val time = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            return Due(time.toLocalDate(), if (todo.hasTime) time.hour * 60 + time.minute else null)
        }
    }
}

private val QuickTimes = listOf(9 * 60, 12 * 60, 15 * 60, 18 * 60, 20 * 60)

/**
 * Days as chips (today, tomorrow, the two weeks after), and once a day is chosen the times of day: the
 * usual ones as chips, any other with the clock's own input. Nothing opens over it.
 */
@Composable
private fun DuePicker(due: Due, onChange: (Due) -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val today = remember { LocalDate.now() }
    val nowMinutes = remember { java.time.LocalTime.now().let { it.hour * 60 + it.minute } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
            item {
                Pill(stringResource(R.string.isr_no_date), selected = due.date == null) { onChange(Due.None) }
            }
            items((0L..13L).toList()) { offset ->
                val day = today.plusDays(offset)
                val label = when (offset) {
                    0L -> stringResource(R.string.reminder_today)
                    1L -> stringResource(R.string.reminder_tomorrow)
                    else -> day.dayOfWeek.getDisplayName(DayStyle.SHORT, locale).trimEnd('.') + " " + day.dayOfMonth + "."
                }
                Pill(label, selected = due.date == day) {
                    // A time that has passed today moves along with the day.
                    val minutes = due.minutes?.takeIf { day != today || it > nowMinutes }
                    onChange(Due(day, minutes))
                }
            }
            // A date further away than two weeks stays as it was, shown as its own chip.
            if (due.date != null && (due.date.isBefore(today) || due.date.isAfter(today.plusDays(13)))) {
                item {
                    Pill(android.text.format.DateUtils.formatDateTime(context, due.millis() ?: 0L, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH), selected = true) {}
                }
            }
        }
        AnimatedVisibility(due.date != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            var other by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    item {
                        Pill(stringResource(R.string.isr_all_day), selected = due.minutes == null && !other) {
                            other = false
                            onChange(due.copy(minutes = null))
                        }
                    }
                    items(QuickTimes) { minutes ->
                        val past = due.date == today && minutes <= nowMinutes
                        Pill(timeLabel(context, minutes), selected = due.minutes == minutes && !other, enabled = !past) {
                            other = false
                            onChange(due.copy(minutes = minutes))
                        }
                    }
                    item {
                        val custom = due.minutes != null && due.minutes !in QuickTimes
                        Pill(if (custom && !other) timeLabel(context, due.minutes!!) else stringResource(R.string.isr_other_time), selected = other || custom) {
                            other = !other
                        }
                    }
                }
                AnimatedVisibility(other) {
                    ClockInput(due.minutes ?: ((nowMinutes / 60 + 1) % 24) * 60) { onChange(due.copy(minutes = it)) }
                }
            }
        }
    }
}

private fun timeLabel(context: android.content.Context, minutes: Int): String {
    val at = LocalDate.now().atTime(minutes / 60, minutes % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return DateFormat.getTimeFormat(context).format(java.util.Date(at))
}

/** Hours and minutes typed in, in the island's colours. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ClockInput(initial: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initial / 60, initial % 60, DateFormat.is24HourFormat(context))
    LaunchedEffect(state.hour, state.minute) { onChange(state.hour * 60 + state.minute) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = OnAccent, surface = Panel, onSurface = Color.White)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TimeInput(
                state,
                colors = TimePickerDefaults.colors(
                    timeSelectorSelectedContainerColor = Accent.copy(alpha = 0.22f),
                    timeSelectorUnselectedContainerColor = Card,
                    timeSelectorSelectedContentColor = Color.White,
                    timeSelectorUnselectedContentColor = Color.White,
                    periodSelectorSelectedContainerColor = Accent.copy(alpha = 0.22f),
                    periodSelectorUnselectedContainerColor = Card,
                    periodSelectorBorderColor = CardHigh,
                ),
            )
        }
    }
}

@Composable
private fun ListPicker(lists: List<TodoList>, selected: Long, onSelect: (Long) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        items(lists, key = { it.id }) { list ->
            Pill(list.name, selected = list.id == selected, accent = list.color(), dot = list.color()) { onSelect(list.id) }
        }
    }
}

/** A rounded chip: pastel with dark text when chosen, a dark grey one otherwise. */
@Composable
private fun Pill(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    accent: Color = Accent,
    dot: Color? = null,
    onClick: () -> Unit,
) {
    // Dark grey also on the grey cards of the list, so an unchosen chip still reads as one.
    val background by animateColorAsState(if (selected) accent else CardHigh, label = "pill")
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null && !selected) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            color = when {
                selected -> OnAccent
                enabled -> Color.White
                else -> Muted.copy(alpha = 0.45f)
            },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

// endregion

// region All of them

private enum class Group(val label: Int) { Overdue(R.string.isr_overdue), Today(R.string.isr_today), Tomorrow(R.string.isr_tomorrow), Week(R.string.isr_week), Later(R.string.isr_later), NoDate(R.string.isr_no_date) }

private fun groupOf(todo: Todo, now: Long, today: LocalDate): Group {
    val due = todo.dueAtMillis ?: return Group.NoDate
    val date = java.time.Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        if (todo.hasTime) due < now else date.isBefore(today) -> Group.Overdue
        date == today -> Group.Today
        date == today.plusDays(1) -> Group.Tomorrow
        date.isBefore(today.plusDays(8)) -> Group.Week
        else -> Group.Later
    }
}

@Composable
private fun AllReminders(repository: OrganizerRepository, onAdded: () -> Unit) {
    val state by repository.organizer.collectAsState()
    val lists = state?.lists.orEmpty().sortedBy { it.order }
    val todos = state?.todos.orEmpty()
    var shownList by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val today = remember(now) { LocalDate.now() }
    val inList = todos.filter { shownList == null || it.listId == shownList }
    val open = inList.filter { !it.done }
        .sortedWith(compareBy<Todo>({ it.dueAtMillis ?: Long.MAX_VALUE }, { it.order }, { it.createdAtMillis }))
        .groupBy { groupOf(it, now, today) }
    val done = inList.filter { it.done }.sortedByDescending { it.doneAtMillis ?: 0L }
    val suggestions = if (shownList == null) state?.suggestions.orEmpty() else emptyList()
    val listsById = lists.associateBy { it.id }

    Column(Modifier.padding(bottom = 14.dp)) {
        if (lists.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                item { Pill(stringResource(R.string.isr_all_lists), selected = shownList == null) { shownList = null } }
                items(lists, key = { it.id }) { list ->
                    Pill(list.name, selected = shownList == list.id, accent = list.color(), dot = list.color()) { shownList = list.id }
                }
            }
        }
        AddRow { title ->
            val list = lists.firstOrNull { it.id == shownList } ?: lists.firstOrNull()
            if (list != null) {
                repository.addTodo(list.id, title, null, false)
                onAdded()
            }
        }
        LazyColumn(
            Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (suggestions.isNotEmpty()) {
                item(key = "suggestions") {
                    GroupCard(stringResource(R.string.isr_suggestions), MascotArt.LeftArm) {
                        suggestions.forEach { suggestion ->
                            SuggestionRow(
                                suggestion.title,
                                listOf(suggestion.app, suggestion.sender).filter { it.isNotBlank() }.joinToString(" · "),
                                onAccept = { (lists.firstOrNull()?.id)?.let { repository.acceptSuggestion(suggestion.id, it) } },
                                onDismiss = { repository.dismissSuggestion(suggestion.id) },
                            )
                        }
                    }
                }
            }
            if (open.isEmpty() && suggestions.isEmpty()) {
                item(key = "nothing") {
                    Text(
                        stringResource(R.string.isr_nothing),
                        color = Muted,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 8.dp),
                    )
                }
            }
            Group.entries.forEach { group ->
                val rows = open[group] ?: return@forEach
                item(key = group.name) {
                    GroupCard(stringResource(group.label), if (group == Group.Overdue) Overdue else Muted) {
                        rows.forEach { todo ->
                            ReminderItem(
                                todo, now, lists, listsById[todo.listId]?.takeIf { shownList == null && lists.size > 1 },
                                editing = editing == todo.id,
                                onEdit = { editing = if (editing == todo.id) null else todo.id },
                                repository = repository,
                            )
                        }
                    }
                }
            }
            if (done.isNotEmpty()) {
                item(key = "done") {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { showDone = !showDone }.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.isr_done, done.size), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            val turn by animateFloatAsState(if (showDone) 90f else 0f, label = "chevron")
                            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.padding(start = 4.dp).size(16.dp).graphicsLayer { rotationZ = turn }, tint = Muted)
                            Spacer(Modifier.weight(1f))
                            if (showDone) {
                                Text(
                                    stringResource(R.string.isr_clear_done),
                                    color = MascotArt.RightArm,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { done.forEach { repository.removeTodo(it.id) } }.padding(6.dp),
                                )
                            }
                        }
                        AnimatedVisibility(showDone) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Card).padding(vertical = 4.dp)) {
                                done.take(MAX_DONE).forEach { todo ->
                                    ReminderItem(todo, now, lists, null, editing = false, onEdit = {}, repository = repository)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_DONE = 30

@Composable
private fun GroupCard(title: String, color: Color, content: @Composable () -> Unit) {
    Column {
        Text(title, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Card).padding(vertical = 4.dp)) { content() }
    }
}

/** "New reminder" written straight in, at the top of the list. */
@Composable
private fun AddRow(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val add = {
        val title = text.trim()
        if (title.isNotEmpty()) onAdd(title)
        text = ""
    }
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(Card).padding(start = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (text.isBlank()) CardHigh else Accent).clickable { add() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp), tint = if (text.isBlank()) Accent else OnAccent)
        }
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(stringResource(R.string.isr_add_placeholder), color = Muted, fontSize = 16.sp)
                    field()
                }
            },
        )
    }
}

/**
 * One reminder: the circle ticks it off (it fills, then the reminder goes), a tap on the rest opens it
 * right there to change its words, its day and time, its list, or to delete it.
 */
@Composable
private fun ReminderItem(
    todo: Todo,
    now: Long,
    lists: List<TodoList>,
    list: TodoList?,
    editing: Boolean,
    onEdit: () -> Unit,
    repository: OrganizerRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val due = todo.dueAtMillis
    val overdue = !todo.done && due != null && (if (todo.hasTime) due < now else due < LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    val label = remember(due, todo.hasTime, now) { due?.let { dueLabel(context, it, todo.hasTime) } }
    var ticking by remember(todo.id, todo.done) { mutableStateOf(false) }
    val checked = todo.done || ticking
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !todo.done, onClick = onEdit).padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckCircle(checked, overdue, list?.color()) {
                if (todo.done) {
                    repository.toggleDone(todo.id)
                } else if (!ticking) {
                    ticking = true
                    scope.launch {
                        delay(450)
                        repository.toggleDone(todo.id)
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    todo.title,
                    color = if (checked) Muted else Color.White,
                    fontSize = 15.sp,
                    textDecoration = if (checked) TextDecoration.LineThrough else null,
                    maxLines = if (editing) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (todo.notes.isNotBlank() && !editing) {
                    Text(todo.notes, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (label != null) {
                Spacer(Modifier.width(8.dp))
                Text(label, color = if (overdue) Overdue else Muted, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (editing) Editor(todo, lists, repository, onDone = onEdit)
    }
}

@Composable
private fun CheckCircle(checked: Boolean, overdue: Boolean, ring: Color?, onClick: () -> Unit) {
    val fill by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "check")
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 1.8.dp.toPx()
            val color = when {
                overdue -> Overdue
                ring != null -> ring
                else -> Muted
            }
            drawCircle(color, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            if (fill > 0f) {
                drawCircle(Accent, radius = (size.minDimension / 2) * fill)
                val w = size.width
                val tick = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.28f, w * 0.52f)
                    lineTo(w * 0.44f, w * 0.67f)
                    lineTo(w * 0.73f, w * 0.36f)
                }
                drawPath(tick, OnAccent.copy(alpha = fill.coerceIn(0f, 1f)), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun Editor(todo: Todo, lists: List<TodoList>, repository: OrganizerRepository, onDone: () -> Unit) {
    var title by remember(todo.id) { mutableStateOf(todo.title) }
    val commit = {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty() && trimmed != todo.title) repository.updateTodo(todo.id) { it.copy(title = trimmed) }
    }
    Column(Modifier.padding(start = 16.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            maxLines = 4,
        )
        DuePicker(Due.of(todo)) { due ->
            repository.updateTodo(todo.id) { it.copy(dueAtMillis = due.millis(), hasTime = due.hasTime) }
        }
        if (lists.size > 1) {
            ListPicker(lists, todo.listId) { id -> repository.updateTodo(todo.id) { it.copy(listId = id) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.isr_delete),
                color = MascotArt.RightArm,
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { repository.removeTodo(todo.id) }.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.isr_finish),
                color = OnAccent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Accent).clickable {
                    commit()
                    onDone()
                }.padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun SuggestionRow(title: String, source: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (source.isNotBlank()) Text(source, color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        SmallRound(R.drawable.ic_close, CardHigh, Muted, onDismiss)
        Spacer(Modifier.width(6.dp))
        SmallRound(R.drawable.ic_add, Accent, OnAccent, onAccept)
    }
}

@Composable
private fun SmallRound(icon: Int, background: Color, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(background).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(16.dp), tint = tint)
    }
}

// endregion
