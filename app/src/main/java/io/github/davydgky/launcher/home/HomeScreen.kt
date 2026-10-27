@file:OptIn(ExperimentalFoundationApi::class)

package io.github.davydgky.launcher.home

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.SizeF
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.settings.SettingsActivity
import io.github.davydgky.launcher.ui.AppIconImage
import io.github.davydgky.launcher.ui.LocalLightWallpaper
import io.github.davydgky.launcher.ui.ScreenBounds
import io.github.davydgky.launcher.ui.wallpaperTextColor
import io.github.davydgky.launcher.widgets.WidgetHost
import kotlin.math.roundToInt

/**
 * The home screen: pages of apps, Nothing-style folders and widgets that the user arranges freely, the
 * App Library to the right of the last page, a small search button and the dock.
 */
@Composable
fun HomeScreen(
    workspace: Workspace,
    apps: Map<String, AppTarget>,
    library: List<LibraryGroup>,
    controller: HomeController,
    resetSignal: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dragging = controller.drag != null
    // While dragging, an empty page after the last one takes elements to a new page.
    val homePages = workspace.pageCount + if (dragging) 1 else 0
    controller.workspace = workspace
    controller.columns = workspace.columns
    controller.rows = workspace.rows
    controller.homePages = homePages
    controller.dockSize = workspace.dock.size
    controller.density = LocalDensity.current.density
    controller.folderName = { keys -> folderName(context, keys.mapNotNull(apps::get)) }

    val pagerState = rememberPagerState { homePages + 1 }
    controller.pager = pagerState
    LaunchedEffect(resetSignal) { if (resetSignal > 0) pagerState.animateScrollToPage(0) }
    var backgroundMenu by remember(controller.menuEpoch) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Column(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { controller.screenWidth = it.size.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    // Long presses on apps, folders and widgets have their own menus.
                    if (controller.itemsPressed == 0) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        backgroundMenu = true
                    }
                })
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !dragging,
                // Keeps every page alive, so an element dragged across pages keeps its gesture.
                beyondViewportPageCount = homePages,
            ) { page ->
                if (page < homePages) {
                    WorkspacePage(page, workspace, apps, controller)
                } else {
                    AppLibraryPage(library, workspace, controller)
                }
            }
            DropdownMenu(expanded = backgroundMenu, onDismissRequest = { backgroundMenu = false }) {
                MenuItem(R.string.menu_add_widget, R.drawable.ic_widget) {
                    backgroundMenu = false
                    controller.widgetPickerOpen = true
                }
                MenuItem(R.string.menu_wallpaper, R.drawable.ic_image) {
                    backgroundMenu = false
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), null)) }
                }
                MenuItem(R.string.settings_title, R.drawable.ic_settings) {
                    backgroundMenu = false
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                }
            }
        }
        val onLibrary = pagerState.currentPage >= homePages
        PageIndicator(homePages, pagerState.currentPage)
        if (!onLibrary) SearchButton(controller.openSearch) else Spacer(Modifier.height(SEARCH_BUTTON_SPACE))
        Dock(workspace, apps, controller)
    }
}

private val CELL_PADDING = 4.dp
private val SEARCH_BUTTON_SPACE = 38.dp

