@file:OptIn(ExperimentalFoundationApi::class)

package io.github.davydgky.launcher.home

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.search.ResultActions
import io.github.davydgky.launcher.search.ShortcutTarget
import io.github.davydgky.launcher.settings.SettingsActivity
import io.github.davydgky.launcher.ui.AppIconImage
import io.github.davydgky.launcher.ui.LocalLightWallpaper
import io.github.davydgky.launcher.ui.ScreenBounds
import io.github.davydgky.launcher.ui.wallpaperTextColor
import kotlin.math.ceil
import kotlin.math.max

/** Everything the home screen needs from outside. */
class HomeCallbacks(
    val actions: ResultActions,
    val shortcutsOf: (AppTarget) -> List<ShortcutTarget>,
    val isInDock: (AppTarget) -> Boolean,
    val toggleDock: (AppTarget) -> Unit,
    val openSearch: () -> Unit,
)

/**
 * Pages of apps in alphabetical order (swiped horizontally, so vertical swipes are free for the
 * search), a search button and the dock.
 */
@Composable
fun HomeScreen(
    apps: List<AppTarget>,
    dock: List<AppTarget>,
    callbacks: HomeCallbacks,
    resetSignal: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var backgroundMenu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Column(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    backgroundMenu = true
                })
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val pageApps = remember(apps, dock) { apps.filter { app -> dock.none { it.key == app.key } } }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (maxWidth >= 600.dp) 6 else 4
            val rows = max(1, ((maxHeight - 24.dp) / CELL_HEIGHT).toInt())
            val perPage = columns * rows
            val pages = max(1, ceil(pageApps.size / perPage.toFloat()).toInt())
            val pagerState = rememberPagerState { pages }
            LaunchedEffect(resetSignal) { if (resetSignal > 0) pagerState.animateScrollToPage(0) }
            Column {
                HorizontalPager(pagerState, Modifier.weight(1f).fillMaxWidth()) { page ->
                    val slice = pageApps.drop(page * perPage).take(perPage)
                    Column(Modifier.fillMaxSize().padding(top = 16.dp, start = 8.dp, end = 8.dp)) {
                        slice.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth().height(CELL_HEIGHT)) {
                                row.forEach { app -> AppCell(app, callbacks, Modifier.weight(1f)) }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                PageIndicator(pages, pagerState.currentPage)
            }
            DropdownMenu(expanded = backgroundMenu, onDismissRequest = { backgroundMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_wallpaper)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_image), null) },
                    onClick = {
                        backgroundMenu = false
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), null)) }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_title)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_settings), null) },
                    onClick = {
                        backgroundMenu = false
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    },
                )
            }
        }
        SearchButton(callbacks.openSearch)
        Dock(dock, callbacks)
    }
}

private val CELL_HEIGHT = 100.dp

@Composable
private fun PageIndicator(pages: Int, current: Int) {
    if (pages < 2) return
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        val color = wallpaperTextColor()
        repeat(pages) { page ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (page == current) 0.95f else 0.35f)),
            )
        }
    }
}

/** A pill like the "Search" button on iOS home screens; tapping opens the search. */
@Composable
private fun SearchButton(onClick: () -> Unit) {
    val color = wallpaperTextColor()
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.18f))
                .combinedClickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(15.dp), tint = color)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.search_button), color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Dock(dock: List<AppTarget>, callbacks: HomeCallbacks) {
    if (dock.isEmpty()) return
    val tint = if (LocalLightWallpaper.current) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.16f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(tint)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        dock.forEach { app -> AppCell(app, callbacks, Modifier.weight(1f), showLabel = false) }
    }
}

@Composable
private fun AppCell(app: AppTarget, callbacks: HomeCallbacks, modifier: Modifier, showLabel: Boolean = true) {
    val bounds = remember { ScreenBounds() }
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = { callbacks.actions.launchApp(app, bounds.rect) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIconImage(app, 58.dp, Modifier.onGloballyPositioned(bounds::update))
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                val lightWallpaper = LocalLightWallpaper.current
                Text(
                    app.label,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = wallpaperTextColor(),
                        shadow = if (lightWallpaper) null else Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 6f),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(76.dp),
                )
            }
        }
        AppMenu(app, menu, onDismiss = { menu = false }, callbacks, bounds)
    }
}

/** Long-press menu: the app's shortcuts, then dock, app info and uninstall. */
@Composable
private fun AppMenu(app: AppTarget, expanded: Boolean, onDismiss: () -> Unit, callbacks: HomeCallbacks, bounds: ScreenBounds) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(18.dp)) {
        val shortcuts = remember(expanded) { if (expanded) callbacks.shortcutsOf(app).take(4) else emptyList() }
        shortcuts.forEach { shortcut ->
            DropdownMenuItem(
                text = { Text(shortcut.info.shortLabel?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = {
                    onDismiss()
                    callbacks.actions.launchShortcut(shortcut, bounds.rect)
                },
            )
        }
        if (shortcuts.isNotEmpty()) HorizontalDivider()
        val inDock = callbacks.isInDock(app)
        DropdownMenuItem(
            text = { Text(stringResource(if (inDock) R.string.menu_remove_from_dock else R.string.menu_add_to_dock)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_pin), null) },
            onClick = {
                onDismiss()
                callbacks.toggleDock(app)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_app_info)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_info), null) },
            onClick = {
                onDismiss()
                callbacks.actions.appInfo(app)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_uninstall)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_trash), null) },
            onClick = {
                onDismiss()
                callbacks.actions.uninstall(app)
            },
        )
    }
}
