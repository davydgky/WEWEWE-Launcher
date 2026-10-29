package io.github.davydgky.launcher.search

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.davydgky.launcher.ai.NanoAnswers
import io.github.davydgky.launcher.ai.NanoReply
import io.github.davydgky.launcher.core.Answer
import io.github.davydgky.launcher.core.Answers
import io.github.davydgky.launcher.core.QuickAction
import io.github.davydgky.launcher.core.QuickActions
import io.github.davydgky.launcher.core.SearchResults
import io.github.davydgky.launcher.core.SmartAction
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.core.StoreApp
import io.github.davydgky.launcher.launcherApp
import java.text.DecimalFormatSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
class AiAnswer(val query: String, val text: String, val done: Boolean, val action: SmartAction? = null)

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
        /** The store is asked once typing pauses this long, not for every letter. */
        const val STORE_PAUSE_MS = 600L
        const val MIN_STORE_CHARS = 3
    }

    private val nano = application.launcherApp.nano
    private val settings = application.launcherApp.settings

    init {
        viewModelScope.launch { if (settings.prefs.first().aiAnswers) nano.prepare() }
    }

    /**
     * Gemini Nano's answer for the current input, after a short pause in typing; a new input drops the
     * answer being written. Empty ("-" from the model) for inputs that are plain searches.
     */
    val aiAnswer: StateFlow<AiAnswer?> = input
        .map { it.first.trim() }
        .distinctUntilChanged()
        .transformLatest { query ->
            emit(null)
            if (query.length < MIN_AI_CHARS || query.none { it.isLetter() }) return@transformLatest
            if (!settings.prefs.first().aiAnswers) return@transformLatest
            nano.prepare()
            delay(AI_PAUSE_MS)
            if (nano.availability.value != NanoAnswers.Availability.Ready) return@transformLatest
            emit(AiAnswer(query, "", done = false))
            var last = ""
            var action: SmartAction? = null
            try {
                nano.reply(query).collect { reply ->
                    when (reply) {
                        is NanoReply.Text -> {
                            last = reply.text
                            emit(AiAnswer(query, reply.text, done = false))
                        }
                        is NanoReply.Command -> action = reply.action
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano failed", e)
            }
            emit(
                when {
                    action != null -> AiAnswer(query, "", done = true, action = action)
                    last.isEmpty() -> null
                    else -> AiAnswer(query, last, done = true)
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val launcher = application.launcherApp

    /**
     * Play Store apps for the current input, once typing pauses, when at most one installed app matches
     * and the input is not a sum or a timer: "bahn tickets" shows the store, "whatsapp" does not.
     */
    val storeApps: StateFlow<StoreResults?> = results
        .distinctUntilChangedBy { it.query }
        .transformLatest { state ->
            emit(null)
            val query = state.query.trim()
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
