package io.github.davydgky.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.davydgky.launcher.ai.AppKnowledge
import io.github.davydgky.launcher.data.LauncherPrefs
import io.github.davydgky.launcher.home.AppLibrary
import io.github.davydgky.launcher.home.CategoryOverlay
import io.github.davydgky.launcher.home.DragOverlay
import io.github.davydgky.launcher.home.FolderOverlay
import io.github.davydgky.launcher.home.HomeController
import io.github.davydgky.launcher.home.HomeScreen
import io.github.davydgky.launcher.home.LibraryGroup
import io.github.davydgky.launcher.home.PagesOverlay
import io.github.davydgky.launcher.home.WidgetPicker
import io.github.davydgky.launcher.home.Workspace
import io.github.davydgky.launcher.home.homeDragTracker
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.search.ResultActions
import io.github.davydgky.launcher.search.SearchViewModel
import io.github.davydgky.launcher.ui.glass.LocalGlassColors
import io.github.davydgky.launcher.ui.glass.frostedBackdrop
import io.github.davydgky.launcher.ui.glass.glassColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.max
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
    val shortcutList by app.apps.shortcuts.collectAsStateWithLifecycle()
    val shortcuts = remember(shortcutList) { shortcutList.associateBy { it.key } }
    val workspace by app.workspace.workspace.collectAsStateWithLifecycle()
    val library by produceState(emptyList<LibraryGroup>(), app) {
        combine(app.apps.apps, app.suggestions.current, app.usage.appPrior, app.appKnowledge.known) { list, suggestions, prior, learned ->
            LibraryInput(list, suggestions.apps, prior, learned)
        }.collect { input ->
            value = withContext(Dispatchers.Default) { AppLibrary.groups(input.apps, input.suggested, input.prior, input.learned) }
        }
    }
    val suggestions by app.suggestions.current.collectAsStateWithLifecycle()
    val prefs by app.settings.prefs.collectAsStateWithLifecycle(LauncherPrefs())
    // The dock's free place shows the most likely app that is not in the dock already.
    val dockSuggestion = workspace?.let { ws ->
        if (!prefs.dockSuggestion || ws.dock.size >= Workspace.DOCK_SIZE) null
        else suggestions.apps.firstOrNull { it.key !in ws.dock }
    }
    LaunchedEffect(appList) { app.icons.preloadApps(appList) }
    LaunchedEffect(Unit) { app.workspace.cleanUpWidgets() }

    LaunchedEffect(homePressed) {
        homePressed.collect {
            controller.dismissMenus()
            when {
                state.isActive -> state.close()
                controller.openFolderId != null -> controller.openFolderId = null
                controller.openCategory != null -> controller.openCategory = null
                controller.widgetPickerOpen -> controller.widgetPickerOpen = false
                controller.pagesOverviewOpen -> controller.pagesOverviewOpen = false
                controller.editMode -> controller.stopEditing()
                else -> homeReset++
            }
        }
    }
    BackHandler(enabled = state.isActive) { state.close() }
    BackHandler(enabled = controller.editMode && !state.isActive) { controller.stopEditing() }
    LaunchedEffect(state) { snapshotFlow { state.isActive }.collect(onSearchActive) }
    LaunchedEffect(state) { snapshotFlow { state.progress.value }.collect(onSearchProgress) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        app.search.onResume()
        // Ready before the next swipe, so the first frame of the search already shows suggestions.
        app.suggestions.refresh(context)
        // Focus without keyboard: the keyboard connection is ready before the next swipe.
        runCatching { focusRequester.requestFocus() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        state.reset()
        controller.stopEditing()
    }

    val colors = glassColors()
    val homeBackdrop = rememberLayerBackdrop()
    val panelBackdrop = rememberLayerBackdrop()
    val distancePx = with(density) { SearchState.DRAG_DISTANCE_DP.dp.toPx() }
    val swipeUpPx = with(density) { 72.dp.toPx() }
    val topInset = WindowInsets.statusBars.getTop(density).toFloat()

    CompositionLocalProvider(LocalGlassColors provides colors) {
        Box(
            Modifier
                .fillMaxSize()
                .searchGesture(state, distancePx, topInset, swipeUpPx, onSwipeUp = controller::showLibrary) {
                    !controller.isEditing && !controller.onLibrary
                }
                .homeDragTracker(controller),
        ) {
            // Everything the glass refracts: the home screen without its overlays. Only recorded while
            // something made of glass is showing.
            val overlayOpen = controller.openFolderId != null || controller.openCategory != null ||
                controller.widgetPickerOpen || controller.pagesOverviewOpen
            val overlayFrost by animateFloatAsState(if (overlayOpen) 1f else 0f, tween(OVERLAY_FADE_MS), label = "overlayFrost")
            val glassShowing = state.isActive || overlayOpen || overlayFrost > 0f
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (glassShowing) {
                            Modifier
                                // The home screen is transparent around its icons (the wallpaper is another
                                // window), so its blurred copy cannot cover it: the sharp original fades out
                                // while the copy is recorded for the glass.
                                .graphicsLayer { alpha = 1f - max(state.progress.value, overlayFrost) }
                                .layerBackdrop(homeBackdrop)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                workspace?.let { ws -> HomeScreen(ws, apps, shortcuts, library, controller, homeReset, dockSuggestion) }
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
            workspace?.let { ws -> PagesOverlay(controller, ws, apps, homeBackdrop) }
            DragOverlay(controller, apps, shortcuts)
        }
    }
}

private const val OVERLAY_FADE_MS = 220

private class LibraryInput(
    val apps: List<AppTarget>,
    val suggested: List<AppTarget>,
    val prior: Map<String, Float>,
    val learned: Map<String, AppKnowledge.Info>,
)
