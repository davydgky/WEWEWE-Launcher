package io.github.davydgky.launcher.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.ai.NanoAnswers
import io.github.davydgky.launcher.data.LauncherPrefs
import io.github.davydgky.launcher.index.MediaCatalog
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.messages.Messages
import io.github.davydgky.launcher.perf.Metric
import io.github.davydgky.launcher.perf.MetricSummary
import io.github.davydgky.launcher.search.SourceStatus
import io.github.davydgky.launcher.sources.AppsSource
import io.github.davydgky.launcher.sources.CalendarSource
import io.github.davydgky.launcher.sources.ContactsSource
import io.github.davydgky.launcher.sources.DocumentsSource
import io.github.davydgky.launcher.sources.ImagesSource
import io.github.davydgky.launcher.sources.MessagesSource
import io.github.davydgky.launcher.sources.NotesSource
import io.github.davydgky.launcher.sources.Requirement
import io.github.davydgky.launcher.sources.SettingsSource
import io.github.davydgky.launcher.sources.ShortcutsSource
import io.github.davydgky.launcher.sources.SmsSource
import io.github.davydgky.launcher.sources.Velo
import io.github.davydgky.launcher.sources.VeloSource
import io.github.davydgky.launcher.ui.LauncherTheme
import io.github.davydgky.launcher.update.UpdateState
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sources, permissions, note folders, text recognition progress and latency measurements. */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LauncherTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    SettingsScreen(this)
                }
            }
        }
    }
}

/** Columns × rows the home screen offers. */
private val GridOptions = listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6, 5 to 7, 6 to 5, 6 to 7)

private class SourceInfo(val id: String, val title: Int, val description: Int)

private val SourceInfos = listOf(
    SourceInfo(AppsSource.ID, R.string.source_apps, R.string.source_apps_description),
    SourceInfo(SettingsSource.ID, R.string.source_settings, R.string.source_settings_description),
    SourceInfo(ShortcutsSource.ID, R.string.source_shortcuts, R.string.source_shortcuts_description),
    SourceInfo(ContactsSource.ID, R.string.source_contacts, R.string.source_contacts_description),
    SourceInfo(MessagesSource.ID, R.string.source_messages, R.string.source_messages_description),
    SourceInfo(SmsSource.ID, R.string.source_sms, R.string.source_sms_description),
    SourceInfo(CalendarSource.ID, R.string.source_calendar, R.string.source_calendar_description),
    SourceInfo(VeloSource.ID, R.string.source_velo, R.string.source_velo_description),
    SourceInfo(NotesSource.ID, R.string.source_notes, R.string.source_notes_description),
    SourceInfo(DocumentsSource.ID, R.string.source_documents, R.string.source_documents_description),
    SourceInfo(ImagesSource.ID, R.string.source_images, R.string.source_images_description),
)

