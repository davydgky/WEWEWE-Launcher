@file:OptIn(ExperimentalFoundationApi::class)

package io.github.davydgky.launcher.home

import android.appwidget.AppWidgetProviderInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.search.AppTarget
import io.github.davydgky.launcher.ui.AppIconImage
import io.github.davydgky.launcher.ui.ScreenBounds
import io.github.davydgky.launcher.ui.glass.LocalGlassColors
import io.github.davydgky.launcher.ui.glass.frostedBackdrop
import io.github.davydgky.launcher.ui.glass.glass
import io.github.davydgky.launcher.ui.glass.glassColors
import io.github.davydgky.launcher.widgets.WidgetHost
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The element under the finger while it is dragged. */
@Composable
fun DragOverlay(controller: HomeController, apps: Map<String, AppTarget>) {
    val drag = controller.drag ?: return
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset((drag.pointer.x - drag.grab.x).roundToInt(), (drag.pointer.y - drag.grab.y).roundToInt()) }
            .size(with(density) { drag.size.width.toDp() }, with(density) { drag.size.height.toDp() })
            .graphicsLayer {
                scaleX = 1.08f
                scaleY = 1.08f
                alpha = 0.92f
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        when (val source = drag.source) {
            is DragSource.Dock -> apps[source.appKey]?.let { AppIconImage(it, 58.dp, Modifier.padding(top = 6.dp)) }
            is DragSource.Item -> when (val item = source.item) {
                is AppItem -> apps[item.appKey]?.let { AppIconImage(it, 58.dp, Modifier.padding(top = 6.dp)) }
                is FolderItem, is WidgetItem -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(glassColors().surface),
                )
            }
        }
    }
}

/** Dims and blurs the home screen behind a panel; a tap outside closes it. */
@Composable
private fun PanelScrim(backdrop: Backdrop, onDismiss: () -> Unit, content: @Composable (Backdrop) -> Unit) {
    val colors = glassColors()
    val panelBackdrop = rememberLayerBackdrop()
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .frostedBackdrop(backdrop, colors, progress = { 1f }, exportedBackdrop = panelBackdrop)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalGlassColors provides colors, LocalContentColor provides colors.content) {
            content(panelBackdrop)
        }
    }
}

/** An opened folder: its name (editable) and all its apps. */
@Composable
fun FolderOverlay(controller: HomeController, workspace: Workspace, apps: Map<String, AppTarget>, backdrop: Backdrop) {
    val folderId = controller.openFolderId
    val folder = folderId?.let { workspace.item(it) as? FolderItem }
    AnimatedVisibility(visible = folder != null, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
        val shown = folder ?: return@AnimatedVisibility
        val close = { controller.openFolderId = null }
        PanelScrim(backdrop, onDismiss = close) { panelBackdrop ->
            val colors = LocalGlassColors.current
            var name by remember(shown.id) { mutableStateOf(shown.name) }
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(0.88f), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        controller.repository.renameFolder(shown.id, it)
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.content, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(colors.content),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (name.isEmpty()) Text(stringResource(R.string.folder_default_name), color = colors.secondaryContent, fontSize = 26.sp)
                            inner()
                        }
                    },
                )
                AppGridPanel(
                    apps = shown.apps.mapNotNull(apps::get),
                    backdrop = panelBackdrop,
                    controller = controller,
                    workspace = workspace,
                    folderId = shown.id,
                    onLaunched = close,
                )
            }
        }
    }
}

/** All apps of an App Library box. */
@Composable
fun CategoryOverlay(controller: HomeController, workspace: Workspace, backdrop: Backdrop) {
    val group = controller.openCategory
    AnimatedVisibility(visible = group != null, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
        val shown = group ?: return@AnimatedVisibility
        val close = { controller.openCategory = null }
        PanelScrim(backdrop, onDismiss = close) { panelBackdrop ->
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(0.88f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(shown.title),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
                AppGridPanel(shown.apps, panelBackdrop, controller, workspace, folderId = null, onLaunched = close)
            }
        }
    }
}

@Composable
private fun AppGridPanel(
    apps: List<AppTarget>,
    backdrop: Backdrop,
    controller: HomeController,
    workspace: Workspace,
    folderId: Long?,
    onLaunched: () -> Unit,
) {
    val colors = LocalGlassColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .glass(backdrop, RoundedRectangle(34.dp), colors)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        apps.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    var menu by remember { mutableStateOf(false) }
                    val bounds = remember { ScreenBounds() }
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = {
                                    controller.actions.launchApp(app, bounds.rect)
                                    onLaunched()
                                },
                                onLongClick = { menu = true },
                            )
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIconImage(app, 56.dp, Modifier.onGloballyPositioned(bounds::update))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            app.label,
                            fontSize = 12.sp,
                            color = colors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        if (menu) LibraryAppMenu(app, workspace, onDismiss = { menu = false }, controller, folderId)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** All widgets of all apps, with previews; tapping one adds it to the current page. */
@Composable
fun WidgetPicker(controller: HomeController, apps: Map<String, AppTarget>, backdrop: Backdrop) {
    AnimatedVisibility(visible = controller.widgetPickerOpen, enter = fadeIn(), exit = fadeOut()) {
        val context = LocalContext.current
        val app = context.launcherApp
        val close = { controller.widgetPickerOpen = false }
        val providers by produceState<List<Pair<String, List<AppWidgetProviderInfo>>>?>(null) {
            value = withContext(Dispatchers.IO) {
                val labels = apps.values.associate { it.packageName to it.label }
                app.widgets.providers()
                    .groupBy { it.provider.packageName }
                    .map { (pkg, list) -> (labels[pkg] ?: appLabel(context.packageManager, pkg)) to list }
                    .sortedBy { it.first.lowercase() }
            }
        }
        PanelScrim(backdrop, onDismiss = close) { panelBackdrop ->
            val colors = LocalGlassColors.current
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .glass(panelBackdrop, RoundedRectangle(34.dp), colors)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.widget_picker_title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel)) }
                }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val list = providers.orEmpty()
                    list.forEach { (label, widgets) ->
                        item(key = "app-$label") {
                            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp, start = 6.dp))
                        }
                        items(widgets, key = { "${it.provider.flattenToString()}-${it.profile.hashCode()}" }) { info ->
                            WidgetPreview(info, controller)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetPreview(info: AppWidgetProviderInfo, controller: HomeController) {
    val context = LocalContext.current
    val colors = LocalGlassColors.current
    val density = context.resources.displayMetrics.densityDpi
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = info.loadPreviewImage(context, density) ?: info.loadIcon(context, density)
                context.launcherApp.icons.drawable(drawable)
            }.getOrNull()
        }
    }
    val span = WidgetHost.defaultSpan(info, controller.cellWidthDp, controller.cellHeightDp, controller.columns, controller.rows)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.fill)
            .clickable {
                controller.widgetPickerOpen = false
                controller.addWidget(info, span.w, span.h, controller.pager.currentPage.coerceAtMost(controller.homePages - 1))
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, null, Modifier.size(96.dp), contentScale = ContentScale.Fit) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(info.loadLabel(context.packageManager), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.widget_size, span.w, span.h), fontSize = 13.sp, color = colors.secondaryContent)
        }
    }
}

private fun appLabel(pm: PackageManager, pkg: String): String =
    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString() }
        .getOrDefault(pkg)
