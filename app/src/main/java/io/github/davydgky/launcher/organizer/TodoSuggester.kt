package io.github.davydgky.launcher.organizer

import io.github.davydgky.launcher.ai.NanoAnswers
import io.github.davydgky.launcher.core.TodoDrafts
import io.github.davydgky.launcher.messages.Messages
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drafts reminders from new messages with Gemini Nano: "Kannst du morgen die Pakete abholen?" becomes
 * the suggestion "Pakete abholen", due tomorrow, which the user takes into a list on the organizer page
 * or dismisses. Android lets Gemini Nano run only while the launcher is in the foreground, so this works
 * through what arrived since last time while the home screen is in use, a few messages per request and
 * only when Gemini Nano has nothing else to do. Everything stays on the phone.
 */
class TodoSuggester(
    private val nano: NanoAnswers,
    private val messages: Messages,
    private val organizer: OrganizerRepository,
    private val scope: CoroutineScope,
) {
    private var worker: Job? = null

    /** The home screen is in use: look through the messages that came since last time, then wait for more. */
    fun resume() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            delay(START_DELAY_MS)
            while (true) {
                val seen = messages.version.value
                if (!work()) break
                // Done with what there is: again when new messages are saved.
                messages.version.first { it != seen }
                delay(SETTLE_MS)
            }
        }
    }

    fun pause() {
        worker?.cancel()
    }

    /** Works through the new messages; false when Gemini Nano is missing or keeps being unavailable. */
    private suspend fun work(): Boolean {
        organizer.organizer.first { it != null }
        val now = System.currentTimeMillis()
        // The first time, the last two days: a few suggestions right away, not months of history.
        val since = organizer.scannedUntil.takeIf { it > 0 } ?: (now - FIRST_LOOK_BACK_MS)
        val fresh = messages.all()
            .filter { it.text.time > since }
            .sortedBy { it.text.time }
            .takeLast(MAX_PER_RUN)
        if (fresh.isEmpty()) return true
        for (batch in fresh.chunked(TodoDrafts.BATCH)) {
            val worth = batch.filter { TodoDrafts.worthAsking(it.text.text) }
            val until = batch.last().text.time
            if (worth.isEmpty()) {
                organizer.addSuggestions(emptyList(), until)
                continue
            }
            val reply = ask(TodoDrafts.input(worth.map { it.text })) ?: return false
            val zone = ZoneId.systemDefault()
            val drafts = TodoDrafts.parse(reply, worth.size).map { draft ->
                val source = worth[draft.message].text
                val time = draft.time
                val due = draft.date?.let { date ->
                    (if (time != null) date.atTime(time).atZone(zone) else date.atStartOfDay(zone)).toInstant().toEpochMilli()
                }
                TodoSuggestion(
                    id = 0,
                    title = draft.title,
                    dueAtMillis = due,
                    hasTime = time != null,
                    app = source.app,
                    sender = source.sender,
                    excerpt = source.text.replace('\n', ' ').take(EXCERPT_CHARS),
                    messageTime = source.time,
                )
            }
            organizer.addSuggestions(drafts, until)
            delay(BETWEEN_MS)
        }
        return true
    }

    /**
     * Gemini Nano's reply about [input], waiting while it is busy with the search, still loading or out of
     * quota; null when it is missing or stays unavailable.
     */
    private suspend fun ask(input: String): String? {
        repeat(MAX_MISSES) {
            // Suggestions are no reason to download the model: that is up to the search setting.
            nano.prepare(download = false)
            if (nano.availability.value == NanoAnswers.Availability.Unsupported) return null
            if (nano.isReady) {
                nano.ask(TodoDrafts.instruction(LocalDate.now()), input, maxTokens = 200, background = true)?.let { return it }
            }
            delay(RETRY_MS)
        }
        return null
    }

    private companion object {
        const val START_DELAY_MS = 6_000L
        const val SETTLE_MS = 3_000L
        const val BETWEEN_MS = 2_500L
        const val RETRY_MS = 15_000L
        const val MAX_MISSES = 4
        const val MAX_PER_RUN = 36
        const val EXCERPT_CHARS = 160
        const val FIRST_LOOK_BACK_MS = 2 * 24 * 60 * 60 * 1000L
    }
}
