package io.github.davydgky.launcher.island

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
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
 * A quick note from the island: one line, saved as a reminder in the first list of the organizer, so
 * it is found again on the organizer page and in the search.
 */
class QuickNoteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherTheme {
                var text by rememberSaveable { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                val save = {
                    val title = text.trim()
                    if (title.isNotEmpty()) {
                        val organizer = launcherApp.organizer
                        val list = organizer.organizer.value?.lists?.minByOrNull { it.order }
                        if (list != null) {
                            organizer.addTodo(list.id, title, dueAtMillis = null, hasTime = false)
                            launcherApp.island.confirm(getString(R.string.quick_note_saved), R.drawable.ic_note)
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
                                minLines = 2,
                            )
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
