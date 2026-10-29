@file:OptIn(ExperimentalFoundationApi::class)

package io.github.davydgky.launcher.home

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.SizeF
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.notifications.Notifications
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.search.ShortcutTarget
import io.github.davydgky.launcher.settings.SettingsActivity
import io.github.davydgky.launcher.ui.AppIconImage
import io.github.davydgky.launcher.ui.LocalLightWallpaper
import io.github.davydgky.launcher.ui.ResultIcon
import io.github.davydgky.launcher.ui.ScreenBounds
import io.github.davydgky.launcher.ui.wallpaperTextColor
import io.github.davydgky.launcher.widgets.WidgetHost
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen: pages of apps, Nothing-style folders and widgets that the user arranges freely, the
 * App Library to the right of the last page, a small search button and the dock.
 */
@Composable
fun HomeScreen(
    workspace: Workspace,
    apps: Map<String, AppTarget>,
    shortcuts: Map<String, ShortcutTarget>,
    library: List<LibraryGroup>,
    controller: HomeController,
    resetSignal: Int,
    dockSuggestion: AppTarget?,
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
    val haptics = LocalHapticFeedback.current

    Column(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { controller.screenWidth = it.size.width.toFloat() }
            .pointerInput(Unit) {
                // Apps, folders and widgets have their own long press; only the background starts editing.
                var onItem = false
                detectTapGestures(
                    onPress = { onItem = controller.itemsPressed > 0 },
                    onTap = { if (!onItem) controller.stopEditing() },
                    onLongPress = {
                        if (!onItem && !controller.editMode) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            controller.startEditing()
                        }
                    },
                )
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val density = LocalDensity.current
        // Search button and dock; the App Library has the screen down to the page dots without them.
        var barsHeight by remember { mutableIntStateOf(0) }
        val barsHeightDp = with(density) { barsHeight.toDp() }
        // 0 on the last home page, 1 halfway to the App Library: the dock is gone before it arrives.
        val libraryProgress = {
            ((pagerState.currentPage + pagerState.currentPageOffsetFraction - (homePages - 1)) * 2f).coerceIn(0f, 1f)
        }
        val onLibrary = pagerState.currentPage >= homePages
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !dragging,
                // Keeps every page alive, so an element dragged across pages keeps its gesture.
                beyondViewportPageCount = homePages,
            ) { page ->
                if (page < homePages) {
                    Box(Modifier.padding(bottom = INDICATOR_HEIGHT + barsHeightDp)) {
                        WorkspacePage(page, workspace, apps, shortcuts, controller)
                    }
                } else {
                    Box(Modifier.padding(bottom = INDICATOR_HEIGHT)) {
                        AppLibraryPage(library, workspace, controller)
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                // The dots move down into the dock's place as it fades out.
                Box(Modifier.graphicsLayer { translationY = libraryProgress() * barsHeight }) {
                    PageIndicator(homePages, pagerState, onEditTap = { controller.pagesOverviewOpen = true }.takeIf { controller.editMode })
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { if (!onLibrary) barsHeight = it.height }
                        .graphicsLayer {
                            val p = libraryProgress()
                            alpha = 1f - p
                            translationY = p * size.height * 0.4f
                        },
                ) {
                    if (onLibrary && barsHeight > 0) {
                        // Hidden on the App Library: same room, but nothing there catches a touch.
                        Spacer(Modifier.height(barsHeightDp))
                    } else {
                        if (controller.editMode) EditBar(controller) else SearchButton(controller.openSearch)
                        Dock(workspace, apps, controller, dockSuggestion)
                    }
                }
            }
        }
    }
}

private val CELL_PADDING = 4.dp
private val CELL_SPRING = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
private val SEARCH_BUTTON_SPACE = 30.dp

/** Apps shown in an App Library box; with more, the last slot shows the rest in miniature. */
private const val LIBRARY_SLOTS = 4

private const val WIGGLE_DEGREES = 1.6f

/** The back-and-forth rotation of elements in edit mode; 0 when [HomeController.wiggling] is off. */
@Composable
private fun wiggleAngle(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "wiggle")
    return transition.animateFloat(
        initialValue = -WIGGLE_DEGREES,
        targetValue = WIGGLE_DEGREES,
        animationSpec = infiniteRepeatable(tween(140, easing = LinearEasing), RepeatMode.Reverse),
        label = "wiggle",
    )
}

