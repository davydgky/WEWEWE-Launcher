package io.github.davydgky.launcher

import io.github.davydgky.launcher.core.UsageEvent
import io.github.davydgky.launcher.data.LaunchLogCodec
import io.github.davydgky.launcher.perf.PerfStats
import io.github.davydgky.launcher.sources.VeloSource
import io.github.davydgky.launcher.sources.VeloUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {

    @Test
    fun launchLogRoundTrip() {
        val events = listOf(UsageEvent("app:0/com.example/.Main", 1_000L), UsageEvent("contact:abc", 2_000L))
        assertEquals(events, LaunchLogCodec.decode(LaunchLogCodec.encode(events)))
        assertEquals(emptyList<UsageEvent>(), LaunchLogCodec.decode("garbage\n\n12\t"))
    }

    @Test
    fun veloUrlsCompareLikeUsersDo() {
        assertEquals("example.com/a", VeloUrls.display("https://www.example.com/a/"))
        assertEquals(VeloUrls.site("http://Example.com/"), VeloUrls.site("https://www.example.com"))
    }

    @Test
    fun historyBoostPrefersFrequentAndRecent() {
        val now = 100L * 86_400_000
        val frequent = VeloSource.historyBoost(visits = 30, lastVisited = now, now = now)
        val once = VeloSource.historyBoost(visits = 1, lastVisited = now, now = now)
        val old = VeloSource.historyBoost(visits = 30, lastVisited = now - 90L * 86_400_000, now = now)
        assertTrue(frequent > once)
        assertTrue(frequent > old)
        assertTrue(frequent <= 1f)
    }

    @Test
    fun perfSummary() {
        val summary = PerfStats.summarize((1..100).map(Int::toDouble))
        assertEquals(50.0, summary.medianMs, 0.0)
        assertEquals(95.0, summary.p95Ms, 0.0)
        assertEquals(100.0, summary.maxMs, 0.0)
        assertEquals(100, summary.count)
    }
}
