package io.github.davydgky.launcher.ai

import android.os.SystemClock
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * Checks whether Gemini Nano can run, and if it still has to be downloaded starts that when
     * [download] (only for what the user turned on: answers in the search).
     */
    fun prepare(download: Boolean = true) {
        if (state.value == Availability.Ready || state.value == Availability.Unsupported || preparing?.isActive == true) return
        // After a failed check, not again on every keystroke.
        if (SystemClock.elapsedRealtime() - lastFailure < RETRY_MS) return
        preparing = scope.launch {
            try {
                val client = model ?: Generation.getClient().also { model = it }
                when (client.checkStatus()) {
                    FeatureStatus.AVAILABLE -> state.value = Availability.Ready
                    FeatureStatus.UNAVAILABLE -> state.value = Availability.Unsupported
                    else -> if (!download) {
                        state.value = Availability.Unknown
                    } else {
                        state.value = Availability.Downloading
                        client.download().collect { status ->
                            when (status) {
                                is DownloadStatus.DownloadCompleted -> state.value = Availability.Ready
                                is DownloadStatus.DownloadFailed -> {
                                    Log.w(TAG, "Gemini Nano download failed", status.e)
                                    lastError = status.e.message
                                    lastFailure = SystemClock.elapsedRealtime()
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
                // AICore may still be starting (right after boot or an update): try again later.
                Log.w(TAG, "Gemini Nano is not available yet", e)
                lastError = e.message
                lastFailure = SystemClock.elapsedRealtime()
                state.value = Availability.Unknown
            }
        }
    }

    /** Why Gemini Nano could not be reached last time, for the search to show. */
    @Volatile var lastError: String? = null
        private set
    @Volatile private var lastFailure = 0L

    /** Waits up to [timeoutMs] for Gemini Nano to be ready, starting the check if needed. */
    suspend fun awaitReady(timeoutMs: Long = READY_WAIT_MS): Boolean {
        prepare()
        if (isReady) return true
        return withTimeoutOrNull(timeoutMs) {
            availability.first { it == Availability.Ready || it == Availability.Unsupported } == Availability.Ready
        } ?: false
    }

    /** Someone is typing: work that can wait stays out of the way for a while. */
    @Volatile private var interactiveUntil = 0L

    private fun interactive() {
        interactiveUntil = SystemClock.elapsedRealtime() + INTERACTIVE_MS
    }

    /**
     * The reply to what was typed into the search. A request to the phone ([command], "ruf Mama an")
     * comes back once complete as [NanoReply.Command]; anything else is answered, chunk by chunk, with
     * [messages] from the phone that may hold the answer. Errors are thrown, so the search can show them.
     */
    fun reply(
        query: String,
        messages: List<MessageText> = emptyList(),
        command: Boolean = false,
        /** Earlier questions and answers of the conversation, oldest first, for follow-ups ("und morgen?"). */
        history: List<Pair<String, String>> = emptyList(),
        /** [messages] are all recent ones, to be summed up ("fasse meine Nachrichten zusammen"). */
        overview: Boolean = false,
        /**
         * In a conversation (Enter): a command Nano did not understand is answered as a question instead
         * (a second run), and answers may be longer. False while typing.
         */
        answerUnknownCommands: Boolean = true,
    ): Flow<NanoReply> = flow {
        val client = model ?: return@flow
        if (!isReady) return@flow
        interactive()
        busy.withLock {
            // The conversation so far goes into the input itself, as a transcript ending with the new
            // message: small models follow that far better than history tucked into the instruction.
            val input = transcript(history, query)
            if (command) {
                val json = generate(client, commandInstruction(), input, maxTokens = 120, temperature = 0.1f)
                val action = SmartActions.parse(json)
                if (action != null) {
                    emit(NanoReply.Command(action))
                    return@withLock
                }
                if (!answerUnknownCommands) return@withLock
            }
            val instruction = answerInstruction() + messageContext(messages, overview) + if (history.isEmpty()) "" else "\n\n" + FOLLOW_UP_INSTRUCTION
            val request = generateContentRequest(SystemInstruction(instruction), TextPart(input)) {
                temperature = 0.3f
                topK = 24
                // A conversation may go into detail; the line in the search stays short.
                maxOutputTokens = when {
                    overview -> MAX_OVERVIEW_TOKENS
                    answerUnknownCommands -> MAX_CONVERSATION_TOKENS
                    else -> MAX_ANSWER_TOKENS
                }
            }
            val text = StringBuilder()
            client.generateContentStream(request).collect { response ->
                val chunk = response.candidates.firstOrNull()?.text ?: return@collect
                text.append(chunk)
                val shown = clean(text.toString())
                if (shown.isNotEmpty()) emit(NanoReply.Text(shown))
            }
        }
    }

    /** Alternative words for a search that found nothing ("Stromrechnung" → "rechnung, strom, energie"). */
    suspend fun searchTerms(query: String): List<String> {
        val reply = ask(TERMS_INSTRUCTION, query, maxTokens = 40) ?: return emptyList()
        return reply.lineSequence().first().split(',', ';', '\n')
            .map { it.trim().trim('"', '.', '*', '-', ' ') }
            .filter { it.length in 2..40 && !it.equals(query, ignoreCase = true) }
            .distinct()
            .take(MAX_TERMS)
    }

    private suspend fun generate(client: GenerativeModel, instruction: String, input: String, maxTokens: Int, temperature: Float): String {
        val request = generateContentRequest(SystemInstruction(instruction), TextPart(input.trim())) {
            this.temperature = temperature
            topK = 8
            maxOutputTokens = maxTokens
        }
        return client.generateContent(request).candidates.firstOrNull()?.text?.trim().orEmpty()
    }

    /** Small models like to start with "Antwort:" or wrap in code blocks; neither belongs in the row. */
    private fun clean(text: String): String = text.trim()
        .removePrefix("```").removeSuffix("```")
        .replace(Regex("^(Antwort|Answer|Du|You)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        .replace("**", "")
        .trim()

    /** The whole reply to a short task, streamed as it grows (for things the user waits for). */
    fun stream(instruction: String, input: String, maxTokens: Int = 160): Flow<String> = flow {
        val client = model ?: return@flow
        if (!isReady) return@flow
        interactive()
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
        if (background && SystemClock.elapsedRealtime() < interactiveUntil) return null
        if (background && !busy.tryLock()) return null
        if (!background) {
            interactive()
            busy.lock()
        }
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
    private fun messageContext(messages: List<MessageText>, overview: Boolean = false): String {
        if (overview && messages.isEmpty()) return "\n\n" + NO_MESSAGES_INSTRUCTION
        if (messages.isEmpty()) return ""
        val format = DateTimeFormatter.ofPattern("EEE dd.MM. HH:mm", Locale.GERMAN)
        val zone = ZoneId.systemDefault()
        val lines = messages.joinToString("\n") { m ->
            val time = Instant.ofEpochMilli(m.time).atZone(zone).format(format)
            "- $time · ${m.app} · ${m.sender}: ${m.text.take(MAX_MESSAGE_CHARS).replace('\n', ' ')}"
        }
        return buildString {
            if (overview) {
                append("\n\nDie neuesten Nachrichten auf diesem Handy (Zeit · App · Absender: Text):\n")
                append(lines)
                append("\n")
                append(OVERVIEW_INSTRUCTION)
            } else {
                append("\n\nNachrichten auf diesem Handy, die zur Eingabe passen könnten (Zeit · App · Absender: Text):\n")
                append(lines)
                append("\n")
                append(MESSAGE_INSTRUCTION)
            }
        }
    }

    /** The new message, after the conversation so far when there is one. */
    private fun transcript(history: List<Pair<String, String>>, query: String): String {
        if (history.isEmpty()) return query.trim()
        return buildString {
            history.takeLast(MAX_HISTORY).forEach { (question, answer) ->
                append("Nutzer: ").append(question.take(MAX_HISTORY_CHARS)).append('\n')
                append("Du: ").append(answer.take(MAX_HISTORY_CHARS)).append("\n\n")
            }
            append("Nutzer: ").append(query.trim())
        }
    }

    private fun now(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN))

    /** Answers everything typed; a few examples show a small model the length and tone. */
    private fun answerInstruction(): String = """
        Du bist die Antwortzeile in der Suche eines Android-Startbildschirms, wie die KI-Übersicht bei Google.
        Jetzt ist ${now()}.
        Antworte immer hilfreich, in der Sprache der Eingabe: ein bis drei kurze Sätze, das Wichtigste zuerst,
        ohne Einleitung, ohne Rückfrage, ohne Markdown.
        Ist die Eingabe nur ein Begriff oder Name, erkläre in einem Satz, was oder wer das ist.
        Weißt du etwas nicht sicher, sag das kurz und nenne, was du weißt. Für Live-Daten wie Wetter, Kurse oder
        Spielstände sag, dass du offline bist, und was man stattdessen tun kann.

        Beispiele:
        Eingabe: höhe eiffelturm
        Antwort: Der Eiffelturm ist 330 Meter hoch (mit Antennen); er wurde 1889 für die Weltausstellung in Paris gebaut.
        Eingabe: was ist eine kWh
        Antwort: Eine Kilowattstunde ist die Energie, die ein Gerät mit 1000 Watt in einer Stunde verbraucht; danach wird Strom abgerechnet.
        Eingabe: Pfannkuchen Rezept
        Antwort: 250 g Mehl, 3 Eier, 500 ml Milch und eine Prise Salz glatt rühren, kurz quellen lassen und dünn in Butter ausbacken.
        Eingabe: Photosynthese
        Antwort: Pflanzen wandeln mit Licht Wasser und Kohlendioxid in Zucker und Sauerstoff um.
    """.trimIndent()

    /** Turns a request into one line of JSON; examples carry the fields and the date format. */
    private fun commandInstruction(): String {
        val tomorrow = LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return """
            Wandle den Auftrag an das Handy in genau eine Zeile JSON um, ohne weiteren Text und ohne Codeblock.
            Steht davor ein Gespräch, gilt der letzte Auftrag; Wörter wie "sie", "ihm" oder "dort" meinen, was
            im Gespräch zuletzt genannt wurde.
            Jetzt ist ${now()}.
            Felder: action (call, sms, mail, navigate, event, web), who, text, title, place, start, end
            (Zeiten als YYYY-MM-DDTHH:MM). Lass Felder weg, die nicht vorkommen.

            Beispiele:
            Auftrag: ruf mama an
            {"action":"call","who":"Mama"}
            Auftrag: schreib anna dass ich später komme
            {"action":"sms","who":"Anna","text":"Ich komme später"}
            Auftrag: navigiere nach hause
            {"action":"navigate","place":"Zuhause"}
            Auftrag: termin morgen 15 uhr zahnarzt
            {"action":"event","title":"Zahnarzt","start":"${tomorrow}T15:00"}
            Auftrag: mail an tom betreff rechnung
            {"action":"mail","who":"Tom","title":"Rechnung"}
        """.trimIndent()
    }

    private companion object {
        const val TAG = "NanoAnswers"
        const val MAX_ANSWER_TOKENS = 320
        const val MAX_CONVERSATION_TOKENS = 600
        const val MAX_TERMS = 5
        const val RETRY_MS = 20_000L
        const val READY_WAIT_MS = 2_500L
        /** Background tasks wait this long after the last search request. */
        const val INTERACTIVE_MS = 8_000L

        val TERMS_INSTRUCTION = """
            Die Suche auf einem Handy hat für die Eingabe nichts gefunden. Nenne bis zu fünf andere Suchbegriffe,
            unter denen Apps, Kontakte, Dateien, Notizen oder Nachrichten dazu heißen könnten: Synonyme, Oberbegriffe,
            deutsch und englisch. Nur die Begriffe, kommagetrennt, in einer Zeile.
        """.trimIndent()
        const val MAX_FOLDER_NAME = 24
        const val MAX_MESSAGE_CHARS = 300
        const val MAX_OVERVIEW_TOKENS = 600
        const val MAX_HISTORY = 6
        const val MAX_HISTORY_CHARS = 400

        val FOLLOW_UP_INSTRUCTION = """
            Die Eingabe ist ein Gespräch: frühere Nachrichten des Nutzers ("Nutzer:") und deine Antworten ("Du:").
            Antworte nur auf die letzte Nutzer-Nachricht, aber im Zusammenhang des Gesprächs: kurze Folgefragen wie
            "und morgen?", "warum?", "mehr dazu", "und sie?" beziehen sich auf das zuvor Besprochene. Wiederhole
            frühere Antworten nicht, schreibe nicht "Du:" oder "Nutzer:" in deine Antwort.
        """.trimIndent()

        val OVERVIEW_INSTRUCTION = """
            Fasse diese Nachrichten für den Nutzer zusammen: pro Person oder Gruppe in einer kurzen Zeile mit "• ",
            was sie will oder mitteilt; Termine, Uhrzeiten, Fragen an den Nutzer und Dringendes zuerst. Werbung und
            automatische Meldungen nur ganz kurz am Ende. Ohne Einleitung, ohne Markdown außer "• ".
        """.trimIndent()

        val NO_MESSAGES_INSTRUCTION = """
            Für diesen Zeitraum sind keine Nachrichten auf dem Handy gespeichert. Sag das kurz und dass der Launcher
            nur Nachrichten kennt, die als Benachrichtigung ankamen, seit "Nachrichten aufbewahren" an ist.
        """.trimIndent()

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