@Composable
private fun WorkspacePage(page: Int, workspace: Workspace, apps: Map<String, AppTarget>, controller: HomeController) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .onGloballyPositioned { controller.pageBounds[page] = it.boundsInRoot() },
    ) {
        val cellW = maxWidth / workspace.columns
        val cellH = maxHeight / workspace.rows
        val drag = controller.drag
        // Where the dragged element would land.
        when (val target = drag?.target) {
            is DropTarget.Move -> if (target.cell.page == page) {
                Box(
                    Modifier
                        .offset(cellW * target.cell.x, cellH * target.cell.y)
                        .size(cellW * target.cell.w, cellH * target.cell.h)
                        .padding(CELL_PADDING)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.16f)),
                )
            }
            else -> Unit
        }
        workspace.itemsOn(page).forEach { item ->
            key(item.id) {
                val target = with(density) {
                    IntOffset((cellW * item.cell.x).roundToPx(), (cellH * item.cell.y).roundToPx())
                }
                val offset by animateIntOffsetAsState(target, label = "cell")
                val highlighted = when (val t = drag?.target) {
                    is DropTarget.MakeFolder -> t.targetId == item.id
                    is DropTarget.IntoFolder -> t.folderId == item.id
                    else -> false
                }
                Box(
                    Modifier
                        .offset { offset }
                        .size(cellW * item.cell.w, cellH * item.cell.h)
                        .graphicsLayer {
                            alpha = if ((drag?.source as? DragSource.Item)?.item?.id == item.id) 0.25f else 1f
                            val scale = if (highlighted) 1.12f else 1f
                            scaleX = scale
                            scaleY = scale
                        },
                ) {
                    when (item) {
                        is AppItem -> apps[item.appKey]?.let { HomeAppIcon(it, DragSource.Item(item), controller, showLabel = true) }
                        is FolderItem -> FolderCell(item, apps, controller)
                        is WidgetItem -> WidgetCell(item, workspace, cellW, cellH, controller)
                    }
                }
            }
        }
    }
}

/** An app icon with label; tap opens, long press shows the menu, long press and move drags it. */
@Composable
private fun HomeAppIcon(app: AppTarget, source: DragSource, controller: HomeController, showLabel: Boolean) {
    val bounds = remember { ScreenBounds() }
    var menu by remember(controller.menuEpoch) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val haptics = LocalHapticFeedback.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { size = it.size }
                .homeItemGestures(
                    key = source,
                    callbacks = HomeItemGestureCallbacks(
                        onTap = { controller.actions.launchApp(app, bounds.rect) },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            controller.itemHeld()
                            menu = true
                        },
                        onDragStart = { pointer, grab ->
                            menu = false
                            controller.startDrag(source, pointer, grab, size)
                        },
                        onDrag = controller::moveDrag,
                        onDragEnd = controller::endDrag,
                        onDown = controller::itemDown,
                        onRelease = controller::itemReleased,
                    ),
                )
                .padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIconImage(app, ICON_SIZE, Modifier.onGloballyPositioned(bounds::update))
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                IconLabel(app.label)
            }
        }
        AppMenu(app, source, menu, onDismiss = { menu = false }, controller, bounds)
    }
}

private val ICON_SIZE = 58.dp

@Composable
private fun IconLabel(text: String, modifier: Modifier = Modifier) {
    val lightWallpaper = LocalLightWallpaper.current
    Text(
        text,
        style = TextStyle(
            fontSize = 12.sp,
            color = wallpaperTextColor(),
            shadow = if (lightWallpaper) null else Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 6f),
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

/**
 * A folder like on Nothing OS: 2×2 cells whose app icons open directly. With more than four apps the
 * last slot shows the rest in miniature and opens the folder.
 */
@Composable
private fun FolderCell(folder: FolderItem, apps: Map<String, AppTarget>, controller: HomeController) {
    var menu by remember(controller.menuEpoch) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val haptics = LocalHapticFeedback.current
    val members = folder.apps.mapNotNull(apps::get)
    Column(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { size = it.size }
            .homeItemGestures(
                key = folder.id,
                callbacks = HomeItemGestureCallbacks(
                    onTap = null,
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        controller.itemHeld()
                        menu = true
                    },
                    onDragStart = { pointer, grab ->
                        menu = false
                        controller.startDrag(DragSource.Item(folder), pointer, grab, size)
                    },
                    onDrag = controller::moveDrag,
                    onDragEnd = controller::endDrag,
                    onDown = controller::itemDown,
                    onRelease = controller::itemReleased,
                ),
            )
            .padding(CELL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .weight(1f)
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(30.dp))
                .background(folderFill())
                .clickable { controller.openFolderId = folder.id }
                .padding(8.dp),
        ) {
            AppQuad(
                apps = members,
                overflow = members.size > FolderItem.DIRECT_SLOTS,
                onOpenApp = { app, bounds -> controller.actions.launchApp(app, bounds) },
                onOverflow = { controller.openFolderId = folder.id },
            )
        }
        Spacer(Modifier.height(4.dp))
        IconLabel(folder.name.ifBlank { stringResource(R.string.folder_default_name) })
        FolderMenu(folder, menu, onDismiss = { menu = false }, controller)
    }
}

