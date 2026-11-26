package io.github.davydgky.launcher.island

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.core.LiveKind
import io.github.davydgky.launcher.core.UrlCleaner
import io.github.davydgky.launcher.organizer.CalendarEvents
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the Dynamic Island shows, gathered from the system: what plays, what runs (timers, calls,
 * navigation, downloads, Live Updates), short messages (a notification arrives, the phone starts
 * charging, the ringer changes, headphones connect), the next event and the tray. Nothing leaves the
 * device; notifications are only held in memory, the tray in the app's own storage.
 */
class IslandHub(private val context: Context, private val scope: CoroutineScope) {

    private val live = MutableStateFlow<List<LiveItem>>(emptyList())
    val liveItems: StateFlow<List<LiveItem>> = live.asStateFlow()

    private val peekState = MutableStateFlow<Peek?>(null)
    val peek: StateFlow<Peek?> = peekState.asStateFlow()

    private val batteryState = MutableStateFlow(BatteryInfo())
    val battery: StateFlow<BatteryInfo> = batteryState.asStateFlow()

    private val eventState = MutableStateFlow<SoonEvent?>(null)
    val nextEvent: StateFlow<SoonEvent?> = eventState.asStateFlow()

    private val shelfState = MutableStateFlow<List<ShelfItem>>(emptyList())
    val shelf: StateFlow<List<ShelfItem>> = shelfState.asStateFlow()

    private val dndState = MutableStateFlow(false)
    /** "Do not disturb" is on (any filter other than "all"). */
    val doNotDisturb: StateFlow<Boolean> = dndState.asStateFlow()

    private val lockedState = MutableStateFlow(false)
    /** The lock screen shows: notifications show without their content, the tray and search are hidden. */
    val locked: StateFlow<Boolean> = lockedState.asStateFlow()

    /** The island keeps the screen on while this is true; turning the screen off ends it. */
    val keepAwake = MutableStateFlow(false)

    /** The island shows the peeks of notifications, and of charging, ringer and headphones. */
    var notificationPeeks = true
    var systemPeeks = true

    val media = MediaTracker(context)

    private var running = false
    private var listener: NotificationListenerService? = null
    private var peekJob: Job? = null
    private var eventJob: Job? = null
    private var nextPeekId = 1L
    /** What each notification said when it last peeked, so updates of the same one stay quiet. */
    private val peeked = HashMap<String, Int>()
    private val shelfFile = File(context.filesDir, "shelf.json")
    private val main = Handler(Looper.getMainLooper())

    init {
        scope.launch { shelfState.value = withContext(Dispatchers.IO) { readShelf() } }
    }

    // region Lifecycle: the island window starts and stops the work that only it needs.

    fun start() {
        if (running) return
        running = true
        registerReceivers()
        audio.registerAudioDeviceCallback(audioDevices, main)
        media.start()
        listener?.let { dndState.value = it.currentInterruptionFilter.isDnd() }
        lockedState.value = keyguard.isKeyguardLocked
        watchEvents()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { context.unregisterReceiver(receiver) }
        audio.unregisterAudioDeviceCallback(audioDevices)
        knownDevices = null
        media.stop()
        eventJob?.cancel()
        peekJob?.cancel()
        peekState.value = null
        keepAwake.value = false
    }

    // endregion

    // region Notifications

    internal fun listenerConnected(service: NotificationListenerService) {
        listener = service
        val active = runCatching { service.activeNotifications }.getOrNull().orEmpty()
        live.value = active.mapNotNull(::liveItemOf).sortedByDescending { it.updatedAt }
        // What is already in the shade does not peek again.
        active.forEach { peeked[it.key] = contentHash(it) }
        dndState.value = service.currentInterruptionFilter.isDnd()
        if (running) media.start()
    }

    internal fun listenerDisconnected(service: NotificationListenerService) {
        if (listener === service) listener = null
        live.value = emptyList()
    }

    internal fun interruptionFilterChanged(filter: Int) {
        dndState.value = filter.isDnd()
    }

    internal fun posted(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?) {
        if (sbn.packageName == context.packageName) return
        val item = liveItemOf(sbn)
        val others = live.value.filter { it.key != sbn.key }
        live.value = if (item != null) listOf(item) + others else others
        if (item == null) maybePeek(sbn, rankingMap)
    }

