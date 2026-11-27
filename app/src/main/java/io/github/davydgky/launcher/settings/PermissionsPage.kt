package io.github.davydgky.launcher.settings

import android.app.Activity
import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.permissions.Permission
import io.github.davydgky.launcher.permissions.SystemPages

/** What each permission is called and what it is for. */
private val Permission.title: Int
    get() = when (this) {
        Permission.Accessibility -> R.string.permission_accessibility
        Permission.NotificationAccess -> R.string.permission_notification_access
        Permission.DoNotDisturb -> R.string.permission_dnd
        Permission.DefaultLauncher -> R.string.permission_default_launcher
        Permission.Assistant -> R.string.permission_assistant
        Permission.Calendar -> R.string.permission_calendar
        Permission.Contacts -> R.string.permission_contacts
        Permission.Sms -> R.string.permission_sms
        Permission.Photos -> R.string.permission_photos
        Permission.AllFiles -> R.string.permission_all_files
        Permission.UsageAccess -> R.string.permission_usage
        Permission.PostNotifications -> R.string.permission_post_notifications
        Permission.InstallUpdates -> R.string.permission_install
        Permission.Velo -> R.string.permission_velo
    }

private val Permission.purpose: Int
    get() = when (this) {
        Permission.Accessibility -> R.string.permission_accessibility_purpose
        Permission.NotificationAccess -> R.string.permission_notification_access_purpose
        Permission.DoNotDisturb -> R.string.permission_dnd_purpose
        Permission.DefaultLauncher -> R.string.permission_default_launcher_purpose
        Permission.Assistant -> R.string.permission_assistant_purpose
        Permission.Calendar -> R.string.permission_calendar_purpose
        Permission.Contacts -> R.string.permission_contacts_purpose
        Permission.Sms -> R.string.permission_sms_purpose
        Permission.Photos -> R.string.permission_photos_purpose
        Permission.AllFiles -> R.string.permission_all_files_purpose
        Permission.UsageAccess -> R.string.permission_usage_purpose
        Permission.PostNotifications -> R.string.permission_post_notifications_purpose
        Permission.InstallUpdates -> R.string.permission_install_purpose
        Permission.Velo -> R.string.permission_velo_purpose
    }

/** The most important first: without these two, the island shows nothing. */
private val Essential = setOf(Permission.Accessibility, Permission.NotificationAccess)

/** How many applicable permissions are still missing; read again with every [resumeCount]. */
@Composable
internal fun missingPermissions(resumeCount: Int): Int {
    val context = LocalContext.current
    return remember(resumeCount) { SystemPages.applicable(context).count { !SystemPages.isGranted(context, it) } }
}

/** On the overview: how many are missing, with the way to the page that grants them. */
@Composable
internal fun PermissionsSummaryCard(missing: Int, onOpen: () -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pluralStringResource(R.plurals.permissions_missing, missing, missing), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.permissions_missing_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onOpen) { Text(stringResource(R.string.permissions_show)) }
        }
    }
}

/**
 * Every permission with its state and a button that leads straight to where it is granted: a dialog
 * for runtime permissions (the app info, once Android no longer asks), the exact system page otherwise.
 */
internal fun LazyListScope.permissionsPage(resumeCount: Int) {
    item {
        val context = LocalContext.current
        val activity = context as Activity
        val app = context.launcherApp
        // Counts up after every answer, so the states are read again without leaving the page.
        var answered by remember { mutableIntStateOf(0) }
        val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            // Denied before and "don't ask again": Android shows no dialog, the app info is where it is granted.
            if (denied.isNotEmpty() && denied.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) {
                SystemPages.openAppInfo(context)
            }
            app.search.onResume()
            answered++
        }
        val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { answered++ }
        val states = remember(resumeCount, answered) {
            SystemPages.applicable(context).map { it to SystemPages.isGranted(context, it) }
        }
        val restricted = remember(resumeCount, answered) {
            SystemPages.mayBeRestricted(context) && states.any { (permission, granted) -> permission in Essential && !granted }
        }

        fun grant(permission: Permission, granted: Boolean) {
            when {
                // Granted runtime permissions are taken back in the app info; asking again would show nothing.
                granted && permission.runtime.isNotEmpty() -> SystemPages.openAppInfo(context)
                permission.runtime.isNotEmpty() -> runtime.launch(permission.runtime.toTypedArray())
                permission == Permission.DefaultLauncher -> {
                    val roles = context.getSystemService(RoleManager::class.java)
                    runCatching { role.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
                        .onFailure { SystemPages.open(context, permission) }
                }
                else -> SystemPages.open(context, permission)
            }
        }

        Column {
            Text(
                stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
            )
            if (restricted) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.permissions_restricted_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.permissions_restricted_description), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(12.dp))
                        FilledTonalButton(onClick = { SystemPages.openAppInfo(context) }) { Text(stringResource(R.string.permissions_restricted_action)) }
                    }
                }
                Spacer(Modifier.size(20.dp))
            }
            val (essential, others) = states.partition { it.first in Essential }
            Group(stringResource(R.string.permissions_group_island)) {
                essential.forEachIndexed { index, (permission, granted) ->
                    if (index > 0) HorizontalDivider()
                    PermissionRow(permission, granted) { grant(permission, granted) }
                }
            }
            Spacer(Modifier.size(20.dp))
            Group(stringResource(R.string.permissions_group_more)) {
                others.forEachIndexed { index, (permission, granted) ->
                    if (index > 0) HorizontalDivider()
                    PermissionRow(permission, granted) { grant(permission, granted) }
                }
            }
            Spacer(Modifier.size(12.dp))
            OutlinedButton(onClick = { SystemPages.openAppInfo(context) }, modifier = Modifier.padding(start = 4.dp)) {
                Text(stringResource(R.string.permissions_app_info))
            }
        }
    }
}

@Composable
private fun PermissionRow(permission: Permission, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(permission.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(permission.purpose), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        // Granted ones can be opened too: to look, or to take them back.
        if (granted) {
            OutlinedButton(onClick = onGrant) { Text(stringResource(R.string.permission_granted)) }
        } else {
            FilledTonalButton(onClick = onGrant) { Text(stringResource(R.string.permission_grant)) }
        }
    }
}
