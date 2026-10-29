package io.github.davydgky.launcher.ai

import android.util.Log
import io.github.davydgky.launcher.core.MessageText
import io.github.davydgky.launcher.core.SmartAction
import io.github.davydgky.launcher.core.SmartActions
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
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

/** What Gemini Nano made of an input: an answer as it grows, or a request to the phone. */
sealed interface NanoReply {
    data class Text(val text: String) : NanoReply
    data class Command(val action: SmartAction) : NanoReply
}

/**
 * Gemini Nano on the device (ML Kit's Prompt API through Android's AICore), for everything in the
 * launcher that understands language: answers and commands in the search, words that describe apps,
 * summaries of notifications, names for folders. Nothing leaves the phone. Android only lets apps in
 * the foreground use it, within a battery quota per app; work that can wait gives way to the search
 * ([ask] with background = true never queues behind it). Devices without Gemini Nano for apps report
 * [Availability.Unsupported], and every feature quietly does without it.
 */
class NanoAnswers(private val scope: CoroutineScope) {

    enum class Availability { Unknown, Unsupported, Downloading, Ready }

    private val state = MutableStateFlow(Availability.Unknown)
    val availability: StateFlow<Availability> = state.asStateFlow()

    val isReady: Boolean get() = state.value == Availability.Ready

    private var model: GenerativeModel? = null
    private var preparing: Job? = null
    /** Gemini Nano works on one request at a time. */
    private val busy = Mutex()

