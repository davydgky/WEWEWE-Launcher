package io.github.davydgky.launcher.search

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.ai.NanoAnswers
import io.github.davydgky.launcher.ai.NanoReply
import io.github.davydgky.launcher.core.Answer
import io.github.davydgky.launcher.core.Answers
import io.github.davydgky.launcher.core.QueryKinds
import io.github.davydgky.launcher.core.QuickAction
import io.github.davydgky.launcher.core.QuickActions
import io.github.davydgky.launcher.core.ScoredItem
import io.github.davydgky.launcher.core.SearchResults
import io.github.davydgky.launcher.core.SmartAction
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.core.StoreApp
import io.github.davydgky.launcher.launcherApp
import io.github.davydgky.launcher.messages.Message
import java.text.DecimalFormatSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Results for one input; [inputNanos] is when the input changed (System.nanoTime). [answer] is a
 * calculation or unit conversion of the input itself, [action] a timer or alarm the input asks for.
 */
class ResultsState(
    val query: String,
    val results: SearchResults,
    val inputNanos: Long,
    val answer: Answer? = null,
    val action: QuickAction? = null,
) {
    companion object {
        val Empty = ResultsState("", SearchResults.Empty, 0L)
    }
}

/**
 * Gemini Nano's reply to [query]: an answer [text] so far ([done] once complete), or a request to the
 * phone ([action], "ruf Mama an") to confirm with a tap.
 */
data class AiAnswer(
    val query: String,
    val text: String,
    val done: Boolean,
    val action: SmartAction? = null,
    /** Messages from the phone the answer may come from, newest first. */
    val sources: List<Message> = emptyList(),
    /** Why there is no answer (Gemini Nano busy, loading, out of its quota). */
    val error: String? = null,
)

/** One exchange of the conversation with Gemini Nano: what was asked and the reply so far. */
data class ChatTurn(val id: Int, val question: String, val answer: AiAnswer)

/** What other words, suggested by Gemini Nano, found for an input that found nothing itself. */
class RelatedResults(val query: String, val terms: List<String>, val items: List<ScoredItem>)

/** Apps from the Play Store for [query]; shown when few installed apps match. */
class StoreResults(val query: String, val apps: List<StoreApp>)