@Composable
private fun SettingsScreen(activity: ComponentActivity) {
    val context = LocalContext.current
    val app = context.launcherApp
    val scope = rememberCoroutineScope()
    val status by app.search.status.collectAsStateWithLifecycle()
    val prefs by app.settings.prefs.collectAsStateWithLifecycle(initialValue = LauncherPrefs())
    val perf by app.perf.summaries.collectAsStateWithLifecycle()
    val usageAccess by app.usage.hasAccess.collectAsStateWithLifecycle()
    var resumeCount by remember { mutableIntStateOf(0) }
    var isDefaultLauncher by remember { mutableStateOf(isDefaultHome(context)) }
    var notificationAccess by remember { mutableStateOf(app.notifications.hasAccess()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Permissions may have been granted in the system settings meanwhile.
        app.search.onResume()
        app.usage.checkAccess()
        isDefaultLauncher = isDefaultHome(context)
        notificationAccess = app.notifications.hasAccess()
        resumeCount++
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val denied = results.filterValues { !it }.keys
        // Denied twice: Android no longer asks, so lead the user to the app's settings page.
        if (denied.isNotEmpty() && results.values.none { it } &&
            denied.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        ) {
            openAppSettings(context)
        }
        app.search.onResume()
    }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefaultLauncher = isDefaultHome(context)
        app.search.onResume()
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            scope.launch { app.settings.addNoteFolder(uri.toString()) }
        }
    }

    fun grant(requirement: Requirement) {
        when (requirement) {
            Requirement.DefaultLauncher -> roleRequest.launch(context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME))
            Requirement.Contacts -> permission.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            Requirement.Calendar -> permission.launch(arrayOf(Manifest.permission.READ_CALENDAR))
            Requirement.Images -> permission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
            Requirement.VeloPermission -> permission.launch(arrayOf(Velo.PERMISSION))
            Requirement.AllFiles -> context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)),
            )
            Requirement.NoteFolder -> folderPicker.launch(null)
            Requirement.VeloInstall -> Unit
            Requirement.NotificationAccess -> context.startActivity(app.notifications.accessIntent())
            // A sideloaded app may first need "Allow restricted settings" in its app info; a refusal leads there.
            Requirement.Sms -> permission.launch(arrayOf(Manifest.permission.READ_SMS))
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
        }
        if (!isDefaultLauncher) {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.default_launcher_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.default_launcher_description), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(12.dp))
                        Button(onClick = { grant(Requirement.DefaultLauncher) }) { Text(stringResource(R.string.default_launcher_action)) }
                    }
                }
            }
        }
        item {
            Group(stringResource(R.string.settings_home)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.setting_grid), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.setting_grid_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
                    val current = (prefs.gridColumns ?: if (tablet) 6 else 5) to (prefs.gridRows ?: if (tablet) 5 else 6)
                    Row(
                        Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GridOptions.forEach { option ->
                            FilterChip(
                                selected = option == current,
                                onClick = { scope.launch { app.settings.setGrid(option.first, option.second) } },
                                label = { Text("${option.first} × ${option.second}") },
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_add_new_apps), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.setting_add_new_apps_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = prefs.addNewAppsToHome,
                        onCheckedChange = { enabled -> scope.launch { app.settings.setAddNewAppsToHome(enabled) } },
                    )
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_dock_suggestion), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.setting_dock_suggestion_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = prefs.dockSuggestion,
                        onCheckedChange = { enabled -> scope.launch { app.settings.setDockSuggestion(enabled) } },
                    )
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.notification_dots), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.notification_dots_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    // Access is granted (and taken back) on the system page; the launcher cannot switch it itself.
                    OutlinedButton(onClick = { runCatching { context.startActivity(app.notifications.accessIntent()) } }) {
                        Text(stringResource(if (notificationAccess) R.string.notification_dots_on else R.string.notification_dots_allow))
                    }
                }
            }
        }
        item {
            Group(stringResource(R.string.settings_sources)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_results_bottom), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.setting_results_bottom_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = prefs.resultsAtBottom,
                        onCheckedChange = { enabled -> scope.launch { app.settings.setResultsAtBottom(enabled) } },
                    )
                }
                HorizontalDivider()
                AiAnswersRow(prefs.aiAnswers) { enabled ->
                    scope.launch { app.settings.setAiAnswers(enabled) }
                    if (enabled) app.nano.prepare()
                }
                HorizontalDivider()
                SavedMessagesRow(prefs.saveMessages, onToggle = { enabled -> scope.launch { app.settings.setSaveMessages(enabled) } })
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_store_results), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.setting_store_results_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = prefs.storeResults, onCheckedChange = { enabled -> scope.launch { app.settings.setStoreResults(enabled) } })
                }
                HorizontalDivider()
                SourceInfos.forEachIndexed { index, info ->
                    if (index > 0) HorizontalDivider()
                    SourceRow(info, status[info.id], onToggle = { enabled ->
                        scope.launch { app.settings.setSourceEnabled(info.id, enabled) }
                    }, onGrant = ::grant)
                }
                HorizontalDivider()
                UsageRow(usageAccess) {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }
            }
        }
        item {
            Group(stringResource(R.string.settings_note_folders)) {
                prefs.noteFolders.sorted().forEach { folder ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(folderLabel(folder), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            scope.launch { app.settings.removeNoteFolder(folder) }
                        }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.remove)) }
                    }
                    HorizontalDivider()
                }
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.note_folders_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.note_folders_add)) }
                }
            }
        }
        item {
            val progress by produceState<Pair<Int, Int>?>(null, resumeCount, status[ImagesSource.ID]?.itemCount) {
                value = withContext(Dispatchers.IO) { recognitionProgress(context) }
            }
            Group(stringResource(R.string.settings_recognition)) {
                Column(Modifier.padding(16.dp)) {
                    val current = progress
                    Text(
                        when {
                            !ImagesSource.hasAccess(context) -> stringResource(R.string.recognition_no_access)
                            current == null -> stringResource(R.string.recognition_counting)
                            else -> stringResource(R.string.recognition_progress, current.first, current.second)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.recognition_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Group(stringResource(R.string.settings_measurements)) {
                Metric.entries.forEachIndexed { index, metric ->
                    if (index > 0) HorizontalDivider()
                    MetricRow(metric, perf[metric])
                }
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.measurements_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(12.dp))
                    OutlinedButton(onClick = app.perf::reset) { Text(stringResource(R.string.measurements_reset)) }
                }
            }
        }
        item {
            val update by app.updater.state.collectAsStateWithLifecycle()
            UpdateGroup(
                state = update,
                currentVersion = app.updater.currentVersionName,
                autoUpdate = prefs.autoUpdate,
                onAction = app.updater::performAction,
                onAutoUpdate = app.updater::setAutoUpdate,
            )
        }
        item {
            Group(stringResource(R.string.settings_privacy)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.privacy_description), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                    OutlinedButton(onClick = app.search::rebuildAll) { Text(stringResource(R.string.rebuild_index)) }
                }
            }
        }
    }
}

