package io.github.davydgky.launcher.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.davydgky.launcher.core.SearchResults
import io.github.davydgky.launcher.launcherApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Results for one input; [inputNanos] is when the input changed (System.nanoTime). */
class ResultsState(val query: String, val results: SearchResults, val inputNanos: Long) {
    companion object {
        val Empty = ResultsState("", SearchResults.Empty, 0L)
    }
}

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
            else withContext(searchThread) { ResultsState(query, repository.search(query), nanos) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ResultsState.Empty)

    fun setQuery(query: String) {
        if (query == input.value.first) return
        input.value = query to System.nanoTime()
    }
}
