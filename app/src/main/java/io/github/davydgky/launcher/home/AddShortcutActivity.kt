package io.github.davydgky.launcher.home

import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.ui.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Answers "Add to home screen" from other apps (a page in Chrome, a chat, a contact): shows the
 * shortcut and puts it on the home screen when confirmed. Widget requests are not offered yet.
 */
class AddShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = runCatching { launcherApps.getPinItemRequest(intent) }.getOrNull()
        val info = request?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && it.isValid }?.shortcutInfo
        if (request == null || info == null) {
            finish()
            return
        }
        val label = (info.shortLabel ?: info.longLabel)?.toString().orEmpty()
        val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(info.userHandle)
        val key = "shortcut:$serial/${info.`package`}/${info.id}"
        setContent {
            LauncherTheme {
                val icon by produceState<ImageBitmap?>(null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { launcherApps.getShortcutBadgedIconDrawable(info, resources.displayMetrics.densityDpi) }
                            .getOrNull()?.let(launcherApp.icons::drawable)
                    }
                }
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(stringResource(R.string.add_shortcut_title), textAlign = TextAlign.Center) },
                    text = {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            icon?.let { Image(it, null, Modifier.size(64.dp)) }
                            Text(label, textAlign = TextAlign.Center)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            // Accepting pins the shortcut for the launcher; the arrangement then shows it.
                            if (request.isValid && runCatching { request.accept() }.getOrDefault(false)) {
                                val workspace = launcherApp.workspace
                                workspace.addShortcut(key, label, page = 0)
                            }
                            finish()
                        }) { Text(stringResource(R.string.add_shortcut_confirm)) }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }
}
