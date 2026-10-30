package io.github.davydgky.launcher.messages

import android.Manifest
import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Process
import android.provider.ContactsContract
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.MessageRetrieval
import io.github.davydgky.launcher.core.MessageText
import io.github.davydgky.launcher.core.Secrets
import io.github.davydgky.launcher.data.LauncherSettings
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a message can be opened: its SMS thread, or the app it came from. */
class MessageTarget(val packageName: String, val smsThread: Long?, val address: String?, val time: Long)

/** A message with what is needed to show and open it. */
class Message(val text: MessageText, val target: MessageTarget)

/**
 * Messages for the search and for Gemini Nano's answers: what arrives as a notification (WhatsApp,
 * Instagram, mail, SMS, anything else) is kept for [KEEP_DAYS] days in a database in the launcher's own
 * storage, and the SMS store is read directly when allowed. Nothing is sent anywhere. Saving can be
 * switched off and everything saved deleted in the settings.
 */
class Messages(private val context: Context, settings: LauncherSettings, scope: CoroutineScope) {

    private val store = MessageStore(context)
    private val labels = ConcurrentHashMap<String, String>()
    /** Off until the setting is known: nothing is saved that the user may have switched off. */
    @Volatile private var saving = false
    @Volatile private var prunedAt = 0L

    private val versionState = MutableStateFlow(0)
    /** Changes whenever saved messages change. */
    val version: StateFlow<Int> = versionState.asStateFlow()

    @Volatile private var cache: List<Message>? = null
    @Volatile private var cachedAt = 0L

    init {
        scope.launch { settings.prefs.collect { saving = it.saveMessages } }
    }

    /** Deletes what is older than [KEEP_DAYS] days, at most once a day. */
    private fun pruneIfDue() {
        val now = System.currentTimeMillis()
        if (now - prunedAt < DAY_MS) return
        prunedAt = now
        runCatching { store.deleteBefore(cutoff()) }.onFailure { Log.w(TAG, "Cannot delete old messages", it) }
    }

    val hasSmsAccess: Boolean
        get() = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Keeps the messages of a notification; called for every notification posted. */
    fun capture(sbn: StatusBarNotification, scope: CoroutineScope) {
        if (!saving) return
        val messages = runCatching { extract(sbn) }.onFailure { Log.w(TAG, "Cannot read ${sbn.packageName}", it) }.getOrNull()
        if (messages.isNullOrEmpty()) return
        scope.launch(Dispatchers.IO) {
            pruneIfDue()
            if (store.add(messages) > 0) {
                cache = null
                versionState.value++
            }
        }
    }

    /** Messages saved from notifications in the last [KEEP_DAYS] days, newest first. */
    suspend fun saved(): List<Message> = withContext(Dispatchers.IO) {
        pruneIfDue()
        store.since(cutoff())
    }

    /** SMS of the last [KEEP_DAYS] days when allowed, newest first. */
    suspend fun sms(): List<Message> = withContext(Dispatchers.IO) { readSms() }

    /** Both, newest first; kept for a minute, as answers ask for them often. */
    suspend fun all(): List<Message> = withContext(Dispatchers.IO) {
        val fresh = cache?.takeIf { System.currentTimeMillis() - cachedAt < CACHE_MS }
        fresh ?: (store.since(cutoff()) + readSms())
            // Codes saved before they were recognised as such.
            .filterNot { Secrets.isOneTimeCode(it.text.text) }
            .sortedByDescending { it.text.time }
            .also {
                cache = it
                cachedAt = System.currentTimeMillis()
            }
    }

    /** The messages that may answer [question], for Gemini Nano. */
    suspend fun relevant(question: String, limit: Int = 8): List<Message> {
        val all = all()
        val byKey = all.associateBy { it.text.key }
        return MessageRetrieval.relevant(question, all.map { it.text }, System.currentTimeMillis(), limit).mapNotNull { byKey[it.key] }
    }

    /** The messages of the last [hours] hours, from apps whose name contains [app] if given; oldest first. */
    suspend fun recent(hours: Int, app: String?, limit: Int = 40): List<Message> {
        val since = System.currentTimeMillis() - hours * 60 * 60 * 1000L
        return all()
            .filter { it.text.time >= since }
            .filter { app == null || it.text.app.contains(app, ignoreCase = true) || it.target.packageName.contains(app, ignoreCase = true) }
            .take(limit)
            .reversed()
    }

    /** SMS changed: read them again next time. */
    fun smsChanged() {
        cache = null
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        store.clear()
        cache = null
        versionState.value++
    }

    suspend fun savedCount(): Int = withContext(Dispatchers.IO) { store.count() }

    /** Sign-in codes are not kept or handed to Gemini Nano: they are secrets for a minute, then noise. */
    private fun extract(sbn: StatusBarNotification): List<Message> = extractAll(sbn).filterNot { Secrets.isOneTimeCode(it.text.text) }

