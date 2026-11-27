package io.github.davydgky.launcher.island

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.UrlCleaner
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.ui.LauncherTheme

/**
 * A new reminder from the island, as on the organizer page: a title, and if wanted a day (today,
 * tomorrow) and a time. It goes into the first list of the organizer; with a time it reminds then.
 */
class QuickNoteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launcherApp.organizer.start()
        setContent {
            LauncherTheme {
                var text by rememberSaveable { mutableStateOf("") }
                // 0 no day, 1 today, 2 tomorrow.
                var day by rememberSaveable { mutableStateOf(0) }
                var minutes by rememberSaveable { mutableStateOf<Int?>(null) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                val save = {
                    val title = text.trim()
                    if (title.isNotEmpty()) {
                        val organizer = launcherApp.organizer
                        val list = organizer.organizer.value?.lists?.minByOrNull { it.order }
                        if (list != null) {
                            val due = dueOf(day, minutes)
                            organizer.addTodo(list.id, title, dueAtMillis = due, hasTime = due != null && minutes != null)
                            launcherApp.island.confirm(getString(R.string.quick_note_saved), R.drawable.ic_bell)
                        } else {
                            Toast.makeText(this, R.string.quick_note_not_ready, Toast.LENGTH_SHORT).show()
                        }
                    }
                    finish()
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .pointerInput(Unit) { detectTapGestures { finish() } }
                        .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(16.dp).fillMaxWidth().pointerInput(Unit) { detectTapGestures { } },
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(stringResource(R.string.quick_note_title), style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(R.string.quick_note_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                            )
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { save() }),
                                singleLine = true,
                            )
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = day == 1, onClick = { day = if (day == 1) 0 else 1 }, label = { Text(stringResource(R.string.reminder_today)) })
                                FilterChip(selected = day == 2, onClick = { day = if (day == 2) 0 else 2 }, label = { Text(stringResource(R.string.reminder_tomorrow)) })
                                val timeLabel = minutes?.let { m -> DateFormat.getTimeFormat(this@QuickNoteActivity).format(java.util.Date(dueOf(1, m)!!)) }
                                FilterChip(
                                    selected = minutes != null,
                                    onClick = {
                                        val now = java.time.LocalTime.now().plusHours(1)
                                        TimePickerDialog(
                                            this@QuickNoteActivity,
                                            { _, hour, minute ->
                                                minutes = hour * 60 + minute
                                                if (day == 0) day = 1
                                            },
                                            minutes?.div(60) ?: now.hour,
                                            minutes?.rem(60) ?: 0,
                                            DateFormat.is24HourFormat(this@QuickNoteActivity),
                                        ).show()
                                    },
                                    label = { Text(timeLabel ?: stringResource(R.string.reminder_time)) },
                                )
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                TextButton(onClick = { finish() }) { Text(stringResource(R.string.quick_note_cancel)) }
                                Button(onClick = { save() }, enabled = text.isNotBlank()) { Text(stringResource(R.string.quick_note_save)) }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The due time: [day] 1 today, 2 tomorrow, 0 none; [minutes] after midnight or null for the whole day.
     * A time today that has passed already means tomorrow.
     */
    private fun dueOf(day: Int, minutes: Int?): Long? {
        if (day == 0 && minutes == null) return null
        val zone = java.time.ZoneId.systemDefault()
        var date = java.time.LocalDate.now(zone).plusDays(if (day == 2) 1 else 0)
        if (minutes == null) return date.atStartOfDay(zone).toInstant().toEpochMilli()
        var at = date.atTime(minutes / 60, minutes % 60).atZone(zone).toInstant().toEpochMilli()
        if (day != 2 && at < System.currentTimeMillis()) {
            date = date.plusDays(1)
            at = date.atTime(minutes / 60, minutes % 60).atZone(zone).toInstant().toEpochMilli()
        }
        return at
    }
}

/**
 * "Share" from any app: into the island's tray ([ShareActivity.TRAY]), or as a cleaned link straight to
 * the clipboard ([ShareActivity.CLEAN_LINK]). Shows nothing itself; the island confirms.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val island = launcherApp.island
        when (intent.component?.className) {
            CLEAN_LINK -> {
                val link = UrlCleaner.firstLink(text)
                if (link != null) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(subject.ifBlank { "Link" }, link))
                    Toast.makeText(this, R.string.share_clean_link_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_SHORT).show()
                }
            }
            else -> if (text.isNotEmpty()) {
                // A shared link often comes with its page title in front: the link is what is kept.
                island.addToShelf(UrlCleaner.firstLink(text)?.takeIf { text.length < it.length + 200 } ?: text)
                island.confirm(getString(R.string.share_tray_done), R.drawable.ic_check)
                Toast.makeText(this, R.string.share_tray_done, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        const val TRAY = "io.github.davydgky.launcher.island.ShareToTray"
        const val CLEAN_LINK = "io.github.davydgky.launcher.island.CleanLink"
    }
}