@Composable
private fun folderFill(): Color =
    if (LocalLightWallpaper.current) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.2f)

/**
 * Four slots of app icons that open directly. With [overflow], the fourth slot shows the next apps in
 * miniature and calls [onOverflow].
 */
@Composable
fun AppQuad(
    apps: List<AppTarget>,
    overflow: Boolean,
    onOpenApp: (AppTarget, Rect?) -> Unit,
    onOverflow: () -> Unit,
    onLongPressApp: ((AppTarget) -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val slot = minOf(maxWidth, maxHeight) / 2
        val icon = minOf(slot - 10.dp, ICON_SIZE)
        Column {
            for (row in 0 until 2) {
                Row {
                    for (col in 0 until 2) {
                        val index = row * 2 + col
                        Box(Modifier.size(slot), contentAlignment = Alignment.Center) {
                            if (overflow && index == 3) {
                                MiniCluster(apps.drop(3).take(4), icon, onOverflow)
                            } else {
                                apps.getOrNull(index)?.let { app ->
                                    val bounds = remember { ScreenBounds() }
                                    AppIconImage(
                                        app, icon,
                                        Modifier
                                            .onGloballyPositioned(bounds::update)
                                            .clip(CircleShape)
                                            .combinedClickable(
                                                onClick = { onOpenApp(app, bounds.rect) },
                                                onLongClick = onLongPressApp?.let { { it(app) } },
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniCluster(apps: List<AppTarget>, size: Dp, onClick: () -> Unit) {
    Column(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        apps.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { AppIconImage(it, size / 2 - 3.dp) }
                if (row.size == 1) Spacer(Modifier.size(size / 2 - 3.dp))
            }
        }
    }
}

/** A hosted app widget; long press for size and removal, long press and move to drag. */
@Composable
private fun WidgetCell(item: WidgetItem, workspace: Workspace, cellW: Dp, cellH: Dp, controller: HomeController) {
    val app = LocalContext.current.launcherApp
    val info = remember(item.widgetId) { app.widgets.info(item.widgetId) }
    var menu by remember(controller.menuEpoch) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val haptics = LocalHapticFeedback.current
    val widthDp = (cellW * item.cell.w - CELL_PADDING * 2).value
    val heightDp = (cellH * item.cell.h - CELL_PADDING * 2).value
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { size = it.size }
            .homeItemGestures(
                key = item.id,
                callbacks = HomeItemGestureCallbacks(
                    onTap = null,
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        controller.itemHeld()
                        menu = true
                    },
                    onDragStart = { pointer, grab ->
                        menu = false
                        controller.startDrag(DragSource.Item(item), pointer, grab, size)
                    },
                    onDrag = controller::moveDrag,
                    onDragEnd = controller::endDrag,
                    onDown = controller::itemDown,
                    onRelease = controller::itemReleased,
                ),
            )
            .padding(CELL_PADDING)
            .clip(RoundedCornerShape(22.dp)),
    ) {
        if (info == null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.widget_unavailable), color = Color.White, textAlign = TextAlign.Center)
            }
        } else {
            AndroidView(
                factory = { ctx -> app.widgets.createView(ctx, item.widgetId, info) },
                update = { view ->
                    val sizeTag = widthDp to heightDp
                    if (view.tag != sizeTag) {
                        view.tag = sizeTag
                        view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (info != null) {
            val options = remember(info, cellW, cellH) {
                WidgetHost.sizeOptions(info, cellW.value, cellH.value, workspace.columns, workspace.rows)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(18.dp)) {
                options.forEach { span ->
                    val current = span.w == item.cell.w && span.h == item.cell.h
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.widget_size, span.w, span.h)) },
                        trailingIcon = if (current) ({ Icon(painterResource(R.drawable.ic_check), null) }) else null,
                        onClick = {
                            menu = false
                            controller.repository.resizeWidget(item.id, span.w, span.h)
                        },
                    )
                }
                HorizontalDivider()
                MenuItem(R.string.menu_remove_widget, R.drawable.ic_trash) {
                    menu = false
                    controller.repository.removeItem(item.id)
                }
            }
        } else {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(R.string.menu_remove_widget, R.drawable.ic_trash) {
                    menu = false
                    controller.repository.removeItem(item.id)
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(homePages: Int, current: Int) {
    val color = wallpaperTextColor()
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), horizontalArrangement = Arrangement.Center) {
        repeat(homePages) { page ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (page == current) 0.95f else 0.35f)),
            )
        }
        // The App Library, drawn as a tiny grid like on iOS.
        Column(Modifier.padding(horizontal = 4.dp).size(7.dp), verticalArrangement = Arrangement.SpaceBetween) {
            repeat(2) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(2) {
                        Box(Modifier.size(3.dp).background(color.copy(alpha = if (current >= homePages) 0.95f else 0.35f)))
                    }
                }
            }
        }
    }
}

/** A small pill like the "Search" button on iOS home screens; tapping opens the search. */
@Composable
private fun SearchButton(onClick: () -> Unit) {
    val color = wallpaperTextColor()
    Box(Modifier.fillMaxWidth().height(SEARCH_BUTTON_SPACE), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.16f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(11.dp), tint = color)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.search_button), color = color, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Dock(workspace: Workspace, apps: Map<String, AppTarget>, controller: HomeController) {
    val tint = if (LocalLightWallpaper.current) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.16f)
    val dragging = controller.drag
    val dropIndex = (dragging?.target as? DropTarget.Dock)?.index
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .heightIn(min = 84.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(if (dropIndex != null) tint.copy(alpha = tint.alpha + 0.12f) else tint)
            .onGloballyPositioned { controller.dockBounds = it.boundsInRoot() }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        workspace.dock.forEach { appKey ->
            val app = apps[appKey] ?: return@forEach
            key(appKey) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .graphicsLayer { alpha = if ((dragging?.source as? DragSource.Dock)?.appKey == appKey) 0.25f else 1f },
                ) {
                    HomeAppIcon(app, DragSource.Dock(appKey), controller, showLabel = false)
                }
            }
        }
    }
}

