package io.github.davydgky.launcher.home

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import io.github.davydgky.launcher.widgets.WidgetHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Answers "Add to home screen" from other apps: a shortcut (a page in Chrome, a chat, a contact) or a
 * widget an app offers. Shows it and puts it on the home screen when confirmed.
 */
class AddShortcutActivity : ComponentActivity() {

    private var pendingWidget: PendingWidget? = null

    private class PendingWidget(val request: LauncherApps.PinItemRequest, val info: AppWidgetProviderInfo, val id: Int)

    private val bind = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingWidget ?: return@registerForActivityResult finish()
        if (result.resultCode == RESULT_OK) acceptWidget(pending) else cancelWidget(pending)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = runCatching { launcherApps.getPinItemRequest(intent) }.getOrNull()
        if (request != null && request.isValid && request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET) {
            val widget = request.getAppWidgetProviderInfo(this)
            if (widget == null) finish() else confirmWidget(request, widget)
            return
        }
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

    private fun confirmWidget(request: LauncherApps.PinItemRequest, info: AppWidgetProviderInfo) {
        val label = info.loadLabel(packageManager).orEmpty()
        setContent {
            LauncherTheme {
                val preview by produceState<ImageBitmap?>(null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { info.loadPreviewImage(this@AddShortcutActivity, resources.displayMetrics.densityDpi) ?: info.loadIcon(this@AddShortcutActivity, resources.displayMetrics.densityDpi) }
                            .getOrNull()?.let(launcherApp.icons::drawable)
                    }
                }
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(stringResource(R.string.add_widget_title), textAlign = TextAlign.Center) },
                    text = {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            preview?.let { Image(it, null, Modifier.heightIn(max = 160.dp)) }
                            Text(label, textAlign = TextAlign.Center)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { startWidget(request, info) }) { Text(stringResource(R.string.add_shortcut_confirm)) }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }

    /** As for the widget list: an id, permission to bind (asked once), then the widget's own setup. */
    private fun startWidget(request: LauncherApps.PinItemRequest, info: AppWidgetProviderInfo) {
        val widgets = launcherApp.widgets
        val pending = PendingWidget(request, info, widgets.allocateId())
        pendingWidget = pending
        if (widgets.bindIfAllowed(pending.id, info)) {
            acceptWidget(pending)
        } else {
            bind.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending.id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
            )
        }
    }

    private fun acceptWidget(pending: PendingWidget) {
        val accepted = pending.request.isValid &&
            runCatching { pending.request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, pending.id) }) }.getOrDefault(false)
        if (!accepted) return cancelWidget(pending)
        val widgets = launcherApp.widgets
        if (widgets.needsConfiguration(pending.info)) {
            try {
                widgets.startConfiguration(this, pending.id, REQUEST_CONFIGURE)
                return
            } catch (e: Exception) {
                // Not exported: the widget then works with its defaults.
                Log.w(TAG, "Cannot configure ${pending.info.provider}", e)
            }
        }
        placeWidget(pending)
    }

    @Deprecated("The widget configuration screen reports back only through this")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return
        val pending = pendingWidget ?: return finish()
        if (resultCode == RESULT_OK) placeWidget(pending) else cancelWidget(pending)
    }

    private fun placeWidget(pending: PendingWidget) {
        pendingWidget = null
        val ws = launcherApp.workspace.workspace.value
        val columns = ws?.columns ?: 5
        val rows = ws?.rows ?: 6
        val config = resources.configuration
        // About the cell size of the home screen: its width shared by the columns, most of its height by the rows.
        val span = WidgetHost.defaultSpan(pending.info, config.screenWidthDp / columns.toFloat(), config.screenHeightDp * 0.72f / rows, columns, rows)
        launcherApp.workspace.addWidget(pending.id, pending.info.provider.flattenToString(), span.w, span.h, page = 0)
        finish()
    }

    private fun cancelWidget(pending: PendingWidget) {
        pendingWidget = null
        launcherApp.widgets.deleteId(pending.id)
        finish()
    }

    private companion object {
        const val TAG = "AddShortcutActivity"
        const val REQUEST_CONFIGURE = 42
    }
}
