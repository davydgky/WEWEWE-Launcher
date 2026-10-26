package io.github.davydgky.launcher.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import io.github.davydgky.launcher.apps.AppsRepository
import io.github.davydgky.launcher.core.UsageEvent
import io.github.davydgky.launcher.data.LaunchLog
import io.github.davydgky.launcher.search.AppTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App launches for "recent and frequent": with usage access from the system's usage statistics (every
 * launch, wherever it came from), otherwise from the launcher's own log.
 */
class UsageHistory(
    private val context: Context,
    private val apps: AppsRepository,
    private val launchLog: LaunchLog,
    private val scope: CoroutineScope,
) {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)

    private val systemState = MutableStateFlow<List<UsageEvent>>(emptyList())
    private var importedUntil = 0L
    private var lastPackage: String? = null
    private var refreshJob: Job? = null

    private val accessState = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = accessState.asStateFlow()

    /** System launch events, keyed like the apps they belong to. Empty without usage access. */
    val systemEvents: StateFlow<List<UsageEvent>> = systemState.asStateFlow()

    fun checkAccess(): Boolean {
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        val granted = mode == AppOpsManager.MODE_ALLOWED
        accessState.value = granted
        return granted
    }

    /** Imports launches since the last call; cheap enough to run whenever the home screen shows. */
    fun refresh() {
        if (!checkAccess()) {
            systemState.value = emptyList()
            importedUntil = 0L
            return
        }
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            val appsByPackage = apps.apps.value.filter { !it.isWorkProfile }.groupBy { it.packageName }
            if (appsByPackage.isEmpty()) return@launch
            val now = System.currentTimeMillis()
            val from = maxOf(importedUntil, now - WINDOW_MS)
            val fresh = withContext(Dispatchers.IO) { readLaunches(from, now, appsByPackage) }
            importedUntil = now
            systemState.value = (systemState.value + fresh).filter { it.time >= now - WINDOW_MS }
        }
    }

    /**
     * Combined history for predictions. System events already contain launches from the launcher, so
     * the own log only adds what the system does not know about (shortcuts, contacts, files).
     */
    fun combinedEvents(): List<UsageEvent> {
        val own = launchLog.events.value
        val system = systemState.value
        if (system.isEmpty()) return own
        return system + own.filter { !it.key.startsWith("app:") }
    }

    private fun readLaunches(
        from: Long,
        to: Long,
        appsByPackage: Map<String, List<AppTarget>>,
    ): List<UsageEvent> {
        val events = runCatching { usageStats.queryEvents(from, to) }.getOrNull() ?: return emptyList()
        val out = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = event.packageName
            // Moving between activities of one app is not a new launch.
            if (pkg == lastPackage) continue
            lastPackage = pkg
            val candidates = appsByPackage[pkg] ?: continue
            val app = candidates.firstOrNull { it.component.className == event.className } ?: candidates.first()
            out += UsageEvent(app.key, event.timeStamp)
        }
        return out
    }

    private companion object {
        const val WINDOW_MS = 28L * 24 * 60 * 60 * 1000
    }
}
