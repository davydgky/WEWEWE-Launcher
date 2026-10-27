package io.github.davydgky.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.davydgky.launcher.home.AppLibrary
import io.github.davydgky.launcher.home.CategoryOverlay
import io.github.davydgky.launcher.home.DragOverlay
import io.github.davydgky.launcher.home.FolderOverlay
import io.github.davydgky.launcher.home.HomeController
import io.github.davydgky.launcher.home.HomeScreen
import io.github.davydgky.launcher.home.LibraryGroup
import io.github.davydgky.launcher.home.WidgetPicker
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.search.ResultActions
import io.github.davydgky.launcher.search.SearchViewModel
import io.github.davydgky.launcher.ui.glass.LocalGlassColors
import io.github.davydgky.launcher.ui.glass.frostedBackdrop
import io.github.davydgky.launcher.ui.glass.glassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * Home screen with the search above it. A downward swipe anywhere below the status bar opens the
 * search; the home screen is blurred behind Liquid Glass panels, as in Velo.
 */
@Composable
fun LauncherScreen(
    viewModel: SearchViewModel,
    actions: ResultActions,
    ime: ImeController,
    homePressed: Flow<Unit>,
    addWidget: (AppWidgetProviderInfo, Int, Int, Int) -> Unit,
    onSearchActive: (Boolean) -> Unit,
    onSearchProgress: (Float) -> Unit,
) {
    val context = LocalContext.current
    val app = context.launcherApp
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember(ime) { SearchState(scope, ime) { app.suggestions.refresh(context) } }
    val focusRequester = remember { FocusRequester() }
    var homeReset by remember { mutableIntStateOf(0) }
    val controller = remember(actions) {
        HomeController(
            actions = actions,
            repository = app.workspace,
            shortcutsOf = app.apps::shortcutsOf,
            openSearch = state::open,
            addWidget = addWidget,
            scope = scope,
        )
    }

    val appList by app.apps.apps.collectAsStateWithLifecycle()
    val apps = remember(appList) { appList.associateBy { it.key } }
    val workspace by app.workspace.workspace.collectAsStateWithLifecycle()
    val library by produceState(emptyList<LibraryGroup>(), app) {
        combine(app.apps.apps, app.suggestions.current, app.usage.appPrior) { list, suggestions, prior ->
            Triple(list, suggestions.apps, prior)
        }.collect { (list, suggested, prior) ->
            value = withContext(Dispatchers.Default) { AppLibrary.groups(list, suggested, prior) }
        }
    }
    LaunchedEffect(appList) { app.icons.preloadApps(appList) }
    LaunchedEffect(Unit) { app.workspace.cleanUpWidgets() }

    LaunchedEffect(homePressed) {
        homePressed.collect {
            when {
                state.isActive -> state.close()
                controller.openFolderId != null -> controller.openFolderId = null
                controller.openCategory != null -> controller.openCategory = null
                controller.widgetPickerOpen -> controller.widgetPickerOpen = false
                else -> homeReset++
            }
        }
    }
    BackHandler(enabled = state.isActive) { state.close() }
    LaunchedEffect(state) { snapshotFlow { state.isActive }.collect(onSearchActive) }
    LaunchedEffect(state) { snapshotFlow { state.progress.value }.collect(onSearchProgress) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        app.search.onResume()
        // Ready before the next swipe, so the first frame of the search already shows suggestions.
        app.suggestions.refresh(context)
        // Focus without keyboard: the keyboard connection is ready before the next swipe.
        runCatching { focusRequester.requestFocus() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.reset() }

    val colors = glassColors()
    val homeBackdrop = rememberLayerBackdrop()
    val panelBackdrop = rememberLayerBackdrop()
    val distancePx = with(density) { SearchState.DRAG_DISTANCE_DP.dp.toPx() }
    val topInset = WindowInsets.statusBars.getTop(density).toFloat()

    CompositionLocalProvider(LocalGlassColors provides colors) {
        Box(
            Modifier
                .fillMaxSize()
                .searchGesture(state, distancePx, topInset) { !controller.isEditing && !controller.onLibrary },
        ) {
            // Everything the glass refracts: the home screen without its overlays. Only recorded while
            // something made of glass is showing.
            val glassShowing = state.isActive || controller.openFolderId != null ||
                controller.openCategory != null || controller.widgetPickerOpen
            Box(Modifier.fillMaxSize().then(if (glassShowing) Modifier.layerBackdrop(homeBackdrop) else Modifier)) {
                workspace?.let { ws -> HomeScreen(ws, apps, library, controller, homeReset) }
            }
            if (state.isActive) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .frostedBackdrop(homeBackdrop, colors, progress = { state.progress.value }, exportedBackdrop = panelBackdrop),
                )
            }
            CompositionLocalProvider(LocalContentColor provides colors.content) {
                SearchPanel(state, viewModel, actions, focusRequester, panelBackdrop)
            }
            workspace?.let { ws ->
                FolderOverlay(controller, ws, apps, homeBackdrop)
                CategoryOverlay(controller, ws, homeBackdrop)
            }
            WidgetPicker(controller, apps, homeBackdrop)
            DragOverlay(controller, apps)
        }
    }
}
