package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetsTest {

    @Test
    fun excerptAroundTheMatch() {
        val text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Das WLAN-Passwort lautet Sonne123, " +
            "bitte nicht weitergeben. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
        val snippet = Snippets.around(text, "passw", length = 50)!!
        assertTrue(snippet, snippet.contains("WLAN-Passwort"))
        assertTrue(snippet.startsWith("…") && snippet.endsWith("…"))
        assertTrue(snippet.length <= 52)
    }

    @Test
    fun matchesFoldedWords() {
        assertEquals("Grüße aus Köln", Snippets.around("Grüße aus Köln", "koln"))
    }

    @Test
    fun noMatch() {
        assertNull(Snippets.around("nichts zu finden", "xyz"))
        assertNull(Snippets.around("", "a"))
    }
}