/** The grey minus of edit mode that takes an element off the home screen. */
@Composable
private fun RemoveBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.edit_remove)
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(0xEB8E8E93))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_minus), label, Modifier.size(14.dp), tint = Color.White)
    }
}

/** Replaces the search button in edit mode: add a widget, wallpaper, settings and done. */
@Composable
private fun EditBar(controller: HomeController) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().height(SEARCH_BUTTON_SPACE).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EditPill(R.drawable.ic_add, stringResource(R.string.menu_add_widget)) { controller.openWidgetPicker() }
        EditPill(R.drawable.ic_pages, null, stringResource(R.string.edit_pages)) { controller.pagesOverviewOpen = true }
        EditPill(R.drawable.ic_image, null, stringResource(R.string.menu_wallpaper)) {
            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), null)) }
        }
        EditPill(R.drawable.ic_settings, null, stringResource(R.string.settings_title)) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
        Spacer(Modifier.weight(1f))
        EditPill(null, stringResource(R.string.edit_done), emphasized = true, onClick = controller::stopEditing)
    }
}

@Composable
private fun EditPill(icon: Int?, text: String?, description: String? = text, emphasized: Boolean = false, onClick: () -> Unit) {
    val color = wallpaperTextColor()
    val content = if (emphasized) (if (LocalLightWallpaper.current) Color.White else Color(0xFF1B1B1F)) else color
    Row(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(50))
            .background(if (emphasized) color.copy(alpha = 0.92f) else color.copy(alpha = 0.18f))
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = if (text == null) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(painterResource(icon), if (text == null) description else null, Modifier.size(15.dp), tint = content)
        if (icon != null && text != null) Spacer(Modifier.width(5.dp))
        if (text != null) {
            Text(text, color = content, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}

@Composable
private fun WorkspacePage(
    page: Int,
    workspace: Workspace,
    apps: Map<String, AppTarget>,
    shortcuts: Map<String, ShortcutTarget>,
    controller: HomeController,
) {
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
        val wiggle = wiggleAngle(controller.wiggling)
        // With many columns or rows the icons shrink so labels keep their room.
        val iconSize = minOf(ICON_SIZE, cellW * 0.76f, cellH - 34.dp)
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
        // While an element is dragged the others stand where they make room for it, maybe on another
        // page; the dragged one keeps its place (unseen), so its gesture is never moved around.
        val dragged = (drag?.source as? DragSource.Item)?.item?.id
        fun cellOf(item: HomeItem) = if (drag == null || item.id == dragged) item.cell else drag.cellOf(item)
        val drop = controller.drop
        CompositionLocalProvider(LocalIconSize provides iconSize) { workspace.items.filter { cellOf(it).page == page }.forEach { item ->
            key(item.id) {
                val cell = cellOf(item)
                val target = with(density) {
                    IntOffset((cellW * cell.x).roundToPx(), (cellH * cell.y).roundToPx())
                }
                val offset = remember { Animatable(target, IntOffset.VectorConverter) }
                LaunchedEffect(target, drop) {
                    if (drop != null && drop.id == item.id) {
                        // Let go: glide from under the finger into the cell instead of from the old one.
                        val origin = controller.pageBounds[page]?.topLeft ?: Offset.Zero
                        offset.snapTo(IntOffset((drop.topLeft.x - origin.x).roundToInt(), (drop.topLeft.y - origin.y).roundToInt()))
                        controller.drop = null
                    }
                    offset.animateTo(target, CELL_SPRING)
                }
                val highlighted = when (val t = drag?.target) {
                    is DropTarget.MakeFolder -> t.targetId == item.id
                    is DropTarget.IntoFolder -> t.folderId == item.id
                    else -> false
                }
                Box(
                    Modifier
                        .offset { offset.value }
                        .size(cellW * cell.w, cellH * cell.h)
                        .graphicsLayer {
                            // The dragged element shows under the finger; the dropped one until it glides.
                            alpha = if (item.id == dragged || drop?.id == item.id) 0f else 1f
                            val scale = if (highlighted) 1.12f else 1f
                            scaleX = scale
                            scaleY = scale
                            // Neighbours swing the other way; large elements less.
                            val direction = if (item.id % 2 == 0L) 1f else -1f
                            rotationZ = wiggle.value * direction / maxOf(item.cell.w, item.cell.h)
                        },
                ) {
                    when (item) {
                        is AppItem -> apps[item.appKey]?.let { HomeAppIcon(it, DragSource.Item(item), controller, showLabel = true) }
                        is FolderItem -> FolderCell(item, apps, controller)
                        is WidgetItem -> WidgetCell(item, workspace, cellW, cellH, controller)
                        is ShortcutItem -> ShortcutCell(item, shortcuts[item.shortcutKey], controller)
                    }
                    if (controller.editMode) {
                        when (item) {
                            is AppItem -> RemoveBadge(
                                onClick = { controller.repository.removeApp(item.appKey) },
                                modifier = Modifier.align(Alignment.TopCenter).offset(x = -iconSize / 2 + 2.dp, y = 0.dp),
                            )
                            is ShortcutItem -> RemoveBadge(
                                onClick = { controller.repository.removeItem(item.id) },
                                modifier = Modifier.align(Alignment.TopCenter).offset(x = -iconSize / 2 + 2.dp, y = 0.dp),
                            )
                            is FolderItem -> RemoveBadge(
                                onClick = { controller.repository.removeItem(item.id) },
                                modifier = if (item.style.isTile) Modifier.align(Alignment.TopStart)
                                else Modifier.align(Alignment.TopCenter).offset(x = -iconSize / 2 + 2.dp, y = 0.dp),
                            )
                            is WidgetItem -> RemoveBadge(
                                onClick = { controller.repository.removeItem(item.id) },
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                        }
                    }
                }
            }
        } }
    }
}

/** An app icon with label; tap opens, long press shows the menu, long press and move drags it. */
@Composable
private fun HomeAppIcon(app: AppTarget, source: DragSource, controller: HomeController, showLabel: Boolean) {
    val bounds = remember { ScreenBounds() }
    var menu by remember(controller.menuEpoch) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val haptics = LocalHapticFeedback.current
    val iconSize = LocalIconSize.current
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
                        onDragStart = { pointer, grab, pointerId ->
                            menu = false
                            controller.startDrag(source, pointer, grab, size, pointerId, iconSize)
                        },
                        onDown = controller::itemDown,
                        onRelease = controller::itemReleased,
                        editing = { controller.editMode },
                    ),
                )
                .padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                AppIconImage(app, iconSize, Modifier.onGloballyPositioned(bounds::update))
                if (notificationCount(app) > 0) NotificationDot(10.dp, Modifier.align(Alignment.TopEnd))
            }
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
 * A folder, as a classic Pixel folder (one cell, a tap opens it) or as a tile in the style of Nothing
 * OS whose app icons open directly while the space around them opens the folder. A name of only
 * spaces hides the name, and the tile takes its room.
 */
