package io.github.davydgky.launcher.island

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.ui.SearchActivity

/** "WEWEWE search" in Quick Settings: opens the search over any app, with any launcher. */
class SearchTileService : TileService() {

    override fun onClick() {
        val intent = PendingIntent.getActivity(this, 0, SearchActivity.intent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startActivityAndCollapse(intent)
    }
}

/** A search bar for any home screen, the Pixel Launcher's too: a tap opens the WEWEWE search. */
class SearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(context, 0, SearchActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(context.packageName, R.layout.widget_search).apply { setOnClickPendingIntent(R.id.widget_search, open) }
        manager.updateAppWidget(ids, views)
    }

    companion object {
        /** Asks the home screen (any launcher that supports it) to place the widget. */
        fun requestPin(context: Context): Boolean {
            val manager = context.getSystemService(AppWidgetManager::class.java)
            if (!manager.isRequestPinAppWidgetSupported) return false
            return manager.requestPinAppWidget(ComponentName(context, SearchWidgetProvider::class.java), null, null)
        }
    }
}
