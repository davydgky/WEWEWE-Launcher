package io.github.davydgky.launcher.island

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.davydgky.launcher.notifications.NotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The media session that plays, or played last: title, artwork and position, with its controls.
 * Android hands out the sessions only to apps with notification access.
 */
class MediaTracker(private val context: Context) {

    private val state = MutableStateFlow<MediaInfo?>(null)
    val current: StateFlow<MediaInfo?> = state.asStateFlow()

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val component get() = ComponentName(context, NotificationListener::class.java)
    private var controllers: List<MediaController> = emptyList()
    private var started = false
    /** When each session last started or stopped playing, by package. */
    private val changedAt = HashMap<String, Long>()
    private val wasPlaying = HashMap<String, Boolean>()
    private var artCache: Pair<Bitmap, Int>? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> follow(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = follow(runCatching { manager.getActiveSessions(component) }.getOrNull().orEmpty())
    }

    fun start() {
        if (started) return
        val sessions = try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            // No notification access (yet): tried again when the listener connects.
            Log.i(TAG, "No access to media sessions")
            return
        }
        started = true
        manager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
        follow(sessions)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        state.value = null
    }

    private fun follow(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        list.forEach { it.registerCallback(callback, main) }
        publish()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        controllers.forEach { c ->
            val playing = c.playbackState?.isPlaying() == true
            if (wasPlaying[c.packageName] != playing) {
                wasPlaying[c.packageName] = playing
                changedAt[c.packageName] = now
            }
        }
        // The one that plays; else the one that stopped last. The system lists the most recent first.
        val chosen = controllers.firstOrNull { it.playbackState?.isPlaying() == true }
            ?: controllers.filter { it.metadata != null }.maxByOrNull { changedAt[it.packageName] ?: 0L }
        state.value = chosen?.let(::infoOf)
    }

    private fun infoOf(c: MediaController): MediaInfo? {
        val meta = c.metadata ?: return null
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val playback = c.playbackState
        return MediaInfo(
            packageName = c.packageName,
            title = title,
            artist = artist,
            art = art,
            accent = art?.let(::accentOf) ?: 0,
            playing = playback?.isPlaying() == true,
            position = playback?.position ?: 0L,
            positionAt = playback?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = playback?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
            changedAt = changedAt[c.packageName] ?: System.currentTimeMillis(),
            controller = c,
            sessionActivity = c.sessionActivity,
        )
    }

    /** The artwork's average colour, brightened so it shows on the black island. */
    private fun accentOf(art: Bitmap): Int {
        artCache?.let { (bitmap, color) -> if (bitmap === art) return color }
        val color = runCatching {
            val source = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
            val pixel = Bitmap.createScaledBitmap(source, 1, 1, true).getPixel(0, 0)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(pixel, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.35f)
            hsv[2] = hsv[2].coerceAtLeast(0.85f)
            android.graphics.Color.HSVToColor(hsv)
        }.getOrDefault(0)
        artCache = art to color
        return color
    }

    private fun PlaybackState.isPlaying() = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING

    private companion object {
        const val TAG = "MediaTracker"
    }
}
