package io.github.davydgky.launcher.organizer

import org.json.JSONArray
import org.json.JSONObject

/** A tab above the todo list, as the "Lists" in Apple Reminders. */
data class TodoList(val id: Long, val name: String, val colorIndex: Int, val order: Int)

/**
 * A reminder with an optional due date, as in Apple Reminders: a bare date shows as an all-day task, a
 * date with [hasTime] also carries a time of day. One with a due date appears on that day in the
 * calendar grid.
 */
data class Todo(
    val id: Long,
    val listId: Long,
    val title: String,
    val notes: String = "",
    val dueAtMillis: Long? = null,
    val hasTime: Boolean = false,
    val done: Boolean = false,
    val doneAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val order: Int = 0,
)

/**
 * A reminder Gemini Nano drafted from a message, waiting for the user to take it into a list or not.
 * [app], [sender] and [excerpt] say where it came from.
 */
data class TodoSuggestion(
    val id: Long,
    val title: String,
    val dueAtMillis: Long? = null,
    val hasTime: Boolean = false,
    val app: String = "",
    val sender: String = "",
    val excerpt: String = "",
    val messageTime: Long = 0L,
)

/** The parts of the organizer page, which the user can put in any order and hide. */
object OrganizerSections {
    const val CALENDAR = "calendar"
    const val AGENDA = "agenda"
    const val SUGGESTIONS = "suggestions"
    const val TASKS = "tasks"

    val DEFAULT = listOf(CALENDAR, AGENDA, SUGGESTIONS, TASKS)

    /** [order] as saved, without names it does not know, and with parts added since at the end. */
    fun normalized(order: List<String>): List<String> {
        val known = order.distinct().filter { it in DEFAULT }
        return known + DEFAULT.filter { it !in known }
    }
}

/** Everything the organizer page keeps of its own (the calendar itself stays in the system provider). */
data class OrganizerState(
    val lists: List<TodoList> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val nextId: Long = 1L,
    val suggestions: List<TodoSuggestion> = emptyList(),
    /** Messages up to this time have been looked through for suggestions. */
    val scannedUntil: Long = 0L,
    /** The page's parts from top to bottom ([OrganizerSections]). */
    val sections: List<String> = OrganizerSections.DEFAULT,
    val hiddenSections: Set<String> = emptySet(),
) {

    fun list(id: Long): TodoList? = lists.firstOrNull { it.id == id }
    fun todosOf(listId: Long): List<Todo> = todos.filter { it.listId == listId }.sortedWith(TODO_ORDER)

    companion object {
        val TODO_ORDER = compareBy<Todo> { it.done }.thenBy { it.order }

        /** The default list every fresh install starts with. */
        fun initial(defaultListName: String) = OrganizerState(
            lists = listOf(TodoList(id = 1, name = defaultListName, colorIndex = 0, order = 0)),
            nextId = 2,
        )
    }
}

/** Reads and writes [OrganizerState] as JSON, the same way the home screen arrangement is stored. */
object OrganizerCodec {
    private const val VERSION = 1

    fun encode(state: OrganizerState): String = JSONObject().apply {
        put("version", VERSION)
        put("nextId", state.nextId)
        put("lists", JSONArray().apply {
            state.lists.forEach { list ->
                put(
                    JSONObject().apply {
                        put("id", list.id)
                        put("name", list.name)
                        put("color", list.colorIndex)
                        put("order", list.order)
                    },
                )
            }
        })
        put("todos", JSONArray().apply {
            state.todos.forEach { todo ->
                put(
                    JSONObject().apply {
                        put("id", todo.id)
                        put("listId", todo.listId)
                        put("title", todo.title)
                        put("notes", todo.notes)
                        if (todo.dueAtMillis != null) put("due", todo.dueAtMillis)
                        put("hasTime", todo.hasTime)
                        put("done", todo.done)
                        if (todo.doneAtMillis != null) put("doneAt", todo.doneAtMillis)
                        put("createdAt", todo.createdAtMillis)
                        put("order", todo.order)
                    },
                )
            }
        })
        put("suggestions", JSONArray().apply {
            state.suggestions.forEach { s ->
                put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        if (s.dueAtMillis != null) put("due", s.dueAtMillis)
                        put("hasTime", s.hasTime)
                        put("app", s.app)
                        put("sender", s.sender)
                        put("excerpt", s.excerpt)
                        put("messageTime", s.messageTime)
                    },
                )
            }
        })
        put("scannedUntil", state.scannedUntil)
        put("sections", JSONArray(state.sections))
        put("hidden", JSONArray(state.hiddenSections.sorted()))
    }.toString()

    fun decode(text: String): OrganizerState {
        val root = JSONObject(text)
        val lists = root.optJSONArray("lists")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TodoList(id = o.getLong("id"), name = o.getString("name"), colorIndex = o.optInt("color"), order = o.optInt("order"))
            }
        }.orEmpty()
        val todos = root.optJSONArray("todos")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Todo(
                    id = o.getLong("id"),
                    listId = o.getLong("listId"),
                    title = o.getString("title"),
                    notes = o.optString("notes"),
                    dueAtMillis = if (o.has("due")) o.getLong("due") else null,
                    hasTime = o.optBoolean("hasTime"),
                    done = o.optBoolean("done"),
                    doneAtMillis = if (o.has("doneAt")) o.getLong("doneAt") else null,
                    createdAtMillis = o.optLong("createdAt", System.currentTimeMillis()),
                    order = o.optInt("order"),
                )
            }
        }.orEmpty()
        val suggestions = root.optJSONArray("suggestions")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TodoSuggestion(
                    id = o.getLong("id"),
                    title = o.getString("title"),
                    dueAtMillis = if (o.has("due")) o.getLong("due") else null,
                    hasTime = o.optBoolean("hasTime"),
                    app = o.optString("app"),
                    sender = o.optString("sender"),
                    excerpt = o.optString("excerpt"),
                    messageTime = o.optLong("messageTime"),
                )
            }
        }.orEmpty()
        val highestId = maxOf(todos.maxOfOrNull { it.id } ?: 0L, suggestions.maxOfOrNull { it.id } ?: 0L)
        return OrganizerState(
            lists = lists,
            todos = todos,
            nextId = root.optLong("nextId", highestId + 1),
            suggestions = suggestions,
            scannedUntil = root.optLong("scannedUntil"),
            sections = OrganizerSections.normalized(root.optJSONArray("sections").strings()),
            hiddenSections = root.optJSONArray("hidden").strings().filterTo(HashSet()) { it in OrganizerSections.DEFAULT },
        )
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
