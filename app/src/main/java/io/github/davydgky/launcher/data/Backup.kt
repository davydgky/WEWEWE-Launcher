package io.github.davydgky.launcher.data

import android.content.Context
import android.net.Uri
import io.github.davydgky.launcher.launcherApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A backup of the home screen arrangement and the settings, as one JSON file the user picks a place
 * for. Messages, the search index and crash reports are not part of it.
 */
object Backup {

    private const val FORMAT = "wewewe-backup"
    private const val VERSION = 1

    suspend fun save(context: Context, uri: Uri): Boolean {
        val app = context.launcherApp
        val workspace = app.workspace.exportText() ?: return false
        val json = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("workspace", JSONObject(workspace))
            .put("settings", toJson(app.settings.export()))
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toString(2).toByteArray()) }
            }.isSuccess
        }
    }

    suspend fun restore(context: Context, uri: Uri): Boolean {
        val app = context.launcherApp
        val text = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
        } ?: return false
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (json.optString("format") != FORMAT) return false
        json.optJSONObject("settings")?.let { app.settings.import(fromJson(it)) }
        val workspace = json.optJSONObject("workspace") ?: return true
        return app.workspace.restore(workspace.toString())
    }

    private fun toJson(values: Map<String, Any>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) ->
            put(
                key,
                when (value) {
                    is List<*> -> JSONArray(value)
                    is Map<*, *> -> JSONObject(value.mapKeys { it.key.toString() })
                    else -> value
                },
            )
        }
    }

    private fun fromJson(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key ->
        when (val value = json.get(key)) {
            is JSONArray -> (0 until value.length()).map { value.get(it) }
            is JSONObject -> value.keys().asSequence().associateWith { value.get(it) }
            else -> value
        }
    }
}
