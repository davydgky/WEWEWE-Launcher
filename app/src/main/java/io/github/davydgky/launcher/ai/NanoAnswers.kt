package io.github.davydgky.launcher.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Short answers to whatever is typed into the search, from Gemini Nano on the device (ML Kit's Prompt
 * API through Android's AICore). Nothing leaves the phone. Devices without Gemini Nano for apps (most
 * phones except recent Pixels) report [Availability.Unsupported], and the search simply shows none.
 */
class NanoAnswers(private val scope: CoroutineScope) {

    enum class Availability { Unknown, Unsupported, Downloading, Ready }

    private val state = MutableStateFlow(Availability.Unknown)
    val availability: StateFlow<Availability> = state.asStateFlow()

    private var model: GenerativeModel? = null
    private var preparing: Job? = null
    /** Gemini Nano answers one request at a time; a newer input waits for the older to be cancelled. */
    private val busy = Mutex()

    /**
     * Checks once whether Gemini Nano is there and fetches it if the system offers it for download
     * (AICore keeps one copy for all apps, so on a Pixel it is usually present already).
     */
    fun prepare() {
        if (state.value == Availability.Ready || preparing?.isActive == true) return
        preparing = scope.launch {
            try {
                val client = model ?: Generation.getClient().also { model = it }
                when (client.checkStatus()) {
                    FeatureStatus.AVAILABLE -> state.value = Availability.Ready
                    FeatureStatus.UNAVAILABLE -> state.value = Availability.Unsupported
                    else -> {
                        state.value = Availability.Downloading
                        client.download().collect { status ->
                            when (status) {
                                is DownloadStatus.DownloadCompleted -> state.value = Availability.Ready
                                is DownloadStatus.DownloadFailed -> {
                                    Log.w(TAG, "Gemini Nano download failed", status.e)
                                    state.value = Availability.Unknown
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano is not available", e)
                state.value = Availability.Unsupported
            }
        }
    }

    /** The answer to [query] as it grows, chunk by chunk; empty when the model has nothing useful to say. */
    fun answer(query: String): Flow<String> = flow {
        val client = model ?: return@flow
        if (state.value != Availability.Ready) return@flow
        busy.withLock {
            val request = generateContentRequest(SystemInstruction(INSTRUCTION), TextPart(query.trim())) {
                temperature = 0.2f
                topK = 16
                maxOutputTokens = MAX_TOKENS
            }
            val text = StringBuilder()
            client.generateContentStream(request).collect { response ->
                val chunk = response.candidates.firstOrNull()?.text ?: return@collect
                text.append(chunk)
                val shown = text.toString().trim()
                // The model's way of saying "this is a search, not a question".
                if (shown.isNotEmpty() && !NOTHING.startsWith(shown)) emit(shown)
            }
        }
    }

    fun close() {
        model?.close()
        model = null
    }

    private companion object {
        const val TAG = "NanoAnswers"
        const val MAX_TOKENS = 220
        const val NOTHING = "-"

        val INSTRUCTION = """
            Du bist die Antwortzeile in der Suche eines Android-Startbildschirms, wie die KI-Antwort bei Google.
            Antworte in der Sprache der Eingabe, sachlich und knapp: höchstens drei kurze Sätze, ohne Einleitung,
            ohne Rückfrage, ohne Markdown. Bei Fakten nenne die Zahl oder den Namen zuerst.
            Ist die Eingabe nur der Name einer App, einer Person, einer Datei oder ein einzelnes Wort ohne erkennbare
            Frage, oder weißt du es nicht sicher, antworte ausschließlich mit: -
        """.trimIndent()
    }
}
