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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The media session that plays, or played last: title, artwork and position, with its controls.
 * Android hands out the sessions only to apps with notification access.
 *
 * Each session's metadata is kept as its callback delivers it: asking the session again would copy the
 * full-size artwork across processes on every play, pause or seek. The artwork is scaled down once per
 * track, off the main thread, and its colour is taken from the small copy.
 */
class MediaTracker(private val context: Context, private val scope: CoroutineScope) {

    private val state = MutableStateFlow<MediaInfo?>(null)
    val current: StateFlow<MediaInfo?> = state.asStateFlow()

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val component get() = ComponentName(context, NotificationListener::class.java)
    private var tracked: List<Session> = emptyList()
    private var started = false

    /** One followed session, with what it last reported. */
    private inner class Session(val controller: MediaController) : MediaController.Callback() {
        var metadata: MediaMetadata? = controller.metadata
        var playback: PlaybackState? = controller.playbackState
        var art: Bitmap? = null
        var accent: Int = 0
        var changedAt: Long = System.currentTimeMillis()
        private var artFor: MediaMetadata? = null
        private var artJob: Job? = null

        val playing: Boolean get() = playback?.isPlaying() == true

        init {
            loadArt()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val wasPlaying = playing
            playback = state
            if (playing != wasPlaying) changedAt = System.currentTimeMillis()
            publish()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            this.metadata = metadata
            loadArt()
            publish()
        }

        override fun onSessionDestroyed() {
            follow(runCatching { manager.getActiveSessions(component) }.getOrNull().orEmpty())
        }

        /** A new track: its artwork, small enough for the island, and the colour of it. */
        private fun loadArt() {
            val meta = metadata
            if (meta === artFor) return
            artFor = meta
            artJob?.cancel()
            val source = meta?.let {
                it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }
            if (source == null) {
                art = null
                accent = 0
                return
            }
            artJob = scope.launch {
                val (small, color) = withContext(Dispatchers.Default) { runCatching { shrink(source) }.getOrNull() } ?: return@launch
                art = small
                accent = color
                publish()
            }
        }

        fun release() {
            artJob?.cancel()
            controller.unregisterCallback(this)
        }
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
        tracked.forEach { it.release() }
        tracked = emptyList()
        state.value = null
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> follow(list.orEmpty()) }

    private fun follow(list: List<MediaController>) {
        if (!started) return
        // Sessions that stay keep what they know; only new ones are asked once.
        val previous = tracked.associateBy { it.controller.sessionToken }
        val next = list.map { controller ->
            previous[controller.sessionToken] ?: Session(controller).also { controller.registerCallback(it, main) }
        }
        previous.values.filter { old -> next.none { it === old } }.forEach { it.release() }
        tracked = next
        publish()
    }

    private fun publish() {
        // The one that plays; else the one that stopped last. The system lists the most recent first.
        val chosen = tracked.firstOrNull { it.playing && it.metadata != null }
            ?: tracked.filter { it.metadata != null }.maxByOrNull { it.changedAt }
        val info = chosen?.let(::infoOf)
        if (info != state.value) state.value = info
    }

    private fun infoOf(session: Session): MediaInfo? {
        val meta = session.metadata ?: return null
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val playback = session.playback
        return MediaInfo(
            packageName = session.controller.packageName,
            title = title,
            artist = artist,
            art = session.art,
            accent = session.accent,
            playing = session.playing,
            position = playback?.position ?: 0L,
            positionAt = playback?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = playback?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
            changedAt = session.changedAt,
            controller = session.controller,
            sessionActivity = session.controller.sessionActivity,
        )
    }

    private companion object {
        const val TAG = "MediaTracker"

        /** The island shows artwork at 52 dp at most; this is enough for any screen density. */
        const val ART_PX = 192

        /** A copy of at most [ART_PX] on its longer side, and its average colour brightened for black. */
        fun shrink(source: Bitmap): Pair<Bitmap, Int> {
            val software = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
            val scale = ART_PX.toFloat() / maxOf(software.width, software.height)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(software, (software.width * scale).toInt().coerceAtLeast(1), (software.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                software
            }
            val pixel = Bitmap.createScaledBitmap(small, 1, 1, true).getPixel(0, 0)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(pixel, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.35f)
            hsv[2] = hsv[2].coerceAtLeast(0.85f)
            return small to android.graphics.Color.HSVToColor(hsv)
        }

        fun PlaybackState.isPlaying() =
            state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING
    }
}
