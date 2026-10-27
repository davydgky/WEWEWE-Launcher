package io.github.davydgky.launcher.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import android.content.res.Resources
import kotlin.math.ceil

/** Size options of a widget in grid cells. */
data class WidgetSpan(val w: Int, val h: Int)

/** Hosts app widgets on the home screen. One instance per process; listens while the home screen shows. */
class WidgetHost(private val context: Context) {

    val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, HOST_ID)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    fun startListening() = runCatching { host.startListening() }

    fun stopListening() = runCatching { host.stopListening() }

    /** All widgets the user can add, from every visible profile. */
    fun providers(): List<AppWidgetProviderInfo> = launcherApps.profiles.flatMap { user ->
        runCatching { manager.getInstalledProvidersForProfile(user) }.getOrDefault(emptyList())
    }

    fun allocateId(): Int = host.allocateAppWidgetId()

    fun deleteId(id: Int) = runCatching { host.deleteAppWidgetId(id) }

    /** Binds without asking if the user allowed this launcher to add widgets before. */
    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo): Boolean =
        runCatching { manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) }.getOrDefault(false)

    fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)

    fun createView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        host.createView(context, id, info)

    /** Widget ids this host owns that no home screen element uses any more. */
    fun deleteUnused(used: Set<Int>) {
        host.appWidgetIds.filter { it !in used }.forEach { deleteId(it) }
    }

    /** Whether the widget wants its configuration screen before it is placed. */
    fun needsConfiguration(info: AppWidgetProviderInfo): Boolean =
        info.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0

    fun startConfiguration(activity: Activity, id: Int, requestCode: Int) {
        host.startAppWidgetConfigureActivityForResult(activity, id, 0, requestCode, null)
    }

    companion object {
        private const val HOST_ID = 1024

        /** Cells needed for [dp] when one cell is [cellDp] wide or high. */
        fun cellsFor(dp: Int, cellDp: Float, max: Int): Int = ceil(dp / cellDp).toInt().coerceIn(1, max)

        /** The size the widget asks for. */
        fun defaultSpan(info: AppWidgetProviderInfo, cellW: Float, cellH: Float, columns: Int, rows: Int): WidgetSpan {
            val w = if (info.targetCellWidth > 0) info.targetCellWidth else cellsFor(info.minWidth.pxToDp(), cellW, columns)
            val h = if (info.targetCellHeight > 0) info.targetCellHeight else cellsFor(info.minHeight.pxToDp(), cellH, rows)
            return WidgetSpan(w.coerceIn(1, columns), h.coerceIn(1, rows))
        }

        /** Sizes offered in the widget's menu: its own size and a few common ones it allows. */
        fun sizeOptions(info: AppWidgetProviderInfo, cellW: Float, cellH: Float, columns: Int, rows: Int): List<WidgetSpan> {
            val default = defaultSpan(info, cellW, cellH, columns, rows)
            val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
            val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
            val minW = if (horizontal) cellsFor(info.minResizeWidth.pxToDp().takeIf { it > 0 } ?: info.minWidth.pxToDp(), cellW, columns) else default.w
            val minH = if (vertical) cellsFor(info.minResizeHeight.pxToDp().takeIf { it > 0 } ?: info.minHeight.pxToDp(), cellH, rows) else default.h
            val maxW = if (!horizontal) default.w else if (info.maxResizeWidth > 0) cellsFor(info.maxResizeWidth.pxToDp(), cellW, columns) else columns
            val maxH = if (!vertical) default.h else if (info.maxResizeHeight > 0) cellsFor(info.maxResizeHeight.pxToDp(), cellH, rows) else rows
            val candidates = listOf(
                default,
                WidgetSpan(minW, minH),
                WidgetSpan(2, 2),
                WidgetSpan(columns, 2),
                WidgetSpan(columns, 4),
                WidgetSpan(columns, minOf(rows, 3)),
            )
            return candidates
                .filter { it.w in minOf(minW, default.w)..maxOf(maxW, default.w) && it.h in minOf(minH, default.h)..maxOf(maxH, default.h) }
                .map { WidgetSpan(it.w.coerceIn(1, columns), it.h.coerceIn(1, rows)) }
                .distinct()
                .sortedWith(compareBy({ it.w * it.h }, { it.w }))
        }

        // Provider sizes are stored in pixels of the device.
        private fun Int.pxToDp(): Int = (this / Resources.getSystem().displayMetrics.density).toInt()
    }
}
