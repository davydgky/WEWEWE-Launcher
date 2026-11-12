package io.github.davydgky.launcher.organizer

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.TodoDrafts
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps the todo lists and their reminders: loads them, saves every edit, debounced like the workspace. */
class OrganizerRepository(private val context: Context, private val scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "organizer.json"))
    private val state = MutableStateFlow<OrganizerState?>(null)
    val organizer: StateFlow<OrganizerState?> = state.asStateFlow()
    private var saveJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            state.value = withContext(Dispatchers.IO) { load() } ?: OrganizerState.initial(context.getString(R.string.organizer_default_list))
        }
    }

    private fun load(): OrganizerState? {
        if (!file.baseFile.exists()) return null
        return runCatching { OrganizerCodec.decode(file.readFully().decodeToString()) }
            .onFailure { e -> Log.e(TAG, "Reminders unreadable", e) }
            .getOrNull()
    }

    private fun update(change: (OrganizerState) -> OrganizerState) {
        val current = state.value ?: return
        val next = runCatching { change(current) }.onFailure { Log.w(TAG, "Edit failed", it) }.getOrNull() ?: return
        if (next == current) return
        state.value = next
        scheduleSave()
    }

    fun addList(name: String): Long {
        val current = state.value ?: return 0
        val id = current.nextId
        update { it.copy(lists = it.lists + TodoList(id, name, colorIndex = it.lists.size % LIST_COLORS, order = it.lists.size), nextId = id + 1) }
        return id
    }

    fun renameList(id: Long, name: String) = update { st -> st.copy(lists = st.lists.map { if (it.id == id) it.copy(name = name) else it }) }

    /** The list's reminders move to the first list instead of disappearing; the first list cannot be removed. */
    fun removeList(id: Long) = update { st ->
        val fallback = st.lists.firstOrNull { it.id != id }?.id ?: return@update st
        if (st.lists.size <= 1) return@update st
        st.copy(
            lists = st.lists.filter { it.id != id },
            todos = st.todos.map { if (it.listId == id) it.copy(listId = fallback) else it },
        )
    }

    fun addTodo(listId: Long, title: String, dueAtMillis: Long?, hasTime: Boolean, notes: String = ""): Long {
        val current = state.value ?: return 0
        val id = current.nextId
        val order = current.todosOf(listId).size
        update {
            it.copy(
                todos = it.todos + Todo(id, listId, title, notes, dueAtMillis, hasTime, order = order),
                nextId = id + 1,
            )
        }
        return id
    }

    fun updateTodo(id: Long, change: (Todo) -> Todo) = update { st -> st.copy(todos = st.todos.map { if (it.id == id) change(it) else it }) }

    fun toggleDone(id: Long) = update { st ->
        st.copy(
            todos = st.todos.map {
                if (it.id == id) it.copy(done = !it.done, doneAtMillis = if (!it.done) System.currentTimeMillis() else null) else it
            },
        )
    }

    fun removeTodo(id: Long) = update { st -> st.copy(todos = st.todos.filter { it.id != id }) }

    /** Messages up to this time have been looked through for suggestions; 0 before the first time. */
    val scannedUntil: Long get() = state.value?.scannedUntil ?: 0L

    /**
     * New suggestions from the messages up to [scannedUntil]. One the user already has as a reminder,
     * or is already suggested, is left out; suggestions older than [SUGGESTION_DAYS] days go.
     */
    fun addSuggestions(drafts: List<TodoSuggestion>, scannedUntil: Long) = update { st ->
        val known = (st.todos.map { it.title } + st.suggestions.map { it.title }).mapTo(HashSet(), TodoDrafts::normalized)
        val cutoff = System.currentTimeMillis() - SUGGESTION_DAYS * 24 * 60 * 60 * 1000L
        var nextId = st.nextId
        val fresh = drafts
            .filter { TodoDrafts.normalized(it.title).let { key -> key.isNotEmpty() && known.add(key) } }
            .map { it.copy(id = nextId++) }
        st.copy(
            suggestions = (st.suggestions.filter { it.messageTime >= cutoff } + fresh).takeLast(MAX_SUGGESTIONS),
            nextId = nextId,
            scannedUntil = maxOf(st.scannedUntil, scannedUntil),
        )
    }

    /** The suggestion becomes a reminder in [listId], with where it came from as its note. */
    fun acceptSuggestion(id: Long, listId: Long): Long? {
        val suggestion = state.value?.suggestions?.firstOrNull { it.id == id } ?: return null
        val source = listOf(suggestion.app, suggestion.sender).filter { it.isNotBlank() }.joinToString(" · ")
        val notes = if (suggestion.excerpt.isBlank()) source else "$source: „${suggestion.excerpt}“"
        val todo = addTodo(listId, suggestion.title, suggestion.dueAtMillis, suggestion.hasTime, notes)
        dismissSuggestion(id)
        return todo
    }

    fun dismissSuggestion(id: Long) = update { st -> st.copy(suggestions = st.suggestions.filter { it.id != id }) }

    fun dismissAllSuggestions() = update { st -> st.copy(suggestions = emptyList()) }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val text = OrganizerCodec.encode(state.value ?: return@launch)
            withContext(Dispatchers.IO) { write(text) }
        }
    }

    private fun write(text: String) {
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.e(TAG, "Cannot save reminders", e)
            return
        }
        try {
            out.write(text.toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            Log.e(TAG, "Cannot save reminders", e)
            file.failWrite(out)
        }
    }

    companion object {
        private const val TAG = "OrganizerRepository"
        const val LIST_COLORS = 6
        private const val SUGGESTION_DAYS = 14L
        private const val MAX_SUGGESTIONS = 30
    }
}