@Composable
private fun UpdateGroup(
    state: UpdateState,
    currentVersion: String,
    autoUpdate: Boolean,
    onAction: () -> Unit,
    onAutoUpdate: (Boolean) -> Unit,
) {
    val (label, supporting) = when (state) {
        UpdateState.Idle -> stringResource(R.string.update_check) to stringResource(R.string.update_current, currentVersion)
        UpdateState.Checking -> stringResource(R.string.update_checking) to stringResource(R.string.update_current, currentVersion)
        UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, currentVersion) to stringResource(R.string.update_check)
        is UpdateState.Available -> stringResource(R.string.update_download, state.release.versionName) to state.release.notes
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.release.versionName, (state.progress * 100).roundToInt()) to ""
        is UpdateState.Ready -> stringResource(R.string.update_install, state.release.versionName) to state.release.notes
        is UpdateState.NeedsPermission -> stringResource(R.string.update_install, state.release.versionName) to stringResource(R.string.update_needs_permission)
        is UpdateState.Scheduled -> stringResource(R.string.update_scheduled, state.release.versionName) to stringResource(R.string.update_install_now)
        is UpdateState.Installing -> stringResource(R.string.update_installing) to ""
        is UpdateState.Failed -> stringResource(state.message) to stringResource(R.string.update_check)
    }
    Group(stringResource(R.string.update_section)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onAction).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (supporting.isNotBlank()) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_auto), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.update_auto_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = autoUpdate, onCheckedChange = onAutoUpdate)
        }
        HorizontalDivider()
        CrashReportsRow()
    }
}

