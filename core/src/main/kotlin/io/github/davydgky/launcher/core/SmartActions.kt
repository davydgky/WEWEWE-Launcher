package io.github.davydgky.launcher.core

import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * A request to the phone that the language model recognised in a sentence typed into the search:
 * "ruf Mama an", "schreib Anna, dass ich später komme", "navigiere nach Hause", "Termin morgen 15 Uhr
 * Zahnarzt". Only these fixed kinds exist, and each one is carried out by an app the user confirms in
 * (the dialer, the messaging app, maps, the calendar): the model never acts on its own.
 */
sealed interface SmartAction {
    /** [who] is a name to look up in the contacts, or a number. */
    data class Call(val who: String) : SmartAction
    data class Message(val who: String, val text: String) : SmartAction
    data class Mail(val who: String, val subject: String, val text: String) : SmartAction
    data class Navigate(val place: String) : SmartAction
    data class Event(val title: String, val start: LocalDateTime, val end: LocalDateTime?, val place: String) : SmartAction
    data class WebSearch(val query: String) : SmartAction
}

/** Reads the model's one-line JSON command; anything else is not a command. */
object SmartActions {

    fun parse(reply: String): SmartAction? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val fields = runCatching { JsonFields.parse(reply.substring(start, end + 1)) }.getOrNull() ?: return null
        fun field(name: String) = fields[name]?.trim().orEmpty()
        return when (field("action").lowercase()) {
            "call" -> field("who").takeIf { it.isNotEmpty() }?.let { SmartAction.Call(it) }
            "sms", "message" -> field("who").takeIf { it.isNotEmpty() }?.let { SmartAction.Message(it, field("text")) }
            "mail", "email" -> field("who").takeIf { it.isNotEmpty() }?.let { SmartAction.Mail(it, field("title"), field("text")) }
            "navigate" -> field("place").takeIf { it.isNotEmpty() }?.let { SmartAction.Navigate(it) }
            "event" -> {
                val begin = dateTime(field("start")) ?: return null
                SmartAction.Event(field("title").ifEmpty { field("text") }, begin, dateTime(field("end")), field("place"))
            }
            "web" -> field("text").ifEmpty { field("title") }.takeIf { it.isNotEmpty() }?.let { SmartAction.WebSearch(it) }
            else -> null
        }
    }

    private fun dateTime(text: String): LocalDateTime? {
        if (text.isEmpty()) return null
        val normalized = text.replace(' ', 'T').let { if (it.length == 10) "${it}T09:00" else it }
        return try {
            LocalDateTime.parse(normalized.take(16))
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/** A flat JSON object of strings (and numbers, kept as text); enough for the model's commands. */
internal object JsonFields {

    fun parse(json: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = skip(json, 0)
        require(json[i] == '{')
        i = skip(json, i + 1)
        if (json[i] == '}') return out
        while (true) {
            val (key, afterKey) = string(json, i)
            i = skip(json, afterKey)
            require(json[i] == ':')
            i = skip(json, i + 1)
            val (value, afterValue) = if (json[i] == '"') string(json, i) else bare(json, i)
            out[key] = value
            i = skip(json, afterValue)
            when (json[i]) {
                ',' -> i = skip(json, i + 1)
                '}' -> return out
                else -> error("Unexpected '${json[i]}'")
            }
        }
    }

    private fun skip(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        require(i < s.length) { "Unexpected end" }
        return i
    }

    private fun string(s: String, from: Int): Pair<String, Int> {
        require(s[from] == '"')
        val out = StringBuilder()
        var i = from + 1
        while (true) {
            require(i < s.length) { "Unterminated string" }
            when (val c = s[i]) {
                '"' -> return out.toString() to i + 1
                '\\' -> {
                    val e = s[i + 1]
                    when (e) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> {
                            out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(e)
                    }
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }

    /** Numbers, true, false, null: up to the next comma or brace. */
    private fun bare(s: String, from: Int): Pair<String, Int> {
        var i = from
        while (i < s.length && s[i] != ',' && s[i] != '}') i++
        val text = s.substring(from, i).trim()
        return (if (text == "null") "" else text) to i
    }
}
