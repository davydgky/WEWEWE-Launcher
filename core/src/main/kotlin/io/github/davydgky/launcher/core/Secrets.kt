package io.github.davydgky.launcher.core

/** Recognises texts that carry a secret, so they are neither saved nor shown to Gemini Nano. */
object Secrets {

    private val codeWords = Regex(
        "(code|pin|tan|otp|passcode|password|passwort|kennwort|verification|verify|verifizierung|bestätigung|" +
            "sicherheitscode|einmal|anmelde|login|sign[ -]?in|2fa|two[- ]factor|zwei[- ]faktor)",
        RegexOption.IGNORE_CASE,
    )

    /** Four to eight digits on their own, optionally split in two ("123 456", "123-456"), or "G-123456". */
    private val code = Regex("(?<![\\d,.])(?:[A-Z]-)?\\d{3,4}[ -]?\\d{1,4}(?![\\d,.:])")

    /** True for a sign-in or confirmation code ("Dein Code: 482913", "123-456 is your verification code"). */
    fun isOneTimeCode(text: String): Boolean {
        if (text.length > 400 || !codeWords.containsMatchIn(text)) return false
        return code.findAll(text).any { match ->
            val digits = match.value.count(Char::isDigit)
            digits in 4..8 && !looksLikeOther(text, match.range)
        }
    }

    /** Years, times, prices and phone numbers are numbers too, but not codes. */
    private fun looksLikeOther(text: String, range: IntRange): Boolean {
        val value = text.substring(range)
        val before = text.substring(0, range.first).trimEnd()
        val after = text.substring(range.last + 1).trimStart()
        if (value.length == 4 && value.toIntOrNull() in 1900..2100) return true
        if (after.startsWith("€") || after.startsWith("eur", ignoreCase = true) || before.endsWith("€") || before.endsWith("$")) return true
        if (before.endsWith("+") || before.endsWith("tel", ignoreCase = true)) return true
        return false
    }
}
