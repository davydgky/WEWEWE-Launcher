package io.github.davydgky.launcher.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import io.github.davydgky.launcher.bubbles.Bubbles
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.search.ShortcutTarget
import java.text.Collator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Installed apps and their shortcuts for all visible profiles, kept current through
 * [LauncherApps.Callback]. Shortcuts are only readable while this app is the default launcher.
 */
class AppsRepository(private val context: Context, private val scope: CoroutineScope) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val appState = MutableStateFlow<List<AppTarget>>(emptyList())
    val apps: StateFlow<List<AppTarget>> = appState.asStateFlow()

    private val shortcutState = MutableStateFlow<List<ShortcutTarget>>(emptyList())
    val shortcuts: StateFlow<List<ShortcutTarget>> = shortcutState.asStateFlow()

    private val changedPackages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Packages that were installed, updated or removed. */
    val packageChanges: SharedFlow<String> = changedPackages.asSharedFlow()

    private val unavailableState = MutableStateFlow<Set<Long>>(emptySet())
    /**
     * Serial numbers of profiles that are paused or locked right now. Their apps are not gone: the home
     * screen keeps their places until the profile is back.
     */
    val unavailableProfiles: StateFlow<Set<Long>> = unavailableState.asStateFlow()

    private val shortcutAccessState = MutableStateFlow(false)
    val hasShortcutAccess: StateFlow<Boolean> = shortcutAccessState.asStateFlow()

    private var started = false
    private var appsJob: Job? = null
    private var shortcutsJob: Job? = null

    fun start() {
        if (started) return
        started = true
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        }
        context.registerReceiver(profileReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        reloadApps(0)
    }

    /** Re-reads shortcuts if the default launcher role changed while we were away. */
    fun checkShortcutAccess() {
        val access = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)
        if (access != shortcutAccessState.value) {
            shortcutAccessState.value = access
            reloadShortcuts(0)
        }
    }

    fun appForKey(key: String): AppTarget? = appState.value.firstOrNull { it.key == key }

    /** Tells the system which shortcuts of [packageName] the home screen shows (the complete list). */
    fun pinShortcuts(userSerial: Long, packageName: String, ids: List<String>) {
        val user = userManager.getUserForSerialNumber(userSerial) ?: return
        runCatching { launcherApps.pinShortcuts(packageName, ids, user) }
            .onFailure { Log.w(TAG, "Cannot pin shortcuts of $packageName", it) }
        reloadShortcuts(SHORTCUT_DEBOUNCE_MS)
    }

    /** The app (main activity) of [packageName] for the user with [userSerial]. */
    fun appOf(userSerial: Long, packageName: String): AppTarget? =
        appState.value.firstOrNull { it.packageName == packageName && it.key.startsWith("app:$userSerial/") }

    /** Shortcuts of one app in the order the app ranks them, as shown in its long-press menu. */
    fun shortcutsOf(app: AppTarget): List<ShortcutTarget> = shortcutState.value
        .filter { it.info.`package` == app.packageName && it.info.userHandle == app.user && !it.info.isPinned }
        .sortedWith(compareBy({ if (it.info.isDeclaredInManifest) 0 else 1 }, { it.info.rank }))

    private fun reloadApps(delayMs: Long) {
        appsJob?.cancel()
        appsJob = scope.launch {
            delay(delayMs)
            val (list, unavailable) = withContext(Dispatchers.IO) { queryApps() }
            // Both before the list: whoever reacts to the list must already know what is only paused.
            unavailableState.value = unavailable
            appState.value = list
            reloadShortcuts(0)
        }
    }

    private fun reloadShortcuts(delayMs: Long) {
        shortcutsJob?.cancel()
        shortcutsJob = scope.launch {
            delay(delayMs)
            val access = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)
            shortcutAccessState.value = access
            val labels = appState.value.associate { (it.packageName to it.user) to it.label }
            shortcutState.value = if (access) withContext(Dispatchers.IO) { queryShortcuts(labels) } else emptyList()
        }
    }

    private fun isAvailable(user: UserHandle) = user == Process.myUserHandle() ||
        (!userManager.isQuietModeEnabled(user) && userManager.isUserUnlocked(user))

    private fun isPrivate(user: UserHandle) = runCatching {
        launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
    }.getOrDefault(false)

    /** Apps of all profiles, and the serials of the profiles that are paused or locked. */
    private fun queryApps(): Pair<List<AppTarget>, Set<Long>> {
        val me = Process.myUserHandle()
        val collator = Collator.getInstance()
        val unavailable = HashSet<Long>()
        val list = launcherApps.profiles.flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            val available = isAvailable(user)
            val private = user != me && isPrivate(user)
            if (!available) unavailable += serial
            // A locked private space shows nothing at all; a paused work profile shows its apps greyed.
            if (private && !available) return@flatMap emptyList()
            val activities = runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
            activities.mapNotNull { info ->
                if (info.componentName.packageName == context.packageName) return@mapNotNull null
                AppTarget(
                    key = appKey(serial, info.componentName.flattenToShortString()),
                    label = info.label.toString().trim(),
                    component = info.componentName,
                    user = user,
                    isWorkProfile = user != me,
                    info = info,
                    paused = !available,
                    isPrivate = private,
                )
            }
        }.sortedWith { a, b -> collator.compare(a.label, b.label) }
        return list to unavailable
    }

    /** Asks the system to turn the paused profile of [app] back on; false if it cannot. */
    fun unpause(app: AppTarget): Boolean =
        runCatching { userManager.requestQuietModeEnabled(false, app.user) }
            .onFailure { Log.w(TAG, "Cannot turn on ${app.user}", it) }
            .getOrDefault(false)

    private fun queryShortcuts(labels: Map<Pair<String, UserHandle>, String>): List<ShortcutTarget> {
        val query = LauncherApps.ShortcutQuery().setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED,
        )
        return launcherApps.profiles.filter(::isAvailable).flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            val list = try {
                launcherApps.getShortcuts(query, user).orEmpty()
            } catch (e: SecurityException) {
                Log.w(TAG, "No shortcut access", e)
                emptyList()
            } catch (e: IllegalStateException) {
                // The profile is locked.
                emptyList()
            }
            // The launcher's own bubble conversations are not shortcuts anyone looks for.
            list.filter { it.isEnabled && !(it.`package` == context.packageName && Bubbles.isBubbleShortcut(it.id)) }.map { info ->
                ShortcutTarget(
                    key = "shortcut:$serial/${info.`package`}/${info.id}",
                    info = info,
                    appLabel = labels[info.`package` to user].orEmpty(),
                )
            }
        }.distinctBy { it.key }
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::changed)
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::changed)
        override fun onPackagesSuspended(packageNames: Array<String>, user: UserHandle) = packageNames.forEach(::changed)
        override fun onPackagesUnsuspended(packageNames: Array<String>, user: UserHandle) = packageNames.forEach(::changed)

        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
            reloadShortcuts(SHORTCUT_DEBOUNCE_MS)
        }
    }

    private fun changed(packageName: String) {
        changedPackages.tryEmit(packageName)
        reloadApps(APP_DEBOUNCE_MS)
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps(APP_DEBOUNCE_MS)
    }

    companion object {
        private const val TAG = "AppsRepository"
        private const val APP_DEBOUNCE_MS = 300L
        private const val SHORTCUT_DEBOUNCE_MS = 500L

        fun appKey(userSerial: Long, component: String) = "app:$userSerial/$component"
    }
}