@Composable
private fun FolderCell(folder: FolderItem, apps: Map<String, AppTarget>, controller: HomeController) {
    var menu by remember(controller.menuEpoch) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val haptics = LocalHapticFeedback.current
    val iconSize = LocalIconSize.current
    val members = folder.apps.mapNotNull(apps::get)
    val open = { controller.openFolderId = folder.id }
    Column(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { size = it.size }
            .homeItemGestures(
                key = folder.id,
                callbacks = HomeItemGestureCallbacks(
                    // Tiles: the icons inside take taps themselves; classic folders open on a tap.
                    onTap = if (folder.style.isTile) null else open,
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        controller.itemHeld()
                        menu = true
                    },
                    onDragStart = { pointer, grab, pointerId ->
                        menu = false
                        controller.startDrag(DragSource.Item(folder), pointer, grab, size, pointerId, iconSize)
                    },
                    onDown = controller::itemDown,
                    onRelease = controller::itemReleased,
                    editing = { controller.editMode },
                    onEditTap = open,
                ),
            )
            .padding(if (folder.style.isTile) CELL_PADDING else 0.dp)
            .padding(top = if (folder.style.isTile) 0.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (folder.style.isTile) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(if (folder.style == FolderStyle.Small) 20.dp else 28.dp))
                    .background(folderFill())
                    .clickable(onClick = open)
                    .padding(if (folder.style == FolderStyle.Small) 3.dp else 6.dp),
            ) {
                FolderGrid(members, folder.style, folder.playful, folder.id) { app, bounds -> controller.actions.launchApp(app, bounds) }
            }
        } else {
            ClassicFolderIcon(members, iconSize)
        }
        if (folder.showsName) {
            Spacer(Modifier.height(if (folder.style.isTile) 4.dp else 6.dp))
            IconLabel(folder.name.ifEmpty { stringResource(R.string.folder_default_name) })
        }
        FolderMenu(folder, menu, onDismiss = { menu = false }, controller)
    }
}

