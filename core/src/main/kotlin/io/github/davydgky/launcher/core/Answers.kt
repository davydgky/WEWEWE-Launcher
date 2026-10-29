package io.github.davydgky.launcher.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** An answer computed from the input itself, shown above all results: "12*7" → "84". */
class Answer(
    /** The input as understood, e.g. "5 km in mi". */
    val expression: String,
    /** The result for display, with the locale's decimal separator. */
    val result: String,
    /** What a tap copies: the bare number. */
    val copyText: String,
)

/**
 * Calculator and unit converter for the search field, like Spotlight. Everything runs on the device;
 * currencies are left out because their rates would need the network.
 */
object Answers {

    /** Null unless [input] is arithmetic with at least one operator, or a unit conversion. */
    fun compute(input: String, decimalSeparator: Char = '.'): Answer? {
        val text = input.trim()
        if (text.isEmpty() || text.length > 120) return null
        return Units.convert(text, decimalSeparator) ?: calculate(text, decimalSeparator)
    }

    private fun calculate(text: String, separator: Char): Answer? {
        // Plain numbers ("2026", "0176 1234"), phone numbers and dates are searches, not sums.
        if (text.none { it in OPERATORS } && !text.contains("sqrt", ignoreCase = true) && !text.contains('√')) return null
        if (text.none { it.isDigit() } || phoneOrDate.matches(text)) return null
        val value = runCatching { Parser(text).parse() }.getOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        val shown = format(value, separator)
        return Answer("$text =", shown, format(value, '.', grouping = false))
    }

    /** Ten significant digits without trailing zeros; [grouping] adds thousands separators. */
    fun format(value: Double, separator: Char, grouping: Boolean = true): String {
        if (value == 0.0) return "0"
        if (abs(value) >= 1e15 || abs(value) < 1e-9) {
            return String.format(java.util.Locale.ROOT, "%.6e", value).replace('.', separator)
        }
        val plain = BigDecimal(value).round(MathContext(10, RoundingMode.HALF_EVEN)).stripTrailingZeros().toPlainString()
        val negative = plain.startsWith("-")
        val digits = plain.removePrefix("-")
        val integer = digits.substringBefore('.')
        val fraction = digits.substringAfter('.', "")
        val thousands = if (separator == ',') '.' else ','
        val groupedInteger = if (grouping && integer.length > 4) {
            integer.reversed().chunked(3).joinToString(thousands.toString()).reversed()
        } else integer
        return buildString {
            if (negative) append('-')
            append(groupedInteger)
            if (fraction.isNotEmpty()) append(separator).append(fraction)
        }
    }

    private const val OPERATORS = "+-*/×÷^%"

    private val phoneOrDate = Regex("^(?:[+0][0-9 /()-]{5,}|\\d{1,4}[-./]\\d{1,2}[-./]\\d{1,4})$")

    /** Recursive descent over + - * / ^ % with parentheses, unary minus and sqrt. */
    private class Parser(source: String) {
        private val s = source.replace('×', '*').replace('÷', '/').replace(" ", "").lowercase()
        private var pos = 0

        fun parse(): Double {
            val value = expression()
            require(pos == s.length) { "Unexpected '${s[pos]}'" }
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (pos < s.length) {
                when (s[pos]) {
                    '+' -> { pos++; value += term() }
                    '-' -> { pos++; value -= term() }
                    else -> return value
                }
            }
            return value
        }

        private fun term(): Double {
            var value = power()
            while (pos < s.length) {
                when (s[pos]) {
                    '*' -> { pos++; value *= power() }
                    '/' -> { pos++; value /= power() }
                    else -> return value
                }
            }
            return value
        }

        private fun power(): Double {
            val base = unary()
            if (pos < s.length && s[pos] == '^') {
                pos++
                return base.pow(power())
            }
            return base
        }

        private fun unary(): Double = when {
            pos < s.length && s[pos] == '-' -> { pos++; -unary() }
            pos < s.length && s[pos] == '+' -> { pos++; unary() }
            else -> postfix()
        }

        private fun postfix(): Double {
            var value = primary()
            while (pos < s.length && s[pos] == '%') {
                pos++
                value /= 100
            }
            return value
        }