    internal fun removed(sbn: StatusBarNotification) {
        peeked.remove(sbn.key)
        if (live.value.any { it.key == sbn.key }) live.value = live.value.filter { it.key != sbn.key }
        if (peekState.value?.notificationKey == sbn.key) dismissPeek()
    }

    /** Switches "Do not disturb" between "priority only" and off; false without notification access. */
    fun toggleDoNotDisturb(): Boolean {
        val service = listener ?: return false
        val on = service.currentInterruptionFilter.isDnd()
        service.requestInterruptionFilter(if (on) NotificationListenerService.INTERRUPTION_FILTER_ALL else NotificationListenerService.INTERRUPTION_FILTER_PRIORITY)
        dndState.value = !on
        return true
    }

    /** Removes a notification that asks for it once it was opened, as the shade does. */
    fun cancelNotification(key: String) {
        runCatching { listener?.cancelNotification(key) }
    }

    private fun maybePeek(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?) {
        val n = sbn.notification
        val hash = contentHash(sbn)
        val before = peeked.put(sbn.key, hash)
        if (!running || !notificationPeeks) return
        // Updates of the same notification, group summaries and silent ones stay quiet, as in the shade.
        if (before != null && (before == hash || n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
        if (System.currentTimeMillis() - sbn.postTime > 10_000) return
        val ranking = NotificationListenerService.Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT || !ranking.matchesInterruptionFilter() || ranking.isSuspended) return
        }
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val label = appLabel(sbn.packageName)
        val secret = lockedState.value || n.visibility == Notification.VISIBILITY_SECRET
        val image = if (secret) appIcon(sbn.packageName) else (bitmapOf(n.getLargeIcon()) ?: appIcon(sbn.packageName))
        showPeek(
            Peek(
                id = nextPeekId++,
                kind = PeekKind.Notification,
                title = if (secret) label else title.ifBlank { label },
                text = if (secret) context.getString(R.string.island_new_notification) else text,
                image = image,
                notificationKey = sbn.key,
                contentIntent = n.contentIntent,
                autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
                durationMs = 5_000,
            ),
        )
    }

    private fun contentHash(sbn: StatusBarNotification): Int {
        val e = sbn.notification.extras
        return (e.getCharSequence(Notification.EXTRA_TITLE)?.toString() + "\u0000" + e.getCharSequence(Notification.EXTRA_TEXT)?.toString()).hashCode()
    }

