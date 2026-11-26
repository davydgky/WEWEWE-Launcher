package io.github.davydgky.launcher.core

/** What a live activity in the island is, which decides which one is shown when several run. */
enum class LiveKind {
    /** An ongoing phone or video call. */
    Call,

    /** Turn-by-turn navigation. */
    Navigation,

    /** A timer counting down. */
    Timer,

    /** A stopwatch or a recording counting up. */
    Stopwatch,

    /** Music, a podcast or a video. */
    Media,

    /** A download, an upload, a delivery, a ride: anything with progress. */
    Progress,

    /** A calendar event that starts soon. */
    Event,

    /** Another ongoing notification that Android promotes to the status bar (Live Update). */
    Other,
}

/** The facts [IslandPicker] needs about one live activity. */
data class LiveCandidate(
    val key: String,
    val kind: LiveKind,
    /** When it last changed, for the order among activities of the same kind. */
    val updatedAt: Long,
    /** False for paused media or a stopped timer: still shown, but after the running ones. */
    val running: Boolean = true,
)

object IslandPicker {

    /**
     * Live activities in the order the island shows them: the first sits next to the camera, the second
     * as a small bubble beside it, and all of them in the opened island. Running before paused; then
     * calls, navigation, timers, stopwatches, media, progress, events; the newest first within a kind.
     */
    fun order(candidates: List<LiveCandidate>): List<LiveCandidate> =
        candidates.sortedWith(
            compareByDescending<LiveCandidate> { it.running }
                .thenBy { it.kind.ordinal }
                .thenByDescending { it.updatedAt },
        )
}

object Durations {

    /** "4:05", "12:00", "1:02:03": as clocks show a countdown. Negative counts as zero. */
    fun clock(millis: Long): String {
        val total = (millis.coerceAtLeast(0) + 999) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "in 5 Min.", "in 1 Std. 10 Min.", "jetzt": how far away a start is, short enough for the island. */
    fun until(millis: Long, german: Boolean): String {
        val minutes = (millis + 59_999) / 60_000
        return when {
            minutes <= 0 -> if (german) "jetzt" else "now"
            minutes < 60 -> if (german) "in $minutes Min." else "in $minutes min"
            else -> {
                val h = minutes / 60
                val m = minutes % 60
                when {
                    m == 0L -> if (german) "in $h Std." else "in $h h"
                    german -> "in $h Std. $m Min."
                    else -> "in $h h $m min"
                }
            }
        }
    }
}