@Composable
private fun AppMenu(
    app: AppTarget,
    source: DragSource,
    expanded: Boolean,
    onDismiss: () -> Unit,
    controller: HomeController,
    bounds: ScreenBounds,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(18.dp)) {
        val shortcuts = remember(expanded) { if (expanded) controller.shortcutsOf(app).take(4) else emptyList() }
        shortcuts.forEach { shortcut ->
            DropdownMenuItem(
                text = { Text(shortcut.info.shortLabel?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = {
                    onDismiss()
                    controller.actions.launchShortcut(shortcut, bounds.rect)
                },
            )
        }
        if (shortcuts.isNotEmpty()) HorizontalDivider()
        when (source) {
            is DragSource.Dock -> MenuItem(R.string.menu_remove_from_dock, R.drawable.ic_pin) {
                onDismiss()
                controller.repository.fromDock(app.key, null)
            }
            is DragSource.Item -> {
                if (controller.dockSize < Workspace.DOCK_SIZE) {
                    MenuItem(R.string.menu_add_to_dock, R.drawable.ic_pin) {
                        onDismiss()
                        controller.repository.toDock(app.key, controller.dockSize)
                    }
                }
                MenuItem(R.string.menu_remove_from_home, R.drawable.ic_minus) {
                    onDismiss()
                    controller.repository.removeApp(app.key)
                }
            }
        }
        MenuItem(R.string.menu_app_info, R.drawable.ic_info) {
            onDismiss()
            controller.actions.appInfo(app)
        }
        MenuItem(R.string.menu_uninstall, R.drawable.ic_trash) {
            onDismiss()
            controller.actions.uninstall(app)
        }
    }
}

@Composable
private fun FolderMenu(folder: FolderItem, expanded: Boolean, onDismiss: () -> Unit, controller: HomeController) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(18.dp)) {
        MenuItem(R.string.menu_rename_folder, R.drawable.ic_edit) {
            onDismiss()
            controller.openFolderId = folder.id
        }
        MenuItem(R.string.menu_dissolve_folder, R.drawable.ic_folder) {
            onDismiss()
            controller.repository.dissolveFolder(folder.id)
        }
        MenuItem(R.string.menu_remove_folder, R.drawable.ic_minus) {
            onDismiss()
            controller.repository.removeItem(folder.id)
        }
    }
}

