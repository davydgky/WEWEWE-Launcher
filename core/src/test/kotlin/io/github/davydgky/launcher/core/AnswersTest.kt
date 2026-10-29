package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswersTest {

    private fun result(input: String, separator: Char = ',') = Answers.compute(input, separator)?.result

    @Test
    fun arithmetic() {
        assertEquals("84", result("12*7"))
        assertEquals("14", result("2 + 3 × 4"))
        assertEquals("20", result("(2+3)*4"))
        assertEquals("1,5", result("3/2"))
        assertEquals("1.5", result("3/2", '.'))
        assertEquals("1024", result("2^10"))
        assertEquals("65.536", result("2^16"))
        assertEquals("-3", result("-5+2"))
        assertEquals("12", result("15% * 80"))
        assertEquals("3", result("sqrt9"))
        assertEquals("2,5", result("1,5+1"))
        assertEquals("0,3333333333", result("1/3"))
        assertEquals("1.000.000", result("1000*1000"))
    }

    @Test
    fun searchesAreNotSums() {
        assertNull(result("2026"))
        assertNull(result("whatsapp"))
        assertNull(result("0176 1234567"))
        assertNull(result("+49 176 1234567"))
        assertNull(result("0176-1234567"))
        assertNull(result("2026-10-29"))
        assertNull(result("12:30"))
        assertNull(result("2+"))
        assertNull(result("1/0"))
    }

    @Test
    fun copiesTheBareNumber() {
        assertEquals("1000000", Answers.compute("1000*1000", ',')?.copyText)
        assertEquals("1.5", Answers.compute("3/2", ',')?.copyText)
    }

    @Test
    fun units() {
        assertEquals("3,106855961 mi", result("5 km in mi"))
        assertEquals("37,77777778 °C", result("100 f in c"))
        assertEquals("7,716179176 lb", result("3,5 kg in pfund"))
        assertEquals("1000 MB", result("1 gb in mb"))
        assertEquals("96,56064 km/h", result("60 mph to km/h"))
        assertEquals("90 min", result("1,5 h in min"))
        assertEquals("273,15 K", result("0 c in k"))
        assertNull(result("5 km in kg"))
        assertNull(result("5 km in km"))
        assertNull(result("urlaub in italien"))
    }

    @Test
    fun numbersInBothNotations() {
        assertEquals(1000.5, Numbers.parse("1.000,5")!!, 1e-9)
        assertEquals(1000.5, Numbers.parse("1,000.5")!!, 1e-9)
        assertEquals(1.5, Numbers.parse("1,5")!!, 1e-9)
        assertEquals(1000.0, Numbers.parse("1.000.000")!! / 1000, 1e-9)
    }
}