/** A folder as on Pixel phones: a round plate with the first four apps in miniature. */
@Composable
private fun ClassicFolderIcon(apps: List<AppTarget>, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(folderFill()),
        contentAlignment = Alignment.Center,
    ) {
        val mini = size * 0.34f
        Column(verticalArrangement = Arrangement.spacedBy(size * 0.05f)) {
            apps.take(4).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(size * 0.05f)) {
                    row.forEach { app -> key(app.key) { AppIconImage(app, mini) } }
                    if (row.size == 1) Spacer(Modifier.size(mini))
                }
            }
        }
    }
}

/**
 * The apps of a tile in [style]'s rows and columns; each icon opens its app. [playful] scatters them a
 * little: shifted rows, a nudge and a tilt per icon, the same every time for the same folder.
 */
@Composable
private fun FolderGrid(
    apps: List<AppTarget>,
    style: FolderStyle,
    playful: Boolean,
    seed: Long,
    onOpenApp: (AppTarget, Rect?) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val slot = minOf(maxWidth / style.appColumns, maxHeight / style.appRows)
        val icon = minOf(slot - if (style == FolderStyle.Small) 4.dp else 6.dp, LocalIconSize.current)
        Column {
            for (row in 0 until style.appRows) {
                Row {
                    for (col in 0 until style.appColumns) {
                        Box(Modifier.size(slot), contentAlignment = Alignment.Center) {
                            apps.getOrNull(row * style.appColumns + col)?.let { app ->
                                key(app.key) {
                                    val bounds = remember { ScreenBounds() }
                                    val scatter = remember(playful, seed, app.key, row) { if (playful) Scatter.of(seed, app.key, row) else Scatter.None }
                                    Box(
                                        Modifier.graphicsLayer {
                                            translationX = scatter.dx * slot.toPx()
                                            translationY = scatter.dy * slot.toPx()
                                            rotationZ = scatter.rotation
                                            scaleX = scatter.scale
                                            scaleY = scatter.scale
                                        },
                                    ) {
                                        AppIconImage(
                                            app, icon,
                                            Modifier
                                                .onGloballyPositioned(bounds::update)
                                                .clip(CircleShape)
                                                .clickable { onOpenApp(app, bounds.rect) },
                                        )
                                        if (notificationCount(app) > 0) NotificationDot(7.dp, Modifier.align(Alignment.TopEnd))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How far one icon of a playful tile leaves its place, in slots, degrees and size. */
private class Scatter(val dx: Float, val dy: Float, val rotation: Float, val scale: Float) {
    companion object {
        val None = Scatter(0f, 0f, 0f, 1f)

        fun of(seed: Long, appKey: String, row: Int): Scatter {
            val random = Random(seed * 31 + appKey.hashCode())
            // Every other row sits a little to the right, like stones in a wall.
            val stagger = if (row % 2 == 1) 0.14f else -0.06f
            return Scatter(
                dx = stagger + (random.nextFloat() - 0.5f) * 0.22f,
                dy = (random.nextFloat() - 0.5f) * 0.24f,
                rotation = (random.nextFloat() - 0.5f) * 22f,
                scale = 0.86f + random.nextFloat() * 0.2f,
            )
        }
    }
}

/** Icon size on the pages: as on the dock, smaller when the grid has many columns. */
private val LocalIconSize = staticCompositionLocalOf { ICON_SIZE }

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
    val iconSize = LocalIconSize.current
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
                    onDragStart = { pointer, grab, pointerId ->
                        menu = false
                        controller.startDrag(DragSource.Item(item), pointer, grab, size, pointerId, iconSize)
                    },
                    onDown = controller::itemDown,
                    onRelease = controller::itemReleased,
                    editing = { controller.editMode },
                    // In edit mode the widget itself gets no touches; a tap offers its sizes.
                    onEditTap = { menu = true },
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
                if (!controller.editMode) {
                    MenuItem(R.string.menu_edit_home, R.drawable.ic_edit) {
                        menu = false
                        controller.startEditing()
                    }
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

/**
 * Page dots plus the App Library glyph. Touching them and sliding along jumps between pages, as on iOS;
 * a tap jumps to the page under the finger, or in edit mode ([onEditTap]) opens the page overview.
 */
@Composable
private fun PageIndicator(homePages: Int, pager: PagerState, onEditTap: (() -> Unit)?) {
    val currentOnEditTap by rememberUpdatedState(onEditTap)
    val color = wallpaperTextColor()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val current = pager.currentPage
    val count = homePages + 1
    var scrubbing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(INDICATOR_HEIGHT), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = if (scrubbing) 0.18f else 0f))
                .pointerInput(count) {
                    val slot = DOT_SLOT.toPx()
                    val start = INDICATOR_PADDING.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val editTap = currentOnEditTap
                        if (editTap != null) {
                            // Edit mode: a tap shows all pages, to remove whole ones.
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                editTap()
                            }
                            return@awaitEachGesture
                        }
                        scrubbing = true
                        var shown = pager.currentPage
                        fun select(x: Float) {
                            val page = floor((x - start) / slot).toInt().coerceIn(0, count - 1)
                            if (page == shown) return
                            shown = page
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch { pager.scrollToPage(page) }
                        }
                        select(down.position.x)
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                select(change.position.x)
                            }
                        } finally {
                            scrubbing = false
                        }
                    }
                }
                .padding(horizontal = INDICATOR_PADDING, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(homePages) { page ->
                Box(Modifier.width(DOT_SLOT), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = if (page == current) 0.95f else 0.35f)),
                    )
                }
            }
            // The App Library, drawn as a tiny grid like on iOS.
            Box(Modifier.width(DOT_SLOT), contentAlignment = Alignment.Center) {
                Column(Modifier.size(7.dp), verticalArrangement = Arrangement.SpaceBetween) {
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
    }
}

private val INDICATOR_HEIGHT = 23.dp
private val INDICATOR_PADDING = 8.dp
private val DOT_SLOT = 15.dp

/** 30 % lower than the first version's 32 dp. */
private val SEARCH_BUTTON_HEIGHT = 22.dp

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
                .height(SEARCH_BUTTON_HEIGHT)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(11.dp), tint = color)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.search_button), color = color, fontSize = 11.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun Dock(workspace: Workspace, apps: Map<String, AppTarget>, controller: HomeController, suggestion: AppTarget?) {
    val tint = if (LocalLightWallpaper.current) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.16f)
    val dragging = controller.drag
    val dropIndex = (dragging?.target as? DropTarget.Dock)?.index
    val wiggle = wiggleAngle(controller.wiggling)
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
        workspace.dock.forEachIndexed { index, appKey ->
            val app = apps[appKey] ?: return@forEachIndexed
            key(appKey) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .graphicsLayer {
                            alpha = if ((dragging?.source as? DragSource.Dock)?.appKey == appKey) 0.25f else 1f
                            rotationZ = if (index % 2 == 0) wiggle.value else -wiggle.value
                        },
                ) {
                    HomeAppIcon(app, DragSource.Dock(appKey), controller, showLabel = false)
                    if (controller.editMode) {
                        RemoveBadge(
                            onClick = { controller.repository.removeApp(appKey) },
                            modifier = Modifier.align(Alignment.TopCenter).offset(x = -ICON_SIZE / 2 + 2.dp, y = 0.dp),
                        )
                    }
                }
            }
        }
        // A free place shows the app most likely needed now, like the suggestions of the Pixel Launcher.
        if (suggestion != null && !controller.editMode && workspace.dock.size < Workspace.DOCK_SIZE) {
            key("suggestion") {
                Box(Modifier.weight(1f).height(64.dp)) { DockSuggestion(suggestion, workspace, controller) }
            }
        }
    }
}