    /**
     * Checks once whether Gemini Nano is there and fetches it if the system offers it for download
     * (AICore keeps one copy for all apps, so on a Pixel it is usually present already).
     */
    fun prepare() {
        if (state.value == Availability.Ready || state.value == Availability.Unsupported || preparing?.isActive == true) return
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano is not available", e)
                state.value = Availability.Unsupported
            }
        }
    }

    /**
     * The reply to what was typed into the search: an answer chunk by chunk, or once complete a request
     * to the phone ("ruf Mama an"). Nothing for plain searches (an app, a name, a single word).
     */
    fun reply(query: String, messages: List<MessageText> = emptyList()): Flow<NanoReply> = flow {
        val client = model ?: return@flow
        if (!isReady) return@flow
        busy.withLock {
            val request = generateContentRequest(SystemInstruction(searchInstruction() + messageContext(messages)), TextPart(query.trim())) {
                temperature = 0.2f
                topK = 16
                maxOutputTokens = MAX_ANSWER_TOKENS
            }
            val text = StringBuilder()
            client.generateContentStream(request).collect { response ->
                val chunk = response.candidates.firstOrNull()?.text ?: return@collect
                text.append(chunk)
                val shown = text.toString().trim()
                // A command is JSON: shown only once it is complete and understood.
                if (shown.startsWith("{") || shown.startsWith("`")) return@collect
                if (shown.isNotEmpty() && !NOTHING.startsWith(shown)) emit(NanoReply.Text(shown))
            }
            val full = text.toString().trim()
            if (full.startsWith("{") || full.startsWith("`")) SmartActions.parse(full)?.let { emit(NanoReply.Command(it)) }
        }
    }

    /** The whole reply to a short task, streamed as it grows (for things the user waits for). */
    fun stream(instruction: String, input: String, maxTokens: Int = 160): Flow<String> = flow {
        val client = model ?: return@flow
        if (!isReady) return@flow
        busy.withLock {
            val request = generateContentRequest(SystemInstruction(instruction), TextPart(input)) {
                temperature = 0.2f
                topK = 16
                maxOutputTokens = maxTokens
            }
            val text = StringBuilder()
            client.generateContentStream(request).collect { response ->
                response.candidates.firstOrNull()?.text?.let { chunk ->
                    text.append(chunk)
                    emit(text.toString().trim())
                }
            }
        }
    }

    /**
     * A short task done in one go. With [background], it runs only when nothing else is asking, so the
     * search never waits for it; null when Gemini Nano is busy, missing or out of quota.
     */
    suspend fun ask(instruction: String, input: String, maxTokens: Int = 64, background: Boolean = false): String? {
        val client = model ?: return null
        if (!isReady) return null
        if (background && !busy.tryLock()) return null
        if (!background) busy.lock()
        return try {
            val request = generateContentRequest(SystemInstruction(instruction), TextPart(input)) {
                temperature = 0.1f
                topK = 8
                maxOutputTokens = maxTokens
            }
            client.generateContent(request).candidates.firstOrNull()?.text?.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano could not answer", e)
            null
        } finally {
            busy.unlock()
        }
    }

    /** A short folder name for apps with these [labels] ("Musik", "Reisen"); null without Gemini Nano. */
    suspend fun folderName(labels: List<String>): String? {
        val reply = ask(FOLDER_INSTRUCTION, labels.joinToString(", "), maxTokens = 12) ?: return null
        val name = reply.lineSequence().firstOrNull().orEmpty().trim().trim('"', '„', '“', '.', '*').trim()
        return name.takeIf { it.length in 2..MAX_FOLDER_NAME && it.none { c -> c == '{' || c == ':' } }
    }

    /** The gist of an app's notifications, streamed: "Anna und Tom fragen nach dem Treffen um 8." */
    fun summarize(app: String, notifications: List<String>): Flow<String> =
        stream(SUMMARY_INSTRUCTION, "App: $app\n" + notifications.joinToString("\n") { "- $it" }, maxTokens = 120)

    fun close() {
        model?.close()
        model = null
    }

    /**
     * Messages from the phone that may answer the question, oldest first, for questions like "Wann muss
     * ich zum Bahnhof?". Only these few are given, never the whole history.
     */
    private fun messageContext(messages: List<MessageText>): String {
        if (messages.isEmpty()) return ""
        val format = DateTimeFormatter.ofPattern("EEE dd.MM. HH:mm", Locale.GERMAN)
        val zone = ZoneId.systemDefault()
        val lines = messages.joinToString("\n") { m ->
            val time = Instant.ofEpochMilli(m.time).atZone(zone).format(format)
            "- $time · ${m.app} · ${m.sender}: ${m.text.take(MAX_MESSAGE_CHARS).replace('\n', ' ')}"
        }
        return buildString {
            append("\n\nNachrichten auf diesem Handy, die zur Eingabe passen könnten (Zeit · App · Absender: Text):\n")
            append(lines)
            append("\n")
            append(MESSAGE_INSTRUCTION)
        }
    }

    /** The search instruction with today's date, so "tomorrow at 3" becomes a date. */
    private fun searchInstruction(): String {
        val now = LocalDateTime.now()
        val today = now.format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd, HH:mm", Locale.GERMAN))
        return """
            Du bist die Antwortzeile in der Suche eines Android-Startbildschirms, wie die KI-Antwort bei Google.
            Jetzt ist $today.
            1. Ist die Eingabe ein Auftrag an das Handy (anrufen, Nachricht oder SMS schreiben, E-Mail schreiben,
               navigieren oder Route, Termin oder Erinnerung eintragen, im Web suchen), antworte ausschließlich mit
               einer Zeile JSON ohne Erklärung, mit diesen Feldern:
               {"action":"call|sms|mail|navigate|event|web","who":"Name oder Nummer","text":"Nachrichtentext",
               "title":"Titel oder Betreff","place":"Ort","start":"YYYY-MM-DDTHH:MM","end":"YYYY-MM-DDTHH:MM"}
               Lass Felder weg, die nicht passen. Rechne Angaben wie "morgen 15 Uhr" in ein Datum um.
            2. Ist die Eingabe eine Frage oder ein Begriff, zu dem man eine Erklärung erwartet, antworte in der
               Sprache der Eingabe, sachlich und knapp: höchstens drei kurze Sätze, ohne Einleitung, ohne Rückfrage,
               ohne Markdown. Bei Fakten nenne die Zahl oder den Namen zuerst.
            3. Ist die Eingabe nur der Name einer App, einer Person, einer Datei oder ein einzelnes Wort ohne
               erkennbare Frage, oder weißt du es nicht sicher, antworte ausschließlich mit: -
        """.trimIndent()
    }

    private companion object {
        const val TAG = "NanoAnswers"
        const val MAX_ANSWER_TOKENS = 220
        const val NOTHING = "-"
        const val MAX_FOLDER_NAME = 24
        const val MAX_MESSAGE_CHARS = 300

        val MESSAGE_INSTRUCTION = """
            Beantworte Fragen zu Treffen, Terminen, Uhrzeiten, Orten, Paketen, Codes oder Absprachen anhand dieser
            Nachrichten: zuerst die Antwort, dann kurz woher (wer, wann). Erfinde nichts; steht es nicht darin,
            antworte wie sonst.
        """.trimIndent()

        val FOLDER_INSTRUCTION = """
            Gib einen kurzen Ordnernamen für einen Startbildschirm-Ordner mit diesen Apps: ein bis zwei Wörter auf
            Deutsch, wie "Musik", "Reisen", "Finanzen", "Soziale Netze". Antworte nur mit dem Namen.
        """.trimIndent()

        val SUMMARY_INSTRUCTION = """
            Fasse die Benachrichtigungen einer App in ein bis zwei kurzen Sätzen auf Deutsch zusammen: wer will was,
            was ist wichtig. Ohne Einleitung, ohne Aufzählung, ohne Markdown.
        """.trimIndent()
    }
}
