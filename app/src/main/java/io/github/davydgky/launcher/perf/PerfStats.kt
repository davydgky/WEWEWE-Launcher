package io.github.davydgky.launcher.perf

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Metric(val budgetMs: Double) {
    /** From the moment the swipe is recognised to the first frame in which the keyboard is visible. */
    Keyboard(150.0),

    /** From a changed search input to the frame that shows its results. */
    Results(50.0),
}

data class MetricSummary(val count: Int, val medianMs: Double, val p95Ms: Double, val maxMs: Double, val lastMs: Double)

/** Latency samples measured on the device; the last [WINDOW] per metric are kept. */
class PerfStats {

    private val samples = Metric.entries.associateWith { ArrayDeque<Double>() }
    private val state = MutableStateFlow<Map<Metric, MetricSummary>>(emptyMap())
    val summaries: StateFlow<Map<Metric, MetricSummary>> = state.asStateFlow()

    @Synchronized
    fun record(metric: Metric, millis: Double) {
        val list = samples.getValue(metric)
        list.addLast(millis)
        if (list.size > WINDOW) list.removeFirst()
        Log.i(TAG, "%s %.1f ms (budget %.0f ms)".format(metric.name, millis, metric.budgetMs))
        state.value = state.value + (metric to summarize(list))
    }

    @Synchronized
    fun reset() {
        samples.values.forEach { it.clear() }
        state.value = emptyMap()
    }

    companion object {
        const val TAG = "LauncherPerf"
        const val WINDOW = 200

        fun summarize(values: Collection<Double>): MetricSummary {
            val sorted = values.sorted()
            fun percentile(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
            return MetricSummary(sorted.size, percentile(0.5), percentile(0.95), sorted.last(), values.last())
        }
    }
}