/** The suggested app in the dock: a ring marks it; long press pins it or turns suggestions off. */
@Composable
private fun DockSuggestion(app: AppTarget, workspace: Workspace, controller: HomeController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val bounds = remember { ScreenBounds() }
    var menu by remember(controller.menuEpoch) { mutableStateOf(false) }
    val ring = wallpaperTextColor().copy(alpha = 0.55f)
    Box(
        Modifier
            .fillMaxSize()
            .homeItemGestures(
                key = "suggestion-${app.key}",
                callbacks = HomeItemGestureCallbacks(
                    onTap = { controller.actions.launchApp(app, bounds.rect) },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        controller.itemHeld()
                        menu = true
                    },
                    onDragStart = { _, _, _ -> },
                    onDown = controller::itemDown,
                    onRelease = controller::itemReleased,
                ),
            )
            .padding(top = 6.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.size(ICON_SIZE).border(1.5.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppIconImage(app, ICON_SIZE - 10.dp, Modifier.onGloballyPositioned(bounds::update))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(18.dp)) {
            MenuItem(R.string.dock_suggestion_pin, R.drawable.ic_pin) {
                menu = false
                controller.repository.toDock(app.key, workspace.dock.size)
            }
            MenuItem(R.string.dock_suggestion_off, R.drawable.ic_close) {
                menu = false
                scope.launch { context.launcherApp.settings.setDockSuggestion(false) }
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
        AppMenuExtras(app, controller, onDismiss, bounds)
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
        MenuItem(R.string.menu_edit_home, R.drawable.ic_edit) {
            onDismiss()
            controller.startEditing()
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
        // How the folder looks: a Pixel folder or a tile of one, three or four cells.
        listOf(
            FolderStyle.Classic to R.string.folder_style_classic,
            FolderStyle.Small to R.string.folder_style_small,
            FolderStyle.Wide to R.string.folder_style_wide,
            FolderStyle.Large to R.string.folder_style_large,
        ).forEach { (style, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { Icon(painterResource(if (style.isTile) R.drawable.ic_widget else R.drawable.ic_folder), null) },
                trailingIcon = if (folder.style == style) ({ Icon(painterResource(R.drawable.ic_check), null) }) else null,
                onClick = {
                    onDismiss()
                    controller.repository.setFolderStyle(folder.id, style)
                },
            )
        }
        if (folder.style.isTile) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folder_playful)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_star), null) },
                trailingIcon = if (folder.playful) ({ Icon(painterResource(R.drawable.ic_check), null) }) else null,
                onClick = {
                    onDismiss()
                    controller.repository.setFolderPlayful(folder.id, !folder.playful)
                },
            )
        }
        HorizontalDivider()
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
        MenuItem(R.string.menu_edit_home, R.drawable.ic_edit) {
            onDismiss()
            controller.startEditing()
        }
    }
}

