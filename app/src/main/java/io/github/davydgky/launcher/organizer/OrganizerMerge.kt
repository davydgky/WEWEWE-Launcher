package io.github.davydgky.launcher.organizer

/**
 * Brings the reminders of the launcher and of the island app together, one by one: nothing is ever
 * replaced as a whole. A reminder or list that only one side has is kept; of two copies of the same one
 * (same uid) the newer wins; one removed on either side stays removed unless it changed after that.
 * The numbers ([Todo.id], [TodoList.id]) belong to each app; the merge keeps this app's and gives new
 * ones to what comes from the other.
 */
object OrganizerMerge {

    /** Removals are remembered this long, long enough for the other app to have heard of them. */
    const val DELETED_KEEP_MS = 60L * 24 * 60 * 60 * 1000

    /**
     * [local] with everything of [other] it does not have or has in an older form. What only this app
     * keeps (suggestions, the page's arrangement) stays as it is in [local].
     */
    fun merge(local: OrganizerState, other: OrganizerState, now: Long = System.currentTimeMillis()): OrganizerState {
        val deleted = (local.deleted.keys + other.deleted.keys)
            .associateWith { maxOf(local.deleted[it] ?: 0L, other.deleted[it] ?: 0L) }
            .filterValues { now - it < DELETED_KEEP_MS }
        fun alive(uid: String, updatedAt: Long) = (deleted[uid] ?: -1L) < updatedAt || deleted[uid] == null

        // Lists: the newer copy of each, local numbers kept.
        val localLists = local.lists.associateBy { it.uid }
        val otherLists = other.lists.associateBy { it.uid }
        var nextId = maxOf(local.nextId, (local.lists.maxOfOrNull { it.id } ?: 0L) + 1, (local.todos.maxOfOrNull { it.id } ?: 0L) + 1)
        val lists = (localLists.keys + otherLists.keys).mapNotNull { uid ->
            val mine = localLists[uid]
            val theirs = otherLists[uid]
            val newer = when {
                mine == null -> theirs!!
                theirs == null -> mine
                theirs.updatedAt > mine.updatedAt -> theirs
                else -> mine
            }
            if (!alive(uid, newer.updatedAt)) return@mapNotNull null
            newer.copy(id = mine?.id ?: nextId++)
        }.sortedWith(compareBy({ it.order }, { it.id }))
        // Never without a list: reminders need one.
        val safeLists = lists.ifEmpty { local.lists.take(1).ifEmpty { other.lists.take(1) } }
        val listIdByUid = safeLists.associate { it.uid to it.id }
        val fallbackList = safeLists.first().id

        fun listUidOf(state: OrganizerState, todo: Todo) = state.lists.firstOrNull { it.id == todo.listId }?.uid

        val localTodos = local.todos.associateBy { it.uid }
        val otherTodos = other.todos.associateBy { it.uid }
        val todos = (localTodos.keys + otherTodos.keys).mapNotNull { uid ->
            val mine = localTodos[uid]
            val theirs = otherTodos[uid]
            val (newer, from) = when {
                mine == null -> theirs!! to other
                theirs == null -> mine to local
                theirs.updatedAt > mine.updatedAt -> theirs to other
                else -> mine to local
            }
            if (!alive(uid, newer.updatedAt)) return@mapNotNull null
            val listId = listUidOf(from, newer)?.let(listIdByUid::get) ?: fallbackList
            newer.copy(id = mine?.id ?: nextId++, listId = listId)
        }.sortedBy { it.id }

        return local.copy(
            lists = safeLists,
            todos = todos,
            nextId = nextId,
            modifiedAt = maxOf(local.modifiedAt, other.modifiedAt),
            deleted = deleted,
        )
    }

    /**
     * [next] after a change here: what changed or is new gets the time [now], what went is remembered as
     * removed. Compares by this app's numbers, which do not change while the app runs.
     */
    fun stamp(previous: OrganizerState, next: OrganizerState, now: Long): OrganizerState {
        val before = previous.todos.associateBy { it.id }
        val todos = next.todos.map { todo ->
            val old = before[todo.id]
            if (old == null || old != todo) todo.copy(updatedAt = now) else todo
        }
        val listsBefore = previous.lists.associateBy { it.id }
        val lists = next.lists.map { list ->
            val old = listsBefore[list.id]
            if (old == null || old != list) list.copy(updatedAt = now) else list
        }
        val nowIds = next.todos.mapTo(HashSet()) { it.id }
        val nowListIds = next.lists.mapTo(HashSet()) { it.id }
        val removed = previous.todos.filter { it.id !in nowIds }.map { it.uid } + previous.lists.filter { it.id !in nowListIds }.map { it.uid }
        return next.copy(
            todos = todos,
            lists = lists,
            modifiedAt = maxOf(now, previous.modifiedAt + 1),
            deleted = (next.deleted + removed.associateWith { now }).filterValues { now - it < DELETED_KEEP_MS },
        )
    }
}
