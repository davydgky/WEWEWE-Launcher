package io.github.davydgky.launcher

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.davydgky.launcher.ai.AppKnowledge
import io.github.davydgky.launcher.ai.NanoAnswers
import io.github.davydgky.launcher.apps.AppsRepository
import io.github.davydgky.launcher.data.LaunchLog
import io.github.davydgky.launcher.data.LauncherSettings
import io.github.davydgky.launcher.home.WorkspaceRepository
import io.github.davydgky.launcher.index.IndexStore
import io.github.davydgky.launcher.notifications.Notifications
import io.github.davydgky.launcher.perf.PerfStats
import io.github.davydgky.launcher.search.IconCache
import io.github.davydgky.launcher.search.SearchRepository
import io.github.davydgky.launcher.search.Suggestions
import io.github.davydgky.launcher.search.Torch
import io.github.davydgky.launcher.update.AppUpdater
import io.github.davydgky.launcher.usage.UsageHistory
import io.github.davydgky.launcher.widgets.WidgetHost
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Context.launcherDataStore by preferencesDataStore(name = "launcher")

/** Holds the app-wide singletons (manual dependency injection, as in Velo). */
class LauncherApplication : Application() {

    /** Lives as long as the process. The home screen process is kept alive by the system. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings: LauncherSettings by lazy { LauncherSettings(launcherDataStore) }
    val launchLog: LaunchLog by lazy { LaunchLog(File(filesDir, "launches.tsv"), appScope) }
    val apps: AppsRepository by lazy { AppsRepository(this, appScope) }
    val usage: UsageHistory by lazy { UsageHistory(this, apps, launchLog, appScope) }
    val indexStore: IndexStore by lazy { IndexStore(this) }
    val icons: IconCache by lazy { IconCache(this) }
    val search: SearchRepository by lazy { SearchRepository(this, appScope) }
    val suggestions: Suggestions by lazy { Suggestions(apps, usage, launchLog, appScope) }
    val widgets: WidgetHost by lazy { WidgetHost(this) }
    val workspace: WorkspaceRepository by lazy { WorkspaceRepository(this, apps, settings, usage, widgets, appScope) }
    val updater: AppUpdater by lazy { AppUpdater(this, settings, appScope) }
    val torch: Torch by lazy { Torch(this) }
    val notifications: Notifications by lazy { Notifications(this) }
    val nano: NanoAnswers by lazy { NanoAnswers(appScope) }
    val appKnowledge: AppKnowledge by lazy { AppKnowledge(this, nano, apps, appScope) }
    val perf = PerfStats()

    override fun onCreate() {
        super.onCreate()
        // Start indexing right away, before the first frame needs it.
        appKnowledge.start()
        search.start()
        workspace.start()
    }
}

val Context.launcherApp: LauncherApplication get() = applicationContext as LauncherApplication
