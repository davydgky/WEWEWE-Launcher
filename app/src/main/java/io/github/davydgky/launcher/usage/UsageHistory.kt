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
import kotlin.math.ln
import kotlin.math.pow
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
    private val priorState = MutableStateFlow<Map<String, Float>>(emptyMap())
    private var priorComputedAt = 0L
    private var importedUntil = 0L
    private var lastPackage: String? = null
    private var refreshJob: Job? = null

    private val accessState = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = accessState.asStateFlow()

    /** System launch events, keyed like the apps they belong to. Empty without usage access. */
    val systemEvents: StateFlow<List<UsageEvent>> = systemState.asStateFlow()

    /**
     * How much each app was used in the last 30 days (screen time, fading with time since the last use),
     * 0..1 by app key. Ranks apps in the search before the user ever opened anything through the launcher.
     */
    val appPrior: StateFlow<Map<String, Float>> = priorState.asStateFlow()

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
            val firstImport = importedUntil == 0L
            val fresh = withContext(Dispatchers.IO) { readLaunches(from, now, appsByPackage) }
            importedUntil = now
            var events = (systemState.value + fresh).filter { it.time >= now - WINDOW_MS }
            if (firstImport) {
                // Detailed events reach back only a few days; daily totals fill the older part of the window.
                val oldest = events.minOfOrNull { it.time } ?: now
                events = withContext(Dispatchers.IO) { dailyUse(now - WINDOW_MS, oldest, appsByPackage) } + events
            }
            systemState.value = events
            if (now - priorComputedAt > PRIOR_INTERVAL_MS) {
                priorComputedAt = now
                priorState.value = withContext(Dispatchers.IO) { usagePrior(now, appsByPackage) }
            }
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

    /** One event per day an app was used, at the time it was last used that day; more for long use. */
    private fun dailyUse(from: Long, until: Long, appsByPackage: Map<String, List<AppTarget>>): List<UsageEvent> {
        if (until <= from) return emptyList()
        val days = runCatching { usageStats.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, until) }.getOrNull().orEmpty()
        return days.flatMap { day ->
            val app = appsByPackage[day.packageName]?.first() ?: return@flatMap emptyList()
            val minutes = day.totalTimeVisible / 60_000L
            if (minutes < 1 || day.lastTimeVisible !in from until until) return@flatMap emptyList()
            List(1 + (minutes / 15).toInt().coerceAtMost(3)) { UsageEvent(app.key, day.lastTimeVisible) }
        }
    }

    private fun usagePrior(now: Long, appsByPackage: Map<String, List<AppTarget>>): Map<String, Float> {
        val stats = runCatching { usageStats.queryAndAggregateUsageStats(now - PRIOR_WINDOW_MS, now) }.getOrNull().orEmpty()
        val out = HashMap<String, Float>()
        for ((pkg, stat) in stats) {
            val app = appsByPackage[pkg]?.first() ?: continue
            val minutes = stat.totalTimeVisible / 60_000.0
            if (minutes < 1) continue
            val days = (now - stat.lastTimeVisible).coerceAtLeast(0) / 86_400_000.0
            val score = (ln(1 + minutes) / ln(1 + 600.0)).coerceAtMost(1.0) * 0.5.pow(days / 14.0)
            out[app.key] = score.toFloat()
        }
        return out
    }

    private companion object {
        const val WINDOW_MS = 28L * 24 * 60 * 60 * 1000
        const val PRIOR_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        const val PRIOR_INTERVAL_MS = 30L * 60 * 1000
    }
}
