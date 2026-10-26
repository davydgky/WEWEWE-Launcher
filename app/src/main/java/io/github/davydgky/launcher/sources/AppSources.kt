package io.github.davydgky.launcher.sources

import android.content.Context
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.apps.AppsRepository
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Installed apps of all visible profiles. */
class AppsSource(
    private val context: Context,
    private val apps: AppsRepository,
    private val scope: CoroutineScope,
) : SearchSource(ID) {

    override fun missing(context: Context): Requirement? = null

    override suspend fun load(): List<SearchItem> {
        val work = context.getString(R.string.work_profile)
        return apps.apps.value.map { app ->
            SearchItem(
                key = app.key,
                kind = SourceKind.App,
                title = app.label,
                subtitle = if (app.isWorkProfile) work else "",
                payload = app,
            )
        }
    }

    override fun observe(onChange: () -> Unit): AutoCloseable {
        val job = scope.launch { apps.apps.drop(1).collect { onChange() } }
        return AutoCloseable { job.cancel() }
    }

    companion object {
        const val ID = "apps"
    }
}

/** Static, dynamic, pinned and cached shortcuts of all apps; needs the default launcher role. */
class ShortcutsSource(
    private val apps: AppsRepository,
    private val scope: CoroutineScope,
) : SearchSource(ID) {

    override fun missing(context: Context): Requirement? =
        if (apps.hasShortcutAccess.value) null else Requirement.DefaultLauncher

    override suspend fun load(): List<SearchItem> = apps.shortcuts.value.map { shortcut ->
        val info = shortcut.info
        val short = info.shortLabel?.toString().orEmpty()
        val long = info.longLabel?.toString().orEmpty()
        SearchItem(
            key = shortcut.key,
            kind = SourceKind.Shortcut,
            title = short.ifBlank { long },
            subtitle = shortcut.appLabel,
            keywords = if (long.isNotBlank() && long != short) listOf(long) else emptyList(),
            // Apps rank their most important shortcuts first.
            boost = if (info.isDeclaredInManifest || info.rank == 0) 0.2f else 0f,
            payload = shortcut,
        )
    }

    override fun observe(onChange: () -> Unit): AutoCloseable {
        val job = scope.launch { apps.shortcuts.drop(1).collect { onChange() } }
        return AutoCloseable { job.cancel() }
    }

    companion object {
        const val ID = "shortcuts"
    }
}
