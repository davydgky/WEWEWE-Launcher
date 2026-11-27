package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IslandLogicTest {

    @Test
    fun runningActivitiesComeFirstThenByKind() {
        val order = IslandPicker.order(
            listOf(
                LiveCandidate("music", LiveKind.Media, updatedAt = 50),
                LiveCandidate("download", LiveKind.Progress, updatedAt = 90),
                LiveCandidate("timer", LiveKind.Timer, updatedAt = 10),
                LiveCandidate("paused-call", LiveKind.Call, updatedAt = 99, running = false),
            ),
        ).map { it.key }
        assertEquals(listOf("timer", "music", "download", "paused-call"), order)
    }

    @Test
    fun newestFirstWithinAKind() {
        val order = IslandPicker.order(
            listOf(
                LiveCandidate("old", LiveKind.Timer, updatedAt = 1),
                LiveCandidate("new", LiveKind.Timer, updatedAt = 2),
            ),
        ).map { it.key }
        assertEquals(listOf("new", "old"), order)
    }

    @Test
    fun clock() {
        assertEquals("0:00", Durations.clock(0))
        assertEquals("0:01", Durations.clock(400))
        assertEquals("4:05", Durations.clock(245_000))
        assertEquals("12:00", Durations.clock(720_000))
        assertEquals("1:02:03", Durations.clock(3_723_000))
        assertEquals("0:00", Durations.clock(-5_000))
    }

    @Test
    fun until() {
        assertEquals("jetzt", Durations.until(0, german = true))
        assertEquals("in 5 Min.", Durations.until(4 * 60_000 + 10_000, german = true))
        assertEquals("in 1 Std.", Durations.until(60 * 60_000, german = true))
        assertEquals("in 1 h 10 min", Durations.until(70 * 60_000, german = false))
    }
}

class UrlCleanerTest {

    @Test
    fun removesTrackingParameters() {
        assertEquals(
            "https://example.com/article?id=7",
            UrlCleaner.clean("https://example.com/article?utm_source=news&id=7&utm_medium=email&fbclid=abc"),
        )
        assertEquals("https://example.com/a#top", UrlCleaner.clean("https://example.com/a?gclid=1#top"))
    }

    @Test
    fun siteSpecificParametersOnlyOnThatSite() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=42", UrlCleaner.clean("https://youtu.be/dQw4w9WgXcQ?si=xyz&t=42"))
        assertEquals("https://open.spotify.com/track/1", UrlCleaner.clean("https://open.spotify.com/track/1?si=abc"))
        // "si" means something else on other sites.
        assertEquals("https://example.com/?si=1", UrlCleaner.clean("https://example.com/?si=1"))
    }

    @Test
    fun leavesCleanLinksAlone() {
        val url = "https://www.wikipedia.org/wiki/Kotlin?action=history"
        assertEquals(url, UrlCleaner.clean(url))
        assertEquals("not a link", UrlCleaner.clean("not a link"))
    }

    @Test
    fun findsTheFirstLinkInSharedText() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            UrlCleaner.firstLink("Schau mal: https://www.youtube.com/watch?v=abc&si=track."),
        )
        assertNull(UrlCleaner.firstLink("kein Link hier"))
    }
}
