package io.github.davydgky.launcher.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.davydgky.launcher.launcherApp

/**
 * Adds a widget the way Android wants it: allocate an id, ask the user once for permission to bind
 * widgets (unless already granted), open the widget's configuration screen if it has one, then place
 * it. Must be created in the activity's onCreate.
 */
class WidgetAdder(private val activity: ComponentActivity) {

    private val app = activity.launcherApp

    private class Pending(val id: Int, val info: AppWidgetProviderInfo, val w: Int, val h: Int, val page: Int)

    private var pending: Pending? = null

    private val bind = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val current = pending ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) configureOrPlace(current) else cancel(current)
    }

    fun add(info: AppWidgetProviderInfo, w: Int, h: Int, page: Int) {
        val request = Pending(app.widgets.allocateId(), info, w, h, page)
        pending = request
        if (app.widgets.bindIfAllowed(request.id, info)) {
            configureOrPlace(request)
        } else {
            bind.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, request.id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
            )
        }
    }

    /** Routes the result of a widget's configuration screen; true if it was one. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CONFIGURE) return false
        val current = pending ?: return true
        if (resultCode == Activity.RESULT_OK) place(current) else cancel(current)
        return true
    }

    private fun configureOrPlace(request: Pending) {
        if (!app.widgets.needsConfiguration(request.info)) {
            place(request)
            return
        }
        try {
            app.widgets.startConfiguration(activity, request.id, REQUEST_CONFIGURE)
        } catch (e: Exception) {
            // Some configuration screens are not exported; the widget then works with its defaults.
            Log.w(TAG, "Cannot configure ${request.info.provider}", e)
            place(request)
        }
    }

    private fun place(request: Pending) {
        pending = null
        app.workspace.addWidget(request.id, request.info.provider.flattenToString(), request.w, request.h, request.page)
    }

    private fun cancel(request: Pending) {
        pending = null
        app.widgets.deleteId(request.id)
    }

    private companion object {
        const val TAG = "WidgetAdder"
        const val REQUEST_CONFIGURE = 41
    }
}