    /** An ongoing notification that the island shows as a live activity; null for everything else. */
    private fun liveItemOf(sbn: StatusBarNotification): LiveItem? {
        if (sbn.packageName == context.packageName) return null
        val n = sbn.notification
        val extras = n.extras
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        // Music and videos come from their media session, with artwork and controls.
        if (template.contains("MediaStyle") || n.category == Notification.CATEGORY_TRANSPORT) return null
        val promoted = n.flags and Notification.FLAG_PROMOTED_ONGOING != 0
        if (!sbn.isOngoing && !promoted) return null
        val chronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val hasProgress = max > 0 || indeterminate || template.contains("ProgressStyle")
        val kind = when {
            n.category == Notification.CATEGORY_CALL || template.contains("CallStyle") -> LiveKind.Call
            n.category == Notification.CATEGORY_NAVIGATION -> LiveKind.Navigation
            chronometer && countDown -> LiveKind.Timer
            n.category == Notification.CATEGORY_STOPWATCH || (chronometer && n.category != Notification.CATEGORY_SERVICE) -> LiveKind.Stopwatch
            sbn.packageName in ClockApps && (n.category == Notification.CATEGORY_ALARM || n.category == Notification.CATEGORY_PROGRESS || n.channelId.orEmpty().contains("timer", ignoreCase = true)) -> LiveKind.Timer
            hasProgress -> LiveKind.Progress
            promoted -> LiveKind.Other
            else -> return null
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: appLabel(sbn.packageName)
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
        val progress = if (max > 0) (extras.getInt(Notification.EXTRA_PROGRESS).toFloat() / max).coerceIn(0f, 1f) else null
        val shortText = runCatching { n.shortCriticalText }.getOrNull()?.takeIf { it.isNotBlank() }
        return LiveItem(
            key = sbn.key,
            kind = kind,
            packageName = sbn.packageName,
            title = title,
            text = text,
            icon = smallIcon(sbn.packageName, n.smallIcon),
            color = n.color,
            progress = progress,
            indeterminate = indeterminate && progress == null,
            chronometer = if (chronometer || kind == LiveKind.Call) n.`when`.takeIf { it > 0 } else null,
            countDown = countDown,
            shortText = shortText,
            contentIntent = n.contentIntent,
            updatedAt = sbn.postTime,
            // A paused timer shows its time without a running chronometer.
            running = kind != LiveKind.Timer || chronometer,
        )
    }

    // endregion

    // region Peeks

    fun showPeek(peek: Peek) {
        peekJob?.cancel()
        peekState.value = peek
        peekJob = scope.launch {
            delay(peek.durationMs)
            if (peekState.value?.id == peek.id) peekState.value = null
        }
    }

    /** Keeps the current peek up while a finger is on it. */
    fun holdPeek() {
        peekJob?.cancel()
    }

    fun releasePeek() {
        val current = peekState.value ?: return
        peekJob?.cancel()
        peekJob = scope.launch {
            delay(2_000)
            if (peekState.value?.id == current.id) peekState.value = null
        }
    }

    fun dismissPeek() {
        peekJob?.cancel()
        peekState.value = null
    }

    fun confirm(text: String, iconRes: Int = R.drawable.ic_check) {
        showPeek(Peek(nextPeekId++, PeekKind.Done, text, "", iconRes = iconRes, tint = 0xFF34C759.toInt(), durationMs = 1_800))
    }

    // endregion

    // region Battery, ringer, screen

    private val audio = context.getSystemService(AudioManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    batteryState.value = BatteryInfo(level * 100 / scale, plugged && status != BatteryManager.BATTERY_STATUS_DISCHARGING)
                }
                Intent.ACTION_POWER_CONNECTED -> if (systemPeeks) {
                    // The level arrives with the next battery broadcast; the current one is good enough.
                    val level = batteryState.value.level
                    showPeek(
                        Peek(
                            nextPeekId++, PeekKind.Charging, context.getString(R.string.island_charging), if (level >= 0) "$level %" else "",
                            iconRes = R.drawable.ic_bolt, tint = 0xFF34C759.toInt(), level = level, durationMs = 3_000,
                        ),
                    )
                }
                Intent.ACTION_BATTERY_LOW -> if (systemPeeks) {
                    val level = batteryState.value.level
                    showPeek(
                        Peek(
                            nextPeekId++, PeekKind.BatteryLow, context.getString(R.string.island_battery_low), if (level >= 0) "$level %" else "",
                            iconRes = R.drawable.ic_battery, tint = 0xFFFF453A.toInt(), level = level, durationMs = 4_000,
                        ),
                    )
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> if (systemPeeks && !isInitialStickyBroadcast) {
                    val (text, icon) = when (intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)) {
                        AudioManager.RINGER_MODE_SILENT -> R.string.island_ringer_silent to R.drawable.ic_bell_off
                        AudioManager.RINGER_MODE_VIBRATE -> R.string.island_ringer_vibrate to R.drawable.ic_vibrate
                        else -> R.string.island_ringer_normal to R.drawable.ic_bell
                    }
                    showPeek(Peek(nextPeekId++, PeekKind.Ringer, context.getString(text), "", iconRes = icon, tint = 0xFFFF9F0A.toInt(), durationMs = 1_800))
                }
                Intent.ACTION_SCREEN_OFF -> {
                    keepAwake.value = false
                    lockedState.value = true
                    eventJob?.cancel()
                }
                Intent.ACTION_SCREEN_ON -> {
                    lockedState.value = keyguard.isKeyguardLocked
                    watchEvents()
                }
                Intent.ACTION_USER_PRESENT -> lockedState.value = false
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    /** Output devices already there when the island started; null until the first report. */
    private var knownDevices: Set<Int>? = null

    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            val known = knownDevices
            val sinks = added.filter { it.isSink && it.type in Headphones }
            knownDevices = known.orEmpty() + sinks.map { it.id }
            // The first report lists what is connected already.
            if (known == null || !systemPeeks) return
            val device = sinks.firstOrNull { it.id !in known } ?: return
            // A Bluetooth headset arrives twice (music and calls): one peek is enough.
            val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: context.getString(R.string.island_headphones)
            val current = peekState.value
            if (current?.kind == PeekKind.Device && current.text == name) return
            showPeek(Peek(nextPeekId++, PeekKind.Device, context.getString(R.string.island_connected), name, iconRes = R.drawable.ic_headphones, tint = 0xFF0A84FF.toInt(), durationMs = 2_500))
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            knownDevices = knownDevices?.minus(removed.map { it.id }.toSet())
        }
    }

    // endregion

    // region Calendar

    /** Looks once a minute, while the screen is on, for an event starting within the next half hour. */
    private fun watchEvents() {
        eventJob?.cancel()
        if (!running) return
        eventJob = scope.launch {
            while (isActive) {
                eventState.value = withContext(Dispatchers.IO) { soonEvent() }
                delay(60_000 - System.currentTimeMillis() % 60_000)
            }
        }
    }

    private fun soonEvent(): SoonEvent? {
        if (!CalendarEvents.hasReadAccess(context)) return null
        val now = System.currentTimeMillis()
        return runCatching { CalendarEvents.between(context, now - EVENT_GRACE_MS, now + EVENT_AHEAD_MS) }
            .getOrNull().orEmpty()
            .filter { !it.allDay && it.begin in (now - EVENT_GRACE_MS)..(now + EVENT_AHEAD_MS) && it.end > now }
            .minByOrNull { it.begin }
            ?.let { SoonEvent(it.title, it.begin, it.color) }
    }

    // endregion

    // region Tray

    /** Parks shared text or a link in the tray; links lose their tracking parameters on the way. */
    fun addToShelf(text: String) {
        val clean = text.trim().let { t -> if (t.startsWith("http://") || t.startsWith("https://")) UrlCleaner.clean(t) else t }
        if (clean.isEmpty()) return
        val list = listOf(ShelfItem(System.nanoTime(), clean, System.currentTimeMillis())) + shelfState.value.filter { it.text != clean }
        updateShelf(list.take(SHELF_SIZE))
    }

    fun removeFromShelf(id: Long) = updateShelf(shelfState.value.filter { it.id != id })

    fun clearShelf() = updateShelf(emptyList())

    fun copy(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("WEWEWE", text))
    }

    private fun updateShelf(list: List<ShelfItem>) {
        shelfState.value = list
        scope.launch(Dispatchers.IO) {
            val array = JSONArray()
            list.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.addedAt)) }
            runCatching { shelfFile.writeText(array.toString()) }.onFailure { Log.w(TAG, "Cannot save the tray", it) }
        }
    }

    private fun readShelf(): List<ShelfItem> = runCatching {
        if (!shelfFile.exists()) return emptyList()
        val array = JSONArray(shelfFile.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            ShelfItem(o.getLong("id"), o.getString("text"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    // endregion

    private fun Int.isDnd() = this != NotificationListenerService.INTERRUPTION_FILTER_ALL && this != NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN

    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, Bitmap?>()

    fun appLabel(packageName: String): String = labels.getOrPut(packageName) {
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
    }

    fun appIcon(packageName: String): Bitmap? = icons.getOrPut(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX) }.getOrNull()
    }

    private val smallIcons = HashMap<String, Bitmap?>()

    /** Progress updates come often: an app's small icon is drawn once per resource, not on every update. */
    private fun smallIcon(packageName: String, icon: Icon?): Bitmap? {
        if (icon == null) return null
        if (icon.type != Icon.TYPE_RESOURCE) return bitmapOf(icon)
        return smallIcons.getOrPut("$packageName/${icon.resId}") { bitmapOf(icon) }
    }

    private fun bitmapOf(icon: Icon?): Bitmap? = icon?.let { runCatching { it.loadDrawable(context)?.toBitmap(ICON_PX, ICON_PX) }.getOrNull() }

    private companion object {
        const val TAG = "IslandHub"
        const val ICON_PX = 96
        const val SHELF_SIZE = 20
        const val EVENT_AHEAD_MS = 30 * 60_000L
        const val EVENT_GRACE_MS = 5 * 60_000L

        val ClockApps = setOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage", "com.oneplus.deskclock")

        val Headphones = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
