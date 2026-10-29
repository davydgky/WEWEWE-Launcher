package io.github.davydgky.launcher.store

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.davydgky.launcher.ai.NanoAnswers
import io.github.davydgky.launcher.core.StoreApp
import io.github.davydgky.launcher.core.StoreApps
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Apps from the Play Store for the search, like the Pixel Launcher's store results: the one place
 * besides the updater that uses the network, and only while "Play Store results" is on. The search
 * words go to play.google.com as they would in a browser, nothing else (no account, no identifiers).
 * Without network, or if the store page cannot be read, Gemini Nano suggests well-known apps instead.
 */
class PlayStore(private val context: Context, private val nano: NanoAnswers) {

    private val results = LruCache<String, List<StoreApp>>(RESULT_CACHE)
    private val icons = LruCache<String, ImageBitmap>(ICON_CACHE)

    /** Up to [limit] apps for [query] that are not installed ([installed] package names). */
    suspend fun search(query: String, installed: Set<String>, limit: Int = 4): List<StoreApp> {
        val key = query.trim().lowercase()
        results.get(key)?.let { return it.filter { app -> app.packageName !in installed }.take(limit) }
        val online = withContext(Dispatchers.IO) { runCatching { fetch(key) }.onFailure { Log.i(TAG, "Play Store not reachable: ${it.message}") }.getOrNull() }
        val apps = online?.takeIf { it.isNotEmpty() } ?: suggest(key)
        if (online != null) results.put(key, apps)
        return apps.filter { it.packageName !in installed }.take(limit)
    }

    /** The icon of a store result, loaded once and kept in memory. */
    suspend fun icon(url: String): ImageBitmap? {
        icons.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                open(url).inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }?.also { icons.put(url, it) }
    }

    private fun fetch(query: String): List<StoreApp> {
        val locale = Locale.getDefault()
        val url = "https://play.google.com/store/search?q=${URLEncoder.encode(query, "UTF-8")}&c=apps" +
            "&hl=${locale.language}&gl=${locale.country.ifEmpty { "US" }}"
        val connection = open(url)
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            StoreApps.parse(html, limit = 8)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun suggest(query: String): List<StoreApp> {
        if (!nano.isReady) return emptyList()
        val reply = nano.ask(SUGGEST_INSTRUCTION, query, maxTokens = 90) ?: return emptyList()
        return StoreApps.parseSuggestions(reply)
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
    }

    private companion object {
        const val TAG = "PlayStore"
        const val TIMEOUT_MS = 4_000
        const val RESULT_CACHE = 40
        const val ICON_CACHE = 60
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36"

        val SUGGEST_INSTRUCTION = """
            Nenne bis zu drei bekannte, echte Android-Apps aus dem Google Play Store, die zur Suche passen.
            Eine pro Zeile im Format: Name | Paketname
            Nur Apps, deren Paketnamen du sicher kennst. Kein weiterer Text.
        """.trimIndent()
    }
}