/**
 * What the Pixel Launcher shows when an app is held: its notifications, its shortcuts (each can go
 * on the home screen), its widgets and its app timer. Followed by the launcher's own entries.
 */
@Composable
private fun AppMenuExtras(app: AppTarget, controller: HomeController, onDismiss: () -> Unit, bounds: ScreenBounds?) {
    val launcher = LocalContext.current.launcherApp
    val byApp by launcher.notifications.byApp.collectAsStateWithLifecycle()
    val notifications = byApp[Notifications.keyOf(app.packageName, app.user)].orEmpty().take(3)
    notifications.forEach { notification ->
        DropdownMenuItem(
            text = {
                Column {
                    Text(notification.title.ifBlank { app.label }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (notification.text.isNotBlank()) {
                        Text(notification.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            leadingIcon = { AppIconImage(app, 24.dp) },
            onClick = {
                onDismiss()
                launcher.notifications.open(notification)
            },
        )
    }
    if (notifications.isNotEmpty()) HorizontalDivider()

    val shortcuts = remember(app.key) { controller.shortcutsOf(app).take(4) }
    shortcuts.forEach { shortcut ->
        val label = (shortcut.info.shortLabel ?: shortcut.info.longLabel)?.toString().orEmpty()
        DropdownMenuItem(
            text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { ResultIcon(SearchItem(shortcut.key, SourceKind.Shortcut, label, payload = shortcut), 24.dp) },
            trailingIcon = {
                IconButton(onClick = {
                    onDismiss()
                    controller.repository.addShortcut(shortcut.key, label, controller.currentHomePage)
                }) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add_shortcut_title), Modifier.size(20.dp)) }
            },
            onClick = {
                onDismiss()
                controller.actions.launchShortcut(shortcut, bounds?.rect)
            },
        )
    }
    if (shortcuts.isNotEmpty()) HorizontalDivider()

    val hasWidgets by produceState(false, app.key) {
        value = withContext(Dispatchers.IO) {
            launcher.widgets.providers().any { it.provider.packageName == app.packageName && it.profile == app.user }
        }
    }
    if (hasWidgets) {
        MenuItem(R.string.menu_widgets, R.drawable.ic_widget) {
            onDismiss()
            controller.openWidgetPicker(app.packageName)
        }
    }
    val hasTimer = remember(app.key) { controller.actions.appTimerIntent(app) != null }
    if (hasTimer) {
        MenuItem(R.string.menu_app_timer, R.drawable.ic_history) {
            onDismiss()
            controller.actions.openAppTimer(app)
        }
    }
    if (controller.actions.canOpenAsBubble(app)) {
        MenuItem(R.string.menu_bubble, R.drawable.ic_bubble) {
            onDismiss()
            controller.actions.openAsBubble(app)
        }
    }
}

/** How many notifications [app] has right now (0 without notification access). */
@Composable
private fun notificationCount(app: AppTarget): Int {
    val byApp by LocalContext.current.launcherApp.notifications.byApp.collectAsStateWithLifecycle()
    return byApp[Notifications.keyOf(app.packageName, app.user)]?.size ?: 0
}

/** The dot of an app with notifications, as on Pixel phones. */
@Composable
private fun NotificationDot(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .padding(1.5.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** A pinned shortcut: its icon with the app's icon in the corner, like on Pixel phones. */
@Composable
fun ShortcutIcon(item: ShortcutItem, target: ShortcutTarget?, app: AppTarget?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        if (target != null) {
            ResultIcon(SearchItem(target.key, SourceKind.Shortcut, item.label, payload = target), size)
        } else {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.28f)).background(Color.White.copy(alpha = 0.25f)))
        }
        app?.let { AppIconImage(it, size * 0.4f, Modifier.align(Alignment.BottomEnd)) }
    }
}

