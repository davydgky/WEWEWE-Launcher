package io.github.davydgky.launcher.core

/** Something the search can do right away instead of finding it: start a timer, set an alarm. */
sealed interface QuickAction {
    /** A countdown of [seconds]. */
    data class Timer(val seconds: Int) : QuickAction

    /** An alarm at [hour]:[minute] (24-hour clock). */
    data class Alarm(val hour: Int, val minute: Int) : QuickAction
}

/**
 * Understands "timer 5 min", "10 minuten timer", "countdown 1:30", "wecker 7:30", "alarm 6 uhr",
 * "wake me at 7 am" in German and English. Anything else is a search.
 */
object QuickActions {

    fun parse(input: String): QuickAction? {
        val text = input.trim().lowercase().replace(Regex("\\s+"), " ")
        if (text.length > 60) return null
        return timer(text) ?: alarm(text)
    }

    private val timerFirst = Regex("^(?:timer|countdown|kurzzeitwecker)(?: (?:für|for|auf))? (.+)$")
    private val timerLast = Regex("^(.+?) (?:timer|countdown)$")

    private fun timer(text: String): QuickAction.Timer? {
        val spec = timerFirst.find(text)?.groupValues?.get(1) ?: timerLast.find(text)?.groupValues?.get(1) ?: return null
        val seconds = duration(spec) ?: return null
        return if (seconds in 1..MAX_TIMER_SECONDS) QuickAction.Timer(seconds) else null
    }

    private val clockDuration = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$")
    private val part = Regex("(\\d+(?:[.,]\\d+)?) ?(stunden|stunde|std|hours|hour|hrs|hr|h|minuten|minute|minutes|mins|min|m|sekunden|sekunde|seconds|second|secs|sec|sek|s)?\\b")

    /** "5 min", "1,5 h", "1 h 30 min", "90 s", "1:30" (minutes and seconds), a bare number in minutes. */
    private fun duration(spec: String): Int? {
        clockDuration.find(spec)?.let { m ->
            val (a, b, c) = m.destructured
            return if (c.isEmpty()) a.toInt() * 60 + b.toInt() else a.toInt() * 3600 + b.toInt() * 60 + c.toInt()
        }
        val cleaned = spec.replace(" und ", " ").replace(" and ", " ")
        var total = 0.0
        var end = 0
        for (m in part.findAll(cleaned)) {
            // Only numbers and units, nothing in between: "5 min pizza" is a search.
            if (cleaned.substring(end, m.range.first).isNotBlank()) return null
            val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val factor = when (m.groupValues[2]) {
                "stunden", "stunde", "std", "hours", "hour", "hrs", "hr", "h" -> 3600
                "sekunden", "sekunde", "seconds", "second", "secs", "sec", "sek", "s" -> 1
                else -> 60
            }
            total += amount * factor
            end = m.range.last + 1
        }
        if (end == 0 || cleaned.substring(end).isNotBlank()) return null
        return Math.round(total).toInt()
    }

    private val alarm = Regex(
        "^(?:wecker|alarm|weck mich|wecke mich|wake me up|wake me|wake up)(?: (?:um|at|für|for|auf))? " +
            "(\\d{1,2})(?:[:.h](\\d{2}))? ?(uhr|am|pm|a\\.m\\.|p\\.m\\.)?$",
    )

    private fun alarm(text: String): QuickAction.Alarm? {
        val m = alarm.find(text) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3]) {
            "pm", "p.m." -> if (hour in 1..11) hour += 12
            "am", "a.m." -> if (hour == 12) hour = 0
        }
        return if (hour in 0..23 && minute in 0..59) QuickAction.Alarm(hour, minute) else null
    }

    /** The clock app's timer goes up to a day. */
    private const val MAX_TIMER_SECONDS = 24 * 3600
}