        private fun primary(): Double {
            require(pos < s.length) { "Unexpected end" }
            if (s.startsWith("sqrt", pos) || s[pos] == '√') {
                pos += if (s[pos] == '√') 1 else 4
                return sqrt(primary())
            }
            if (s[pos] == '(') {
                pos++
                val value = expression()
                require(pos < s.length && s[pos] == ')') { "Missing )" }
                pos++
                return value
            }
            return number()
        }

        private fun number(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == ',')) pos++
            require(pos > start) { "Number expected" }
            return Numbers.parse(s.substring(start, pos)) ?: error("Bad number")
        }
    }
}

/** Numbers as typed in German or English: "1,5", "1.5", "1.000,5", "1,000.5". */
internal object Numbers {
    fun parse(raw: String): Double? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val commas = text.count { it == ',' }
        val dots = text.count { it == '.' }
        val normalized = when {
            commas == 0 -> if (dots > 1) text.replace(".", "") else text
            dots == 0 -> if (commas > 1) text.replace(",", "") else text.replace(',', '.')
            // Both: whichever comes last is the decimal separator.
            text.lastIndexOf(',') > text.lastIndexOf('.') -> text.replace(".", "").replace(',', '.')
            else -> text.replace(",", "")
        }
        return normalized.toDoubleOrNull()
    }
}

/** Unit conversion: "5 km in mi", "100 f to c", "3,5 kg in pfund", "1 gb in mb". */
object Units {

    private class Unit(val category: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double, val symbol: String)

    private fun linear(category: String, factor: Double, symbol: String) =
        Unit(category, { it * factor }, { it / factor }, symbol)