@Composable
fun MenuItem(label: Int, icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(painterResource(icon), null) },
        onClick = onClick,
    )
}

/** Like iOS: a search field on top, then boxes of apps by category. */
@Composable
private fun AppLibraryPage(groups: List<LibraryGroup>, workspace: Workspace, controller: HomeController) {
    val color = wallpaperTextColor()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("search") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(folderFill())
                    .clickable(onClick = controller.openSearch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(16.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.library_search), color = color.copy(alpha = 0.8f), fontSize = 15.sp)
            }
        }
        items(groups.chunked(2), key = { row -> row.joinToString { it.title.toString() } }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { group -> LibraryBox(group, workspace, controller, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LibraryBox(group: LibraryGroup, workspace: Workspace, controller: HomeController, modifier: Modifier) {
    var menuApp by remember(controller.menuEpoch) { mutableStateOf<AppTarget?>(null) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(folderFill())
                .clickable { controller.openCategory = group }
                .padding(8.dp),
        ) {
            AppQuad(
                apps = group.apps,
                overflow = group.apps.size > FolderItem.DIRECT_SLOTS,
                onOpenApp = { app, bounds -> controller.actions.launchApp(app, bounds) },
                onOverflow = { controller.openCategory = group },
                onLongPressApp = { menuApp = it },
            )
            menuApp?.let { app ->
                LibraryAppMenu(app, workspace, onDismiss = { menuApp = null }, controller)
            }
        }
        Spacer(Modifier.height(6.dp))
        IconLabel(stringResource(group.title))
    }
}

/** Long-press menu of an app in the App Library or a folder view. */
@Composable
fun LibraryAppMenu(app: AppTarget, workspace: Workspace, onDismiss: () -> Unit, controller: HomeController, folderId: Long? = null) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, shape = RoundedCornerShape(18.dp)) {
        if (folderId != null) {
            MenuItem(R.string.menu_remove_from_folder, R.drawable.ic_minus) {
                onDismiss()
                controller.repository.removeFromFolder(folderId, app.key)
            }
        } else if (app.key !in workspace.appsOnHome()) {
            MenuItem(R.string.menu_add_to_home, R.drawable.ic_add) {
                onDismiss()
                controller.repository.addApp(app.key)
            }
        }
        MenuItem(R.string.menu_app_info, R.drawable.ic_info) {
            onDismiss()
            controller.actions.appInfo(app)
        }
        MenuItem(R.string.menu_uninstall, R.drawable.ic_trash) {
            onDismiss()
            controller.actions.uninstall(app)
        }
    }
}

/** A folder takes the name of the category its first apps share, as on iOS. */
private fun folderName(context: Context, apps: List<AppTarget>): String {
    val categories = apps.map(AppLibrary::categoryOf).distinct()
    val single = categories.singleOrNull()
    return if (single != null && single != R.string.category_other) context.getString(single)
    else context.getString(R.string.folder_default_name)
}
