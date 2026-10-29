package io.github.davydgky.launcher.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.text.format.DateUtils
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.messages.Message
import io.github.davydgky.launcher.messages.Messages
import io.github.davydgky.launcher.notifications.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Messages that arrived as notifications (WhatsApp, Instagram, mail, anything), kept on the device for
 * 90 days. Needs notification access; finds only what arrived after saving was switched on.
 */
class MessagesSource(
    private val context: Context,
    private val messages: Messages,
    private val notifications: Notifications,
    private val scope: CoroutineScope,
) : SearchSource(ID) {

    override fun missing(context: Context): Requirement? = if (notifications.hasAccess()) null else Requirement.NotificationAccess

    override suspend fun load(): List<SearchItem> = messages.saved().map { it.toItem(context) }

    override fun observe(onChange: () -> Unit): AutoCloseable {
        val job = scope.launch { messages.version.drop(1).collect { onChange() } }
        return AutoCloseable { job.cancel() }
    }

    companion object {
        const val ID = "messages"
    }
}

/** All SMS of the last 90 days, also those from before saving was switched on. Needs READ_SMS. */
class SmsSource(private val context: Context, private val messages: Messages) : SearchSource(ID) {

    override fun missing(context: Context): Requirement? =
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) null else Requirement.Sms

    override suspend fun load(): List<SearchItem> = messages.sms().map { it.toItem(context) }

    override fun observe(onChange: () -> Unit) = context.observeUri(Telephony.Sms.CONTENT_URI) {
        messages.smsChanged()
        onChange()
    }

    companion object {
        const val ID = "sms"
    }
}

/** "Wir treffen uns um 14.20 am Bahnhof" · "Anna · WhatsApp · 11:02". */
private fun Message.toItem(context: Context): SearchItem {
    val m = text
    val time = DateUtils.getRelativeTimeSpanString(m.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    val who = if (m.conversation.isNotEmpty() && m.conversation != m.sender) "${m.sender} · ${m.conversation}" else m.sender
    return SearchItem(
        key = "message:${m.key}",
        kind = SourceKind.Message,
        title = m.text.lineSequence().first().take(TITLE_LENGTH),
        subtitle = listOf(who, m.app, time.toString()).filter { it.isNotBlank() }.joinToString(" · "),
        keywords = listOf(m.sender, m.conversation, m.app).filter { it.isNotBlank() }.distinct(),
        body = m.text,
        boost = recency(m.time),
        payload = target,
    )
}

/** Newer messages rank a little higher: today most, after a month nothing. */
private fun recency(time: Long): Float {
    val days = (System.currentTimeMillis() - time) / DateUtils.DAY_IN_MILLIS.toFloat()
    return (0.3f * (1f - days / 30f)).coerceIn(0f, 0.3f)
}

private const val TITLE_LENGTH = 120