/**
 * Runs searches on a single background thread. A new input cancels the search for the previous one,
 * so fast typing never queues up work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = application.launcherApp.search
    private val searchThread = Dispatchers.Default.limitedParallelism(1)
    private val input = MutableStateFlow("" to 0L)

    val results: StateFlow<ResultsState> = input
        .mapLatest { (query, nanos) ->
            if (query.isBlank()) ResultsState.Empty
            else withContext(searchThread) {
                val separator = DecimalFormatSymbols.getInstance().decimalSeparator
                ResultsState(query, repository.search(query), nanos, Answers.compute(query, separator), QuickActions.parse(query))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ResultsState.Empty)

    private companion object {
        const val TAG = "SearchViewModel"
        /** Gemini Nano starts once typing pauses this long; every keystroke would only cancel it. */
        const val AI_PAUSE_MS = 280L
        const val MIN_AI_CHARS = 3
        const val RELATED_PAUSE_MS = 500L
        const val RELATED_PER_TERM = 3
        const val MAX_RELATED = 8
        const val MAX_SOURCES = 6
        const val SHOWN_SOURCES = 2

        /** The store is asked once typing pauses this long, not for every letter. */
        const val STORE_PAUSE_MS = 600L
        const val MIN_STORE_CHARS = 3
    }

    private val launcher = application.launcherApp
    private val nano = application.launcherApp.nano
    // Before the flows below, which read it as soon as they start.
    private val chatState = MutableStateFlow<List<ChatTurn>>(emptyList())
    private val settings = application.launcherApp.settings

    init {
        viewModelScope.launch { if (settings.prefs.first().aiAnswers) nano.prepare() }
    }

    /**
     * Gemini Nano's answer for the current input, after a short pause in typing; a new input drops the
     * answer being written. Empty ("-" from the model) for inputs that are plain searches.
     */
    val aiAnswer: StateFlow<AiAnswer?> = results
        .distinctUntilChangedBy { it.query.trim() }
        .transformLatest { state ->
            emit(null)
            // In a conversation, typing only prepares the next question; Enter sends it.
            if (chatState.value.isNotEmpty()) return@transformLatest
            if (!wantsAnswer(state)) return@transformLatest
            if (!settings.prefs.first().aiAnswers) return@transformLatest
            nano.prepare()
            delay(AI_PAUSE_MS)
            emitAll(answer(state.query.trim(), emptyList()))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Gemini Nano's reply to [query] as it grows, after [history] of a conversation: a command, an answer
     * (from the matching messages for questions, from all recent ones for "fasse meine Nachrichten
     * zusammen"), or why there is none. Ends with a done answer or null.
     */
    private fun answer(query: String, history: List<Pair<String, String>>): Flow<AiAnswer?> = flow {
        emit(AiAnswer(query, "", done = false))
        if (!nano.awaitReady()) {
            // Say why instead of showing nothing; quietly nothing on devices without Gemini Nano.
            emit(
                when (nano.availability.value) {
                    NanoAnswers.Availability.Unsupported -> null
                    NanoAnswers.Availability.Downloading -> AiAnswer(query, "", done = true, error = launcher.getString(R.string.ai_status_downloading))
                    else -> AiAnswer(query, "", done = true, error = nano.lastError ?: launcher.getString(R.string.ai_not_ready))
                },
            )
            return@flow
        }
        val command = QueryKinds.isCommand(query)
        val overview = if (command) null else QueryKinds.wantsMessages(query)
        val sources = when {
            overview != null -> runCatching { launcher.messages.recent(overview.hours, overview.app) }.getOrDefault(emptyList())
            // Questions may be answered from the messages on the phone ("Wann muss ich zum Bahnhof?").
            !command && QueryKinds.isQuestion(query) -> runCatching { launcher.messages.relevant(query, MAX_SOURCES) }.getOrDefault(emptyList())
            else -> emptyList()
        }
        val shownSources = if (overview != null) emptyList() else sources.sortedByDescending { it.text.time }.take(SHOWN_SOURCES)
        var last = ""
        var action: SmartAction? = null
        var error: String? = null
        try {
            nano.reply(query, sources.map { it.text }, command, history, overview = overview != null).collect { reply ->
                when (reply) {
                    is NanoReply.Text -> {
                        last = reply.text
                        emit(AiAnswer(query, reply.text, done = false, sources = shownSources))
                    }
                    is NanoReply.Command -> action = reply.action
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano failed", e)
            error = e.message ?: e.javaClass.simpleName
        }
        emit(
            when {
                action != null -> AiAnswer(query, "", done = true, action = action)
                last.isNotEmpty() -> AiAnswer(query, last, done = true, sources = shownSources)
                error != null -> AiAnswer(query, "", done = true, error = error)
                else -> null
            },
        )
    }

    /** The conversation with Gemini Nano in the search, oldest first; empty when there is none. */
    val chat: StateFlow<List<ChatTurn>> = chatState.asStateFlow()

    private var chatJob: Job? = null
    private var nextTurn = 0

    /**
     * Sends [question] to the conversation (starting one): it moves up as the user's message and Gemini
     * Nano's reply grows below it. [known] is an answer the search already has for it.
     */
    fun ask(question: String, known: AiAnswer? = null) {
        val text = question.trim()
        if (text.isEmpty()) return
        chatJob?.cancel()
        // An answer cut short by the next question keeps what it had.
        chatState.value = chatState.value.map { if (it.answer.done) it else it.copy(answer = it.answer.copy(done = true)) }
        val history = chatState.value.filter { it.answer.text.isNotEmpty() }.map { it.question to it.answer.text }
        val id = nextTurn++
        val start = known?.takeIf { it.done && it.query == text } ?: AiAnswer(text, "", done = false)
        chatState.value = chatState.value + ChatTurn(id, text, start)
        if (start.done) return
        chatJob = viewModelScope.launch {
            answer(text, history).collect { reply ->
                val shown = reply ?: AiAnswer(text, "", done = true, error = launcher.getString(R.string.ai_no_answer))
                chatState.value = chatState.value.map { if (it.id == id) it.copy(answer = shown) else it }
            }
        }
    }

    /** Ends the conversation (closing the search, back, the close button). */
    fun endChat() {
        chatJob?.cancel()
        chatState.value = emptyList()
    }

    /**
     * Whether Gemini Nano should answer: not for a sum or a timer (answered already), not when the input
     * is exactly the name of an app, contact or setting that was found, not for a letter or two.
     */
    private fun wantsAnswer(state: ResultsState): Boolean {
        val query = state.query.trim()
        if (query.length < MIN_AI_CHARS || query.none { it.isLetter() }) return false
        if (state.answer != null || state.action != null) return false
        val top = state.results.top?.item ?: return true
        val named = top.kind == SourceKind.App || top.kind == SourceKind.Contact || top.kind == SourceKind.Setting
        return !(named && QueryKinds.isExactName(query, top.title))
    }

    /**
     * When the search finds nothing, Gemini Nano suggests other words for it, and what those find is
     * shown instead ("Stromrechnung" finds the PDF named "Rechnung Energie").
     */
    val related: StateFlow<RelatedResults?> = results
        .distinctUntilChangedBy { it.query.trim() }
        .transformLatest { state ->
            emit(null)
            val query = state.query.trim()
            if (chatState.value.isNotEmpty()) return@transformLatest
            val empty = state.results.top == null && state.results.groups.isEmpty()
            if (!empty || query.length < MIN_AI_CHARS || state.answer != null || state.action != null) return@transformLatest
            if (!settings.prefs.first().aiAnswers) return@transformLatest
            delay(RELATED_PAUSE_MS)
            if (!nano.awaitReady()) return@transformLatest
            val terms = runCatching { nano.searchTerms(query) }.getOrDefault(emptyList())
            if (terms.isEmpty()) return@transformLatest
            val found = withContext(searchThread) {
                terms.flatMap { term ->
                    val r = repository.search(term)
                    listOfNotNull(r.top) + r.groups.flatMap { it.items.take(RELATED_PER_TERM) }
                }
                    .distinctBy { it.item.key }
                    .sortedByDescending { it.score }
                    .take(MAX_RELATED)
            }
            if (found.isNotEmpty()) emit(RelatedResults(query, terms, found))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)


    /**
     * Play Store apps for the current input, once typing pauses, when at most one installed app matches
     * and the input is not a sum or a timer: "bahn tickets" shows the store, "whatsapp" does not.
     */
    val storeApps: StateFlow<StoreResults?> = results
        .distinctUntilChangedBy { it.query }
        .transformLatest { state ->
            emit(null)
            val query = state.query.trim()
            if (chatState.value.isNotEmpty()) return@transformLatest
            if (query.length < MIN_STORE_CHARS || state.answer != null || state.action != null) return@transformLatest
            val top = if (state.results.top?.item?.kind == SourceKind.App) 1 else 0
            val listed = state.results.groups.firstOrNull { it.kind == SourceKind.App }?.items?.size ?: 0
            if (top + listed > 1) return@transformLatest
            if (!settings.prefs.first().storeResults) return@transformLatest
            delay(STORE_PAUSE_MS)
            val installed = launcher.apps.apps.value.mapTo(HashSet()) { it.packageName }
            val found = try {
                launcher.playStore.search(query, installed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Play Store search failed", e)
                emptyList()
            }
            if (found.isNotEmpty()) emit(StoreResults(query, found))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setQuery(query: String) {
        if (query == input.value.first) return
        input.value = query to System.nanoTime()
    }
}
