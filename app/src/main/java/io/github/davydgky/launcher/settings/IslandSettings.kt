package io.github.davydgky.launcher.settings

import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.davydgky.launcher.BuildConfig
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.data.LauncherPrefs
import io.github.davydgky.launcher.gestures.LockService
import io.github.davydgky.launcher.island.SearchTileService
import io.github.davydgky.launcher.island.SearchWidgetProvider
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.permissions.Permission
import io.github.davydgky.launcher.permissions.SystemPages
import kotlinx.coroutines.launch

/** The Dynamic Island's page: on or off, what it shows, and the ways into the search without WEWEWE as home screen. */
internal fun LazyListScope.islandSettings(prefs: LauncherPrefs, notificationAccess: Boolean, resumeCount: Int, openPermissions: () -> Unit) {
    item {
        // All that is still missing, in one place, each with the way to where it is granted.
        val missing = missingPermissions(resumeCount)
        if (missing > 0) PermissionsSummaryCard(missing, openPermissions)
    }
    item {
        val context = LocalContext.current
        val app = context.launcherApp
        val scope = rememberCoroutineScope()
        // Read again on every return to the settings: the service is switched on in the system's pages.
        val serviceOn = remember(resumeCount) { LockService.isEnabled(context) }
        val islandApp = remember(resumeCount) {
            BuildConfig.IS_LAUNCHER && runCatching { context.packageManager.getPackageInfo(BuildConfig.ISLAND_PACKAGE, 0) }.isSuccess
        }
        if (islandApp) {
            Card {
                Text(
                    stringResource(R.string.island_in_other_app),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.size(20.dp))
        }
        Group(stringResource(R.string.settings_group_island)) {
            SwitchRow(R.string.setting_island, R.string.setting_island_description, prefs.islandEnabled) { enabled ->
                scope.launch { app.settings.setIsland(enabled = enabled) }
                if (enabled && !serviceOn) SystemPages.open(context, Permission.Accessibility)
            }
            if (prefs.islandEnabled && !serviceOn) {
                ActionRow(
                    title = stringResource(R.string.island_service_needed),
                    description = stringResource(R.string.island_service_restricted),
                    action = stringResource(R.string.island_service_action),
                ) { SystemPages.open(context, Permission.Accessibility) }
            }
            HorizontalDivider()
            ActionRow(
                title = stringResource(R.string.island_notification_access),
                description = stringResource(R.string.island_notification_access_description),
                action = stringResource(if (notificationAccess) R.string.notification_dots_on else R.string.notification_dots_allow),
                tonal = !notificationAccess,
            ) { SystemPages.open(context, Permission.NotificationAccess) }
            HorizontalDivider()
            SwitchRow(R.string.setting_island_notifications, R.string.setting_island_notifications_description, prefs.islandNotifications, enabled = prefs.islandEnabled) {
                scope.launch { app.settings.setIsland(notifications = it) }
            }
            HorizontalDivider()
            SwitchRow(R.string.setting_island_system, R.string.setting_island_system_description, prefs.islandSystemEvents, enabled = prefs.islandEnabled) {
                scope.launch { app.settings.setIsland(systemEvents = it) }
            }
            HorizontalDivider()
            IslandWidthRow(prefs.islandWidth, enabled = prefs.islandEnabled) { scope.launch { app.settings.setIsland(width = it) } }
            HorizontalDivider()
            SwitchRow(R.string.setting_island_idle, R.string.setting_island_idle_description, prefs.islandIdleVisible, enabled = prefs.islandEnabled) {
                scope.launch { app.settings.setIsland(idleVisible = it) }
            }
        }
    }
    item {
        Group(stringResource(R.string.settings_group_island_how)) {
            Text(
                stringResource(R.string.island_how),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    item {
        val context = LocalContext.current
        val assistantHeld = remember(resumeCount) {
            context.getSystemService(RoleManager::class.java).let { it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && it.isRoleHeld(RoleManager.ROLE_ASSISTANT) }
        }
        Group(stringResource(R.string.settings_group_without_launcher)) {
            Text(
                stringResource(R.string.without_launcher_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            ActionRow(
                title = stringResource(R.string.assistant_title),
                description = stringResource(R.string.assistant_description),
                action = stringResource(if (assistantHeld) R.string.assistant_set else R.string.assistant_action),
                tonal = !assistantHeld,
            ) { SystemPages.open(context, Permission.Assistant) }
            HorizontalDivider()
            ActionRow(
                title = stringResource(R.string.tile_title),
                description = stringResource(R.string.tile_description),
                action = stringResource(R.string.tile_action),
            ) { addTile(context) }
            HorizontalDivider()
            ActionRow(
                title = stringResource(R.string.widget_title),
                description = stringResource(R.string.widget_description),
                action = stringResource(R.string.widget_action),
            ) {
                if (!SearchWidgetProvider.requestPin(context)) Toast.makeText(context, R.string.widget_not_supported, Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun addTile(context: Context) {
    val manager = context.getSystemService(StatusBarManager::class.java)
    manager.requestAddTileService(
        ComponentName(context, SearchTileService::class.java),
        context.getString(R.string.tile_search_label),
        Icon.createWithResource(context, R.drawable.ic_search),
        context.mainExecutor,
    ) { }
}

/** How far the compact island reaches beside the camera; narrow leaves the status bar's icons free. */
@Composable
private fun IslandWidthRow(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.setting_island_width), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.setting_island_width_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            listOf(R.string.island_width_narrow, R.string.island_width_normal, R.string.island_width_wide).forEachIndexed { index, label ->
                androidx.compose.material3.FilterChip(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    enabled = enabled,
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, description: String, action: String, tonal: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(2.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        if (tonal) FilledTonalButton(onClick = onClick) { Text(action) } else OutlinedButton(onClick = onClick) { Text(action) }
    }
}
