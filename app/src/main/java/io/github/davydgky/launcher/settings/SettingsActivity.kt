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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.data.LauncherPrefs
import io.github.davydgky.launcher.index.MediaCatalog
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.perf.Metric
import io.github.davydgky.launcher.perf.MetricSummary
import io.github.davydgky.launcher.search.SourceStatus
import io.github.davydgky.launcher.sources.AppsSource
import io.github.davydgky.launcher.sources.CalendarSource
import io.github.davydgky.launcher.sources.ContactsSource
import io.github.davydgky.launcher.sources.DocumentsSource
import io.github.davydgky.launcher.sources.ImagesSource
import io.github.davydgky.launcher.sources.NotesSource
import io.github.davydgky.launcher.sources.Requirement
import io.github.davydgky.launcher.sources.ShortcutsSource
import io.github.davydgky.launcher.sources.Velo
import io.github.davydgky.launcher.sources.VeloSource
import io.github.davydgky.launcher.ui.LauncherTheme
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

private class SourceInfo(val id: String, val title: Int, val description: Int)

private val SourceInfos = listOf(
    SourceInfo(AppsSource.ID, R.string.source_apps, R.string.source_apps_description),
    SourceInfo(ShortcutsSource.ID, R.string.source_shortcuts, R.string.source_shortcuts_description),
    SourceInfo(ContactsSource.ID, R.string.source_contacts, R.string.source_contacts_description),
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

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Permissions may have been granted in the system settings meanwhile.
        app.search.onResume()
        app.usage.checkAccess()
        isDefaultLauncher = isDefaultHome(context)
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
            Group(stringResource(R.string.settings_sources)) {
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
    Requirement.Contacts, Requirement.Calendar, Requirement.Images, Requirement.VeloPermission -> R.string.grant_permission
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
