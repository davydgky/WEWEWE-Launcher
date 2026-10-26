package io.github.davydgky.launcher.data

import io.github.davydgky.launcher.core.UsageEvent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Line based storage: "time<TAB>key" per launch, oldest first. */
object LaunchLogCodec {

    fun encode(events: List<UsageEvent>): String = events.joinToString("\n") { "${it.time}\t${it.key}" }

    fun decode(raw: String): List<UsageEvent> = raw.lineSequence().mapNotNull { line ->
        val time = line.substringBefore('\t', "").toLongOrNull() ?: return@mapNotNull null
        val key = line.substringAfter('\t', "").ifBlank { return@mapNotNull null }
        UsageEvent(key, time)
    }.toList()
}

/**
 * Everything the user opened from the home screen or the search: apps, shortcuts, contacts, files.
 * Feeds the suggestions and lets results the user picked before rank higher. Mutations happen on the
 * main thread; the file is written shortly after.
 */
class LaunchLog(
    private val file: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<List<UsageEvent>>(emptyList())
    val events: StateFlow<List<UsageEvent>> = state.asStateFlow()
    private var saveJob: Job? = null
    private var loaded = false

    init {
        scope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { LaunchLogCodec.decode(file.readText()) }.getOrDefault(emptyList())
            }
            state.value = (stored + state.value).takeLast(MAX_EVENTS)
            loaded = true
        }
    }

    fun record(key: String) {
        state.value = (state.value + UsageEvent(key, clock())).takeLast(MAX_EVENTS)
        scheduleSave()
    }

    fun clear() {
        state.value = emptyList()
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            if (!loaded) return@launch
            val text = LaunchLogCodec.encode(state.value)
            withContext(Dispatchers.IO) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        const val MAX_EVENTS = 5_000
    }
}