    private fun extractAll(sbn: StatusBarNotification): List<Message> {
        if (sbn.packageName == context.packageName || sbn.isOngoing) return emptyList()
        // Work profile messages belong to work: they are not kept in the personal launcher.
        if (sbn.user != Process.myUserHandle()) return emptyList()
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
        if (n.category in SKIPPED_CATEGORIES) return emptyList()
        val app = labelOf(sbn.packageName)
        val target = { time: Long -> MessageTarget(sbn.packageName, null, null, time) }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val me = style.user.name?.toString()?.ifBlank { null } ?: context.getString(R.string.message_me)
            val conversation = style.conversationTitle?.toString()
                ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            return style.messages.mapNotNull { m ->
                val text = m.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                val sender = m.person?.name?.toString() ?: me
                val time = m.timestamp.takeIf { it > 0 } ?: sbn.postTime
                Message(MessageText(key(sbn.packageName, conversation, sender, text, time), app, sender, conversation.ifEmpty { sender }, text, time), target(time))
            }
        }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString().trim() }?.filter { it.isNotEmpty() }
        val body = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        val texts = lines?.takeIf { it.isNotEmpty() } ?: listOfNotNull(body.ifEmpty { null })
        if (texts.isEmpty() || (title.isEmpty() && texts.all { it.length < 3 })) return emptyList()
        return texts.map { text ->
            // Without a timestamp per message, the same text from the same notification is one message.
            Message(MessageText(key(sbn.packageName, title, title, text, 0), app, title, title.ifEmpty { app }, text, sbn.postTime), target(sbn.postTime))
        }
    }

    private fun readSms(): List<Message> {
        if (!hasSmsAccess) return emptyList()
        val out = ArrayList<Message>()
        val names = HashMap<String, String>()
        val me = context.getString(R.string.message_me)
        val smsApp = Telephony.Sms.getDefaultSmsPackage(context).orEmpty()
        val appLabel = if (smsApp.isEmpty()) "SMS" else labelOf(smsApp)
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.DATE} >= ?", arrayOf(cutoff().toString()), "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_SMS) {
                    val address = c.getString(2).orEmpty()
                    val body = c.getString(3)?.trim().orEmpty()
                    if (body.isEmpty() || Secrets.isOneTimeCode(body)) continue
                    val name = names.getOrPut(address) { contactName(address) ?: address }
                    val sent = c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_SENT
                    val time = c.getLong(4)
                    out += Message(
                        MessageText("sms:${c.getLong(0)}", appLabel, if (sent) me else name, name, body, time),
                        MessageTarget(smsApp, c.getLong(1), address, time),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No SMS access", e)
        }
        return out
    }

    private fun contactName(address: String): String? = try {
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun labelOf(packageName: String): String = labels.getOrPut(packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    private fun key(vararg parts: Any): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(parts.joinToString("\u0000").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun cutoff() = System.currentTimeMillis() - KEEP_DAYS * DAY_MS

    companion object {
        private const val TAG = "Messages"
        const val KEEP_DAYS = 90L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val CACHE_MS = 60_000L
        private const val MAX_SMS = 5_000

        /** Progress bars, music, calls in progress and system status are not messages. */
        private val SKIPPED_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_CALL,
            Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_STOPWATCH,
        )
    }
}

/** The saved messages, in the launcher's own storage. */
private class MessageStore(context: Context) : SQLiteOpenHelper(context, "messages.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE message (key TEXT PRIMARY KEY, package TEXT NOT NULL, app TEXT NOT NULL, sender TEXT NOT NULL, " +
                "conversation TEXT NOT NULL, text TEXT NOT NULL, time INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX message_time ON message(time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Returns how many were new. */
    fun add(messages: List<Message>): Int {
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in messages) {
                val values = ContentValues().apply {
                    put("key", m.text.key)
                    put("package", m.target.packageName)
                    put("app", m.text.app)
                    put("sender", m.text.sender)
                    put("conversation", m.text.conversation)
                    put("text", m.text.text)
                    put("time", m.text.time)
                }
                if (db.insertWithOnConflict("message", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun since(time: Long): List<Message> {
        val out = ArrayList<Message>()
        readableDatabase.rawQuery(
            "SELECT key, package, app, sender, conversation, text, time FROM message WHERE time >= ? ORDER BY time DESC",
            arrayOf(time.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val t = c.getLong(6)
                out += Message(MessageText(c.getString(0), c.getString(2), c.getString(3), c.getString(4), c.getString(5), t), MessageTarget(c.getString(1), null, null, t))
            }
        }
        return out
    }

    fun deleteBefore(time: Long) {
        writableDatabase.delete("message", "time < ?", arrayOf(time.toString()))
    }

    fun clear() {
        writableDatabase.delete("message", null, null)
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM message", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
