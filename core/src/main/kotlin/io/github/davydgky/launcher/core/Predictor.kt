package io.github.davydgky.launcher.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/** Something the user opened: an app, a shortcut, a contact, a file. */
data class UsageEvent(val key: String, val time: Long)

data class Prediction(val key: String, val score: Double)

/**
 * Predicts what the user is likely to open now, from when they opened things before.
 *
 * Every past launch votes for its key. Its vote fades with a half-life of [halfLifeDays] and counts more
 * the closer its time of day is to now (a Gaussian over the circular 24 h clock, so 23:30 is close to
 * 00:30). Launches on the same kind of day (work day or weekend) count fully, the others half.
 * A base share keeps apps that are used all day long in the list.
 */
class Predictor(
    private val halfLifeDays: Double = 14.0,
    private val sigmaHours: Double = 1.5,
    private val baseShare: Double = 0.25,
) {
    fun rank(events: List<UsageEvent>, now: Long, zone: ZoneId): List<Prediction> {
        if (events.isEmpty()) return emptyList()
        val nowLocal = Instant.ofEpochMilli(now).atZone(zone)
        val nowHour = nowLocal.hour + nowLocal.minute / 60.0
        val nowWeekend = nowLocal.dayOfWeek.isWeekend()
        val scores = HashMap<String, Double>()
        for (event in events) {
            if (event.time > now) continue
            val ageDays = (now - event.time) / DAY_MS
            val decay = 0.5.pow(ageDays / halfLifeDays)
            val local = Instant.ofEpochMilli(event.time).atZone(zone)
            val hour = local.hour + local.minute / 60.0
            val distance = circularDistance(hour, nowHour)
            val timeOfDay = exp(-(distance * distance) / (2 * sigmaHours * sigmaHours))
            val dayMatch = if (local.dayOfWeek.isWeekend() == nowWeekend) 1.0 else 0.5
            scores.merge(event.key, decay * (baseShare + timeOfDay) * dayMatch, Double::plus)
        }
        return scores.map { Prediction(it.key, it.value) }.sortedByDescending { it.score }
    }

    companion object {
        private const val DAY_MS = 24.0 * 60 * 60 * 1000

        /** Distance between two times of day in hours, at most 12. */
        fun circularDistance(a: Double, b: Double): Double {
            val d = abs(a - b) % 24.0
            return min(d, 24.0 - d)
        }

        private fun DayOfWeek.isWeekend() = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY
    }
}

/**
 * How much the user engaged with each item recently, 0..1, independent of the time of day. Used to rank
 * search results the user picked before above equally good matches.
 */
object Engagement {

    fun scores(events: List<UsageEvent>, now: Long, halfLifeDays: Double = 30.0): Map<String, Float> {
        val sums = HashMap<String, Double>()
        for (event in events) {
            if (event.time > now) continue
            val ageDays = (now - event.time) / (24.0 * 60 * 60 * 1000)
            sums.merge(event.key, 0.5.pow(ageDays / halfLifeDays), Double::plus)
        }
        // Saturates: three recent uses give 0.5, many uses approach 1.
        return sums.mapValues { (_, sum) -> (sum / (sum + 3.0)).toFloat() }
    }
}