/** A pinned shortcut on a page: tap opens it, long press offers removal, long press and move drags. */
@Composable
private fun ShortcutCell(item: ShortcutItem, target: ShortcutTarget?, controller: HomeController) {
    val context = LocalContext.current
    val launcher = context.launcherApp
    val bounds = remember { ScreenBounds() }
    var menu by remember(controller.menuEpoch) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val haptics = LocalHapticFeedback.current
    val iconSize = LocalIconSize.current
    val app = remember(item.shortcutKey, target) { launcher.apps.appOf(item.userSerial, item.packageName) }
    val label = target?.info?.shortLabel?.toString()?.ifBlank { null } ?: item.label
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { size = it.size }
                .homeItemGestures(
                    key = item.id,
                    callbacks = HomeItemGestureCallbacks(
                        onTap = {
                            if (target != null) controller.actions.launchShortcut(target, bounds.rect)
                            else Toast.makeText(context, R.string.shortcut_unavailable, Toast.LENGTH_SHORT).show()
                        },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            controller.itemHeld()
                            menu = true
                        },
                        onDragStart = { pointer, grab, pointerId ->
                            menu = false
                            controller.startDrag(DragSource.Item(item), pointer, grab, size, pointerId, iconSize)
                        },
                        onDown = controller::itemDown,
                        onRelease = controller::itemReleased,
                        editing = { controller.editMode },
                    ),
                )
                .padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShortcutIcon(item, target, app, iconSize, Modifier.onGloballyPositioned(bounds::update))
            Spacer(Modifier.height(6.dp))
            IconLabel(label)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(18.dp)) {
            MenuItem(R.string.menu_remove_from_home, R.drawable.ic_minus) {
                menu = false
                controller.repository.removeItem(item.id)
            }
            if (app != null) {
                MenuItem(R.string.menu_app_info, R.drawable.ic_info) {
                    menu = false
                    controller.actions.appInfo(app)
                }
            }
            MenuItem(R.string.menu_edit_home, R.drawable.ic_edit) {
                menu = false
                controller.startEditing()
            }
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
                overflow = group.apps.size > LIBRARY_SLOTS,
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
        AppMenuExtras(app, controller, onDismiss, null)
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
