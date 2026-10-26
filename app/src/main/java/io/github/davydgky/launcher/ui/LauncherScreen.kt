package io.github.davydgky.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.davydgky.launcher.apps.DefaultApps
import io.github.davydgky.launcher.data.LauncherPrefs
import io.github.davydgky.launcher.home.HomeCallbacks
import io.github.davydgky.launcher.home.HomeScreen
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.search.ResultActions
import io.github.davydgky.launcher.search.SearchViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Home screen with the search panel above it; a downward swipe in the upper half opens the search. */
@Composable
fun LauncherScreen(
    viewModel: SearchViewModel,
    actions: ResultActions,
    ime: ImeController,
    homePressed: Flow<Unit>,
    onSearchActive: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val app = context.launcherApp
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember(ime) { SearchState(scope, ime) { app.suggestions.refresh(context) } }
    val focusRequester = remember { FocusRequester() }
    var homeReset by remember { mutableIntStateOf(0) }

    val apps by app.apps.apps.collectAsStateWithLifecycle()
    val prefs by app.settings.prefs.collectAsStateWithLifecycle(initialValue = LauncherPrefs())
    val dock = remember(apps, prefs.dock) {
        val keys = prefs.dock
        if (keys != null) keys.mapNotNull { key -> apps.firstOrNull { it.key == key } }
        else DefaultApps.resolve(context, apps).take(DOCK_SIZE)
    }
    LaunchedEffect(apps) { app.icons.preloadApps(apps) }

    LaunchedEffect(homePressed) {
        homePressed.collect { if (state.isActive) state.close() else homeReset++ }
    }
    BackHandler(enabled = state.isActive) { state.close() }
    LaunchedEffect(state) { snapshotFlow { state.isActive }.collect(onSearchActive) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        app.search.onResume()
        // Ready before the next swipe, so the first frame of the search already shows suggestions.
        app.suggestions.refresh(context)
        // Focus without keyboard: the keyboard connection is ready before the next swipe.
        runCatching { focusRequester.requestFocus() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.reset() }

    val callbacks = remember(actions, dock) {
        HomeCallbacks(
            actions = actions,
            shortcutsOf = app.apps::shortcutsOf,
            isInDock = { target -> dock.any { it.key == target.key } },
            toggleDock = { target ->
                val keys = dock.map { it.key }
                val next = if (target.key in keys) keys - target.key else (keys + target.key).takeLast(DOCK_SIZE)
                scope.launch { app.settings.setDock(next) }
            },
            openSearch = state::open,
        )
    }
    val dark = isSystemInDarkTheme()
    val scrim = if (dark) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.55f)
    val blurPx = with(density) { 28.dp.toPx() }
    val distancePx = with(density) { SearchState.DRAG_DISTANCE_DP.dp.toPx() }

    Box(Modifier.fillMaxSize().searchGesture(state, distancePx)) {
        HomeScreen(
            apps = apps,
            dock = dock,
            callbacks = callbacks,
            resetSignal = homeReset,
            modifier = Modifier.graphicsLayer {
                // Read in the layer phase only: the gesture never recomposes the home screen.
                val p = state.progress.value
                val scale = 1f - 0.06f * p
                scaleX = scale
                scaleY = scale
                alpha = 1f - 0.4f * p
                renderEffect = if (p > 0.01f) BlurEffect(blurPx * p, blurPx * p, TileMode.Decal) else null
            },
        )
        Box(Modifier.fillMaxSize().drawBehind { drawRect(scrim.copy(alpha = scrim.alpha * state.progress.value)) })
        SearchPanel(state, viewModel, actions, focusRequester)
    }
}

private const val DOCK_SIZE = 4