/** Crash reports kept on the device: shared only when the user taps them. */
@Composable
private fun CrashReportsRow() {
    val context = LocalContext.current
    val guard = context.launcherApp.crashGuard
    var reports by remember { mutableStateOf(guard.reports()) }
    val share = {
        reports.firstOrNull()?.let { latest ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, latest.readText())
            context.startActivity(Intent.createChooser(send, context.getString(R.string.crash_reports)))
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = reports.isNotEmpty(), onClick = { share() }).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.crash_reports), style = MaterialTheme.typography.titleMedium)
            Text(
                if (reports.isEmpty()) stringResource(R.string.crash_reports_none) else stringResource(R.string.crash_reports_summary, reports.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (guard.safeMode) {
                Text(stringResource(R.string.safe_mode_notice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        if (reports.isNotEmpty()) {
            TextButton(onClick = {
                guard.clear()
                reports = emptyList()
            }) { Text(stringResource(R.string.crash_reports_clear)) }
        }
    }
}

@Composable
private fun SourceRow(info: SourceInfo, status: SourceStatus?, onToggle: (Boolean) -> Unit, onGrant: (Requirement) -> Unit) {
    val enabled = status?.enabled ?: true
    val missing = status?.missing
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(info.title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(info.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (info.id != AppsSource.ID) {
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
        if (enabled) {
            Spacer(Modifier.size(6.dp))
            when {
                missing == Requirement.VeloInstall -> StatusText(stringResource(R.string.status_velo_missing))
                missing != null -> FilledTonalButton(onClick = { onGrant(missing) }) { Text(stringResource(requirementLabel(missing))) }
                status != null && status.loading && status.itemCount == 0 -> StatusText(stringResource(R.string.status_loading))
                else -> StatusText(stringResource(R.string.status_items, status?.itemCount ?: 0))
            }
        }
    }
}

/** Keeping messages from notifications for 90 days, with how many there are and a way to delete them. */
@Composable
private fun SavedMessagesRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val messages = LocalContext.current.launcherApp.messages
    val scope = rememberCoroutineScope()
    val version by messages.version.collectAsStateWithLifecycle()
    val count by produceState(0, version) { value = messages.savedCount() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.setting_save_messages), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.setting_save_messages_description, Messages.KEEP_DAYS),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (count > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.saved_messages, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { messages.clear() } }) { Text(stringResource(R.string.saved_messages_delete)) }
            }
        }
    }
}

/** Gemini Nano on or off, with whether the device has it. */
@Composable
private fun AiAnswersRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val nano = LocalContext.current.launcherApp.nano
    LaunchedEffect(enabled) { nano.prepare(download = enabled) }
    val availability by nano.availability.collectAsStateWithLifecycle()
    val status = when (availability) {
        NanoAnswers.Availability.Ready -> stringResource(R.string.ai_status_ready)
        NanoAnswers.Availability.Downloading -> stringResource(R.string.ai_status_downloading)
        NanoAnswers.Availability.Unsupported -> stringResource(R.string.ai_status_unsupported)
        NanoAnswers.Availability.Unknown -> null
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.setting_ai_answers), style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(stringResource(R.string.setting_ai_answers_description), status).joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun UsageRow(granted: Boolean, onGrant: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onGrant).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.source_usage), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.source_usage_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        StatusText(stringResource(if (granted) R.string.status_usage_granted else R.string.status_usage_missing))
    }
}

@Composable
private fun MetricRow(metric: Metric, summary: MetricSummary?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            stringResource(if (metric == Metric.Keyboard) R.string.metric_keyboard else R.string.metric_results, metric.budgetMs.toInt()),
            style = MaterialTheme.typography.titleMedium,
        )
        if (summary == null) {
            StatusText(stringResource(R.string.metric_none))
        } else {
            val withinBudget = summary.p95Ms <= metric.budgetMs
            Text(
                stringResource(R.string.metric_summary, summary.medianMs, summary.p95Ms, summary.maxMs, summary.count),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (withinBudget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Card(content)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

private fun requirementLabel(requirement: Requirement): Int = when (requirement) {
    Requirement.DefaultLauncher -> R.string.grant_default_launcher
    Requirement.Contacts, Requirement.Calendar, Requirement.Images, Requirement.VeloPermission, Requirement.Sms -> R.string.grant_permission
    Requirement.NotificationAccess -> R.string.grant_notification_access
    Requirement.AllFiles -> R.string.grant_all_files
    Requirement.NoteFolder -> R.string.note_folders_add
    Requirement.VeloInstall -> R.string.status_velo_missing
}

private fun isDefaultHome(context: Context): Boolean =
    context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}

/** "Download/Notes" for a SAF tree URI of that folder. */
private fun folderLabel(uri: String): String =
    Uri.decode(Uri.parse(uri).lastPathSegment.orEmpty()).substringAfter(':').ifBlank { uri }

/** Pictures whose text is known, out of all pictures worth recognising. */
private fun recognitionProgress(context: Context): Pair<Int, Int>? {
    if (!ImagesSource.hasAccess(context)) return null
    val known = context.launcherApp.indexStore.imageTexts()
    val images = MediaCatalog.images(context).filter { it.worthRecognising }
    return images.count { known[it.id]?.version == it.version } to images.size
}
