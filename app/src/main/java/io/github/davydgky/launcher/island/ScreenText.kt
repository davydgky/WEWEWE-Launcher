package io.github.davydgky.launcher.island

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.ui.LauncherTheme
import io.github.davydgky.launcher.ui.SearchActivity

/** Text on the screen, read on the device with the same ML Kit model as the photo search. */
object ScreenText {

    /** The text last read, for [ScreenTextActivity]; only in memory. */
    @Volatile var last: String? = null

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun recognize(bitmap: Bitmap, onResult: (String?) -> Unit) {
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> onResult(result.textBlocks.joinToString("\n\n") { it.text }) }
            .addOnFailureListener { onResult(null) }
    }
}

/** Shows the text read from the screen, to select, copy or search for. */
class ScreenTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = ScreenText.last.orEmpty()
        setContent {
            LauncherTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) {
                        Text(stringResource(R.string.screen_text_title), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.screen_text_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                        )
                        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            Text(text, style = MaterialTheme.typography.bodyLarge)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                startActivity(SearchActivity.intent(this@ScreenTextActivity, text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80)))
                                finish()
                            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.screen_text_search)) }
                            Button(onClick = {
                                launcherApp.island.copy(text)
                                launcherApp.island.confirm(getString(R.string.island_copied), R.drawable.ic_copy)
                                finish()
                            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.screen_text_copy_all)) }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The text stays in memory only while it is shown.
        if (isFinishing) ScreenText.last = null
    }
}
