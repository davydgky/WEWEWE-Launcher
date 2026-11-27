package io.github.davydgky.launcher.island

import android.app.PendingIntent
import android.graphics.Bitmap
import android.media.session.MediaController
import io.github.davydgky.launcher.core.LiveKind

/**
 * Something that runs for a while and that the island shows next to the camera: a timer, a call,
 * navigation, a download, a Live Update of any app. Read from ongoing notifications.
 */
data class LiveItem(
    val key: String,
    val kind: LiveKind,
    val packageName: String,
    val title: String,
    val text: String,
    /** The notification's small icon, drawn white on the island. */
    val icon: Bitmap?,
    /** Its accent colour, for the progress bar and the icon. */
    val color: Int,
    /** 0..1, or null without progress. */
    val progress: Float?,
    val indeterminate: Boolean = false,
    /** Wall time the chronometer counts from (up) or to (down); null without one. */
    val chronometer: Long? = null,
    val countDown: Boolean = false,
    /** The short text Android shows in the status bar chip of a Live Update ("5 Min.", "12 %"). */
    val shortText: String? = null,
    val contentIntent: PendingIntent? = null,
    val updatedAt: Long,
    val running: Boolean = true,
)

/** What a media session is playing. */
data class MediaInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    /** Average colour of the artwork, for the bars and the progress; 0 if there is no artwork. */
    val accent: Int,
    val playing: Boolean,
    /** Position in ms at [positionAt] (elapsedRealtime), advancing with [speed] while playing. */
    val position: Long,
    val positionAt: Long,
    val speed: Float,
    val duration: Long,
    /** When it last started or stopped playing (wall time). */
    val changedAt: Long,
    val controller: MediaController,
    val sessionActivity: PendingIntent?,
) {
    fun positionNow(nowElapsed: Long): Long {
        val moved = if (playing) ((nowElapsed - positionAt) * speed).toLong() else 0L
        val p = position + moved
        return if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
    }
}

enum class PeekKind { Notification, Reminder, Charging, BatteryLow, Ringer, Device, Done }

/** A short message that widens the island for a few seconds, like a notification arriving. */
data class Peek(
    val id: Long,
    val kind: PeekKind,
    val title: String,
    val text: String,
    /** A picture for the left side: the sender's avatar or the app's icon. */
    val image: Bitmap? = null,
    /** A drawable resource instead of [image], drawn in [tint]. */
    val iconRes: Int = 0,
    val tint: Int = 0,
    val notificationKey: String? = null,
    val contentIntent: PendingIntent? = null,
    val autoCancel: Boolean = false,
    /** For charging: the battery level 0..100, shown as a ring. */
    val level: Int? = null,
    val durationMs: Long = 4_000,
    /** Where it comes from (the app's name), shown small above it. */
    val source: String = "",
    /** The app's icon beside [source]. */
    val sourceIcon: Bitmap? = null,
)

/** Something parked in the island's tray, from "Share" in any app. */
data class ShelfItem(val id: Long, val text: String, val addedAt: Long) {
    val isLink: Boolean get() = text.startsWith("http://") || text.startsWith("https://")
}

/** The next calendar event that starts soon. */
data class SoonEvent(val title: String, val begin: Long, val color: Int)

data class BatteryInfo(val level: Int = -1, val charging: Boolean = false)
