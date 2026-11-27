package io.github.davydgky.launcher.permissions

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.util.Log
import io.github.davydgky.launcher.BuildConfig
import io.github.davydgky.launcher.gestures.LockService
import io.github.davydgky.launcher.notifications.NotificationListener
import io.github.davydgky.launcher.sources.Velo

/** Everything WEWEWE may be granted, and what each one is for. */
enum class Permission(
    /** Runtime permissions asked for in a dialog; empty for those granted on a page of the system settings. */
    val runtime: List<String> = emptyList(),
    /** Only the launcher needs it. */
    val launcherOnly: Boolean = false,
) {
    /** Accessibility service: the Dynamic Island, double tap to lock, screen text. */
    Accessibility,

    /** Notification access: music, timers and notifications in the island, dots, messages in the search. */
    NotificationAccess,

    /** "Do not disturb" access: the island's "Quiet" shortcut. */
    DoNotDisturb,

    /** Home screen role. */
    DefaultLauncher(launcherOnly = true),

    /** Digital assistant: the search with a swipe from a bottom corner. */
    Assistant,

    Calendar(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
    Contacts(listOf(Manifest.permission.READ_CONTACTS)),
    Sms(listOf(Manifest.permission.READ_SMS)),
    Photos(listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)),

    /** Documents of other apps in the search. */
    AllFiles,

    /** Better suggestions from how often apps are used. */
    UsageAccess,

    /** "Open as bubble" is a notification. */
    PostNotifications(listOf(Manifest.permission.POST_NOTIFICATIONS)),

    /** Updates install themselves. */
    InstallUpdates,

    /** Velo's history and favourites in the search; only when Velo is installed. */
    Velo(listOf(io.github.davydgky.launcher.sources.Velo.PERMISSION)),
}

/**
 * Whether each [Permission] is granted, and the exact system page for each one. Every page has a
 * fallback (the general list, then the app's info), so a tap always leads somewhere, also on systems
 * that do not know the detailed page.
 */
object SystemPages {

    private const val TAG = "SystemPages"

    /** The permissions that apply to this app on this device, in the order they are shown. */
    fun applicable(context: Context): List<Permission> = Permission.entries.filter { permission ->
        when {
            permission.launcherOnly && !BuildConfig.IS_LAUNCHER -> false
            permission == Permission.Velo -> isInstalled(context, Velo.PACKAGE)
            permission == Permission.Sms -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
            else -> true
        }
    }

    /** Whether [permission] is granted; a check the system refuses counts as "not granted", never as a crash. */
    fun isGranted(context: Context, permission: Permission): Boolean =
        runCatching { check(context, permission) }.onFailure { Log.w(TAG, "Cannot check $permission", it) }.getOrDefault(false)

    private fun check(context: Context, permission: Permission): Boolean = when (permission) {
        Permission.Accessibility -> LockService.isEnabled(context)
        Permission.NotificationAccess ->
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, NotificationListener::class.java))
        Permission.DoNotDisturb -> context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        Permission.DefaultLauncher -> context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
        Permission.Assistant -> context.getSystemService(RoleManager::class.java).let { it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && it.isRoleHeld(RoleManager.ROLE_ASSISTANT) }
        Permission.AllFiles -> Environment.isExternalStorageManager()
        Permission.UsageAccess -> {
            val ops = context.getSystemService(AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }
        Permission.InstallUpdates -> context.packageManager.canRequestPackageInstalls()
        // Photos count as granted with full access; "selected photos only" is a choice the user made.
        Permission.Photos -> granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        Permission.Calendar -> granted(context, Manifest.permission.READ_CALENDAR)
        else -> permission.runtime.all { granted(context, it) }
    }

    /**
     * True if Android may grey out the accessibility and notification-access switches until "Allow
     * restricted settings" is chosen in the app info: apps not installed from an app store.
     */
    fun mayBeRestricted(context: Context): Boolean {
        val installer = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
        return installer == null || installer == context.packageName || installer in SideloadInstallers
    }

    /** Opens the system page where [permission] is granted. Not for runtime permissions (see [Permission.runtime]). */
    fun open(context: Context, permission: Permission) {
        val pkg = Uri.fromParts("package", context.packageName, null)
        val intents = when (permission) {
            Permission.Accessibility -> accessibilityIntents(context)
            Permission.NotificationAccess -> listOf(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, NotificationListener::class.java).flattenToString()),
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
            Permission.DoNotDisturb -> listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Permission.DefaultLauncher -> listOf(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            // Android does not let apps ask for the assistant role; this page is where it is chosen.
            Permission.Assistant -> listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            Permission.AllFiles -> listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            Permission.UsageAccess -> listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Permission.InstallUpdates -> listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg))
            Permission.PostNotifications -> listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            // A runtime permission that Android no longer asks for: the app's info, where "Permissions" is.
            else -> emptyList()
        }
        openFirst(context, intents + appInfo(context))
    }

    /**
     * The list of accessibility services, scrolled to WEWEWE's and with it highlighted. Android keeps the
     * service's own page for system apps; the highlight arguments are what the Settings app itself uses
     * for search results.
     */
    fun accessibilityIntents(context: Context): List<Intent> {
        val component = ComponentName(context, LockService::class.java).flattenToString()
        val highlight = Bundle().apply { putString(FRAGMENT_ARGS_KEY, component) }
        return listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(FRAGMENT_ARGS_KEY, component)
                .putExtra(SHOW_FRAGMENT_ARGS, highlight),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
    }

    private const val FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
    private const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    /** The app's info page: permissions, and "Allow restricted settings" in its menu. */
    fun appInfo(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    fun openAppInfo(context: Context) = openFirst(context, listOf(appInfo(context)))

    /** Starts the first of [intents] the system can open. */
    fun openFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "No page for ${intent.action}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to open ${intent.action}", e)
            }
        }
        return false
    }

    private fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun isInstalled(context: Context, packageName: String) = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /** Installers that count as sideloading for restricted settings (files app, browsers, adb). */
    private val SideloadInstallers = setOf(
        "com.google.android.packageinstaller", "com.android.packageinstaller", "com.android.shell",
        "com.google.android.documentsui", "com.android.documentsui", "com.android.chrome",
        "com.google.android.apps.nbu.files", "io.github.davydgky.browser",
    )
}
