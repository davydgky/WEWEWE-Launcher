package io.github.davydgky.launcher.ai

import android.content.Context
import android.util.Log
import io.github.davydgky.launcher.apps.AppsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What each installed app is for, in the words people search with ("bank", "musik", "wetter",
 * "tickets"), and a category for apps that declare none. Gemini Nano writes it once per app while the
 * home screen is in use, a few seconds apart and only when it has nothing else to do; it is kept in a
 * file on the device. Without Gemini Nano nothing happens.
 */
class AppKnowledge(
    private val context: Context,
    private val nano: NanoAnswers,
    private val apps: AppsRepository,
    private val scope: CoroutineScope,
) {
    /** [category] is one of [CATEGORIES] or null; [keywords] are lower case. */
    class Info(val category: String?, val keywords: List<String>)

    private val file = File(context.filesDir, "app_knowledge.json")
    private val state = MutableStateFlow<Map<String, Info>>(emptyMap())
    /** By package name. */
    val known: StateFlow<Map<String, Info>> = state.asStateFlow()

    private var loaded = false
    private var worker: Job? = null

    fun start() {
        scope.launch {
            state.value = withContext(Dispatchers.IO) { runCatching { read() }.getOrDefault(emptyMap()) }
            loaded = true
        }
    }

    /** The home screen is in use: describe the apps not described yet, one at a time. */
    fun resume() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            delay(START_DELAY_MS)
            var misses = 0
            while (true) {
                if (!loaded) {
                    delay(1000)
                    continue
                }
                val missing = apps.apps.value
                    .distinctBy { it.packageName }
                    .firstOrNull { it.packageName !in state.value && it.packageName != context.packageName }
                    ?: break
                nano.prepare()
                if (nano.availability.value == NanoAnswers.Availability.Unsupported) break
                val reply = if (nano.isReady) {
                    nano.ask(INSTRUCTION, "${missing.label} (${missing.packageName})", maxTokens = 90, background = true)
                } else null
                if (reply == null) {
                    // Busy with the search, still loading, or out of quota: try again later, not forever.
                    if (++misses >= MAX_MISSES) break
                    delay(RETRY_MS)
                    continue
                }
                misses = 0
                state.value = state.value + (missing.packageName to parse(reply))
                withContext(Dispatchers.IO) { runCatching { write(state.value) }.onFailure { Log.w(TAG, "Cannot save", it) } }
                delay(BETWEEN_MS)
            }
        }
    }

    fun pause() {
        worker?.cancel()
    }

    private fun parse(reply: String): Info {
        val category = Regex("(?i)kategorie\\s*:\\s*([a-z]+)").find(reply)?.groupValues?.get(1)?.lowercase()?.takeIf { it in CATEGORIES }
        val keywords = Regex("(?i)begriffe\\s*:\\s*(.+)").find(reply)?.groupValues?.get(1).orEmpty()
            .split(',', ';')
            .map { it.trim().trim('.', '"', '*').lowercase() }
            .filter { it.length in 2..30 }
            .distinct()
            .take(MAX_KEYWORDS)
        return Info(category, keywords)
    }

    private fun read(): Map<String, Info> {
        if (!file.exists()) return emptyMap()
        val json = JSONObject(file.readText())
        return json.keys().asSequence().associateWith { pkg ->
            val entry = json.getJSONObject(pkg)
            val words = entry.optJSONArray("keywords") ?: JSONArray()
            Info(entry.optString("category").ifEmpty { null }, (0 until words.length()).map { words.getString(it) })
        }
    }

    private fun write(map: Map<String, Info>) {
        val json = JSONObject()
        map.forEach { (pkg, info) ->
            json.put(pkg, JSONObject().put("category", info.category.orEmpty()).put("keywords", JSONArray(info.keywords)))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    companion object {
        private const val TAG = "AppKnowledge"
        private const val START_DELAY_MS = 4_000L
        private const val BETWEEN_MS = 2_500L
        private const val RETRY_MS = 15_000L
        private const val MAX_MISSES = 4
        private const val MAX_KEYWORDS = 10

        val CATEGORIES = setOf(
            "social", "productivity", "games", "photo", "entertainment", "music", "news", "travel",
            "utilities", "shopping", "finance", "health", "education", "food",
        )

        private val INSTRUCTION = """
            Du beschreibst Android-Apps für die Suche eines Startbildschirms. Eingabe: App-Name und Paketname.
            Antworte genau in zwei Zeilen, ohne weiteren Text:
            Kategorie: <genau eines von: ${CATEGORIES.joinToString(", ")}>
            Begriffe: <8 kurze Suchbegriffe auf Deutsch und Englisch, kommagetrennt, wofür man die App benutzt; nicht der App-Name>
        """.trimIndent()
    }
}
