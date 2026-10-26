package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun foldsCaseAndDiacritics() {
        assertEquals("muller strasse", TextNormalizer.fold("Müller Straße"))
        assertEquals("cafe creme", TextNormalizer.fold("Café Crème"))
        assertEquals("plain ascii", TextNormalizer.fold("Plain ASCII"))
    }

    @Test
    fun splitsAtNonLetters() {
        assertEquals(listOf("muller", "ludenscheidt"), TextNormalizer.tokens("Müller-Lüdenscheidt"))
        assertEquals(listOf("a1", "b", "c"), TextNormalizer.tokens("  a1, b/c "))
        assertEquals(emptyList<String>(), TextNormalizer.tokens(" - "))
    }

    @Test
    fun umlautsGetTheirTypedSpelling() {
        assertEquals(listOf("mueller"), TextNormalizer.alternateSpellings("Müller"))
        assertEquals(emptyList<String>(), TextNormalizer.alternateSpellings("Mueller"))
    }

    @Test
    fun initialsOfMultiWordTitles() {
        assertEquals("gm", TextNormalizer.initials("Google Maps"))
        assertEquals("", TextNormalizer.initials("Kalender"))
    }

    @Test
    fun digitsOnly() {
        assertEquals("491761234567", TextNormalizer.digits("+49 (176) 123-4567"))
    }
}
