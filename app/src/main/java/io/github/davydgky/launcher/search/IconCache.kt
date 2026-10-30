package io.github.davydgky.launcher.search

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Icons and thumbnails for results, decoded off the main thread and cached. App icons are preloaded
 * so the first frame of the search already shows them.
 */
class IconCache(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val density = context.resources.displayMetrics.densityDpi
    private val iconPx = (ICON_DP * context.resources.displayMetrics.density).toInt()

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(key: String): ImageBitmap? = cache.get(key)

    /** Changes whenever icons were dropped, so the ones on screen are loaded again. */
    var generation by mutableIntStateOf(0)
        private set

    /** An app was updated or changed its icon: its icons and shortcut icons are loaded again. */
    fun evictPackage(packageName: String) {
        val stale = cache.snapshot().keys.filter { "/$packageName/" in it }
        if (stale.isEmpty()) return
        stale.forEach(cache::remove)
        generation++
    }

    suspend fun preloadApps(apps: List<AppTarget>) = withContext(Dispatchers.IO) {
        apps.forEach { app -> if (cache.get(app.key) == null) loadApp(app) }
    }

    suspend fun load(key: String, loader: () -> ImageBitmap?): ImageBitmap? {
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching(loader).getOrNull()?.also { cache.put(key, it) }
        }
    }

    fun loadApp(app: AppTarget): ImageBitmap? = cache.get(app.key) ?: runCatching {
        app.info.getBadgedIcon(density).render()
    }.getOrNull()?.also { cache.put(app.key, it) }

    fun shortcutIcon(shortcut: ShortcutTarget): ImageBitmap? =
        launcherApps.getShortcutBadgedIconDrawable(shortcut.info, density)?.render()

    fun thumbnail(uri: Uri, sizePx: Int = iconPx * 2): ImageBitmap =
        context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null).asImageBitmap()

    fun drawable(drawable: Drawable): ImageBitmap = drawable.render()

    private fun Drawable.render(): ImageBitmap {
        val bitmap: Bitmap = if (intrinsicWidth > 0 && intrinsicHeight > 0) {
            toBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        } else {
            createBitmap(iconPx, iconPx).also { bmp ->
                setBounds(0, 0, iconPx, iconPx)
                draw(Canvas(bmp))
            }
        }
        return bitmap.asImageBitmap().also { it.prepareToDraw() }
    }

    private companion object {
        const val ICON_DP = 60
        const val MAX_BYTES = 48 * 1024 * 1024
    }
}

/**
 * An icon from the cache, or loaded in the background; null while loading. The state is keyed by
 * [key], so a list row that shows another result never keeps the previous result's icon.
 */
@Composable
fun rememberIcon(cache: IconCache, key: String, loader: () -> ImageBitmap?): State<ImageBitmap?> {
    val generation = cache.generation
    val state = remember(cache, key, generation) { mutableStateOf(cache.cached(key)) }
    LaunchedEffect(cache, key, generation) {
        if (state.value == null) state.value = cache.load(key, loader)
    }
    return state
}