    private val units: Map<String, Unit> = buildMap {
        fun add(names: List<String>, unit: Unit) = names.forEach { put(it, unit) }
        // Length, base metre.
        add(listOf("mm", "millimeter", "millimetre"), linear("length", 0.001, "mm"))
        add(listOf("cm", "zentimeter", "centimeter", "centimetre"), linear("length", 0.01, "cm"))
        add(listOf("m", "meter", "metre", "metres", "meters"), linear("length", 1.0, "m"))
        add(listOf("km", "kilometer", "kilometre", "kilometers", "kilometres"), linear("length", 1000.0, "km"))
        add(listOf("in", "inch", "inches", "zoll", "\""), linear("length", 0.0254, "in"))
        add(listOf("ft", "foot", "feet", "fuss", "fuß"), linear("length", 0.3048, "ft"))
        add(listOf("yd", "yard", "yards"), linear("length", 0.9144, "yd"))
        add(listOf("mi", "mile", "miles", "meile", "meilen"), linear("length", 1609.344, "mi"))
        add(listOf("nmi", "seemeile", "seemeilen"), linear("length", 1852.0, "nmi"))
        // Mass, base gram.
        add(listOf("mg", "milligramm", "milligram"), linear("mass", 0.001, "mg"))
        add(listOf("g", "gramm", "gram", "grams"), linear("mass", 1.0, "g"))
        add(listOf("kg", "kilo", "kilogramm", "kilogram", "kilograms"), linear("mass", 1000.0, "kg"))
        add(listOf("t", "tonne", "tonnen", "ton", "tons"), linear("mass", 1_000_000.0, "t"))
        add(listOf("oz", "ounce", "ounces", "unze", "unzen"), linear("mass", 28.349523125, "oz"))
        add(listOf("lb", "lbs", "pound", "pounds", "pfund"), linear("mass", 453.59237, "lb"))
        add(listOf("st", "stone"), linear("mass", 6350.29318, "st"))
        // Volume, base litre.
        add(listOf("ml", "milliliter", "millilitre"), linear("volume", 0.001, "ml"))
        add(listOf("cl", "zentiliter", "centiliter"), linear("volume", 0.01, "cl"))
        add(listOf("l", "liter", "litre", "liters", "litres"), linear("volume", 1.0, "l"))
        add(listOf("gal", "gallon", "gallons", "gallone", "gallonen"), linear("volume", 3.785411784, "gal"))
        add(listOf("floz", "fl oz"), linear("volume", 0.0295735295625, "fl oz"))
        add(listOf("cup", "cups", "tasse", "tassen"), linear("volume", 0.2365882365, "cup"))
        // Speed, base m/s.
        add(listOf("km/h", "kmh", "kph"), linear("speed", 1 / 3.6, "km/h"))
        add(listOf("m/s", "mps"), linear("speed", 1.0, "m/s"))
        add(listOf("mph"), linear("speed", 0.44704, "mph"))
        add(listOf("kn", "knot", "knots", "knoten"), linear("speed", 0.514444, "kn"))
        // Time, base second.
        add(listOf("ms"), linear("time", 0.001, "ms"))
        add(listOf("s", "sec", "sek", "sekunde", "sekunden", "second", "seconds"), linear("time", 1.0, "s"))
        add(listOf("min", "minute", "minuten", "minutes"), linear("time", 60.0, "min"))
        add(listOf("h", "std", "stunde", "stunden", "hour", "hours"), linear("time", 3600.0, "h"))
        add(listOf("d", "tag", "tage", "day", "days"), linear("time", 86400.0, "d"))
        add(listOf("woche", "wochen", "week", "weeks", "wk"), linear("time", 604800.0, "wk"))
        // Data, base byte (decimal prefixes as on storage, binary for KiB etc.).
        add(listOf("b", "byte", "bytes"), linear("data", 1.0, "B"))
        add(listOf("kb", "kilobyte"), linear("data", 1e3, "KB"))
        add(listOf("mb", "megabyte"), linear("data", 1e6, "MB"))
        add(listOf("gb", "gigabyte"), linear("data", 1e9, "GB"))
        add(listOf("tb", "terabyte"), linear("data", 1e12, "TB"))
        add(listOf("kib"), linear("data", 1024.0, "KiB"))
        add(listOf("mib"), linear("data", 1024.0 * 1024, "MiB"))
        add(listOf("gib"), linear("data", 1024.0 * 1024 * 1024, "GiB"))
        // Area, base square metre.
        add(listOf("m2", "m²", "qm"), linear("area", 1.0, "m²"))
        add(listOf("km2", "km²"), linear("area", 1e6, "km²"))
        add(listOf("ha", "hektar", "hectare", "hectares"), linear("area", 1e4, "ha"))
        add(listOf("acre", "acres"), linear("area", 4046.8564224, "acre"))
        add(listOf("ft2", "ft²", "sqft"), linear("area", 0.09290304, "ft²"))
        // Energy, base joule.
        add(listOf("j", "joule"), linear("energy", 1.0, "J"))
        add(listOf("kj", "kilojoule"), linear("energy", 1000.0, "kJ"))
        add(listOf("kcal", "kalorien", "calories"), linear("energy", 4184.0, "kcal"))
        add(listOf("kwh"), linear("energy", 3.6e6, "kWh"))
        // Temperature, base Celsius.
        add(listOf("c", "°c", "celsius", "grad"), Unit("temperature", { it }, { it }, "°C"))
        add(listOf("f", "°f", "fahrenheit"), Unit("temperature", { (it - 32) * 5 / 9 }, { it * 9 / 5 + 32 }, "°F"))
        add(listOf("k", "kelvin"), Unit("temperature", { it - 273.15 }, { it + 273.15 }, "K"))
    }

    private val connector = Regex("\\s+(?:in|to|into|nach|zu|als|=|->|→)\\s+", RegexOption.IGNORE_CASE)
    private val amount = Regex("^([-+]?[0-9][0-9.,]*)\\s*(.+)$")

    fun convert(text: String, separator: Char): Answer? {
        val parts = text.trim().split(connector)
        if (parts.size != 2) return null
        val match = amount.find(parts[0].trim()) ?: return null
        val value = Numbers.parse(match.groupValues[1]) ?: return null
        val from = units[match.groupValues[2].trim().lowercase()] ?: return null
        val to = units[parts[1].trim().lowercase()] ?: return null
        if (from.category != to.category || from === to) return null
        val result = to.fromBase(from.toBase(value))
        val shownValue = Answers.format(value, separator)
        return Answer(
            expression = "$shownValue ${from.symbol} =",
            result = "${Answers.format(result, separator)} ${to.symbol}",
            copyText = Answers.format(result, '.', grouping = false),
        )
    }
}
