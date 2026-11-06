package io.github.davydgky.launcher.search

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Icons and thumbnails for results, decoded off the main thread and cached. App icons are preloaded
 * so the first frame of the search already shows them.
 */
class IconCache(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val density = context.resources.displayMetrics.densityDpi
    private val iconPx = (ICON_DP * context.resources.displayMetrics.density).toInt()

    /** Thumbnails, contact photos and shortcut icons. */
    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** App icons on their own, so scrolling through photos in the search never pushes them out. */
    private val appCache = object : LruCache<String, ImageBitmap>(MAX_APP_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /**
     * The last real icon of every app, on disk: when the system hands out its placeholder robot (an
     * app in the middle of an update, resources that cannot be read at that moment), the icon from
     * before is shown instead, as the Pixel launcher does with its icon database.
     */
    private val diskDir = File(context.cacheDir, "app_icons").apply { mkdirs() }
    /** Keys written to disk in this run; once each is enough. */
    private val written = ConcurrentHashMap.newKeySet<String>()

    fun cached(key: String): ImageBitmap? = appCache.get(key) ?: cache.get(key)

    /** Changes whenever icons were dropped, so the ones on screen are loaded again. */
    var generation by mutableIntStateOf(0)
        private set

    /** An app was updated or changed its icon: its icons and shortcut icons are loaded again. */
    fun evictPackage(packageName: String) {
        appCache.snapshot().keys.filter { "/$packageName/" in it }.forEach(appCache::remove)
        cache.snapshot().keys.filter { "/$packageName/" in it }.forEach(cache::remove)
        written.removeAll { "/$packageName/" in it }
        generation++
    }

    suspend fun preloadApps(apps: List<AppTarget>) = withContext(Dispatchers.IO) {
        apps.forEach { app -> if (appCache.get(app.key) == null) loadAppIcon(app) }
    }

    suspend fun load(key: String, loader: () -> ImageBitmap?): ImageBitmap? {
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching(loader).getOrNull()?.also { cache.put(key, it) }
        }
    }

    /** An app icon, and whether it is the app's own (false: the earlier one from disk, or the robot). */
    class AppIcon(val bitmap: ImageBitmap, val real: Boolean)

    fun loadApp(app: AppTarget): ImageBitmap? = loadAppIcon(app)?.bitmap

    /**
     * Only real icons are kept in memory and on disk; anything else is shown for now and asked for
     * again later ([rememberAppIcon]).
     */
    fun loadAppIcon(app: AppTarget): AppIcon? {
        appCache.get(app.key)?.let { return AppIcon(it, true) }
        return runCatching {
            val real = realIcon(app, app.info) ?: freshInfo(app)?.let { realIcon(app, it) }
            if (real != null) {
                appCache.put(app.key, real)
                saveToDisk(app.key, real)
                AppIcon(real, true)
            } else {
                AppIcon(fromDisk(app.key) ?: app.info.getBadgedIcon(density).render(), false)
            }
        }.onFailure { Log.w(TAG, "Cannot load the icon of ${app.key}", it) }.getOrNull()
    }

    /** The app's icon, or null if the system gave its placeholder robot instead. */
    private fun realIcon(app: AppTarget, info: LauncherActivityInfo): ImageBitmap? {
        val plain = info.getIcon(density).render()
        if (plain.asAndroidBitmap().sameAs(placeholder)) return null
        themedIcon(app, info)?.let { return it.render() }
        return if (app.isWorkProfile) info.getBadgedIcon(density).render() else plain
    }

    /** The entry may be from before an update, when the app's files were being replaced. */
    private fun freshInfo(app: AppTarget): LauncherActivityInfo? = runCatching {
        launcherApps.getActivityList(app.packageName, app.user).firstOrNull { it.componentName == app.component }
    }.getOrNull()

    /** The system's robot, drawn like every icon, to recognise it. */
    private val placeholder: Bitmap by lazy { context.packageManager.defaultActivityIcon.render().asAndroidBitmap() }

    private fun diskFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(diskDir, "$digest.png")
    }

    private fun saveToDisk(key: String, icon: ImageBitmap) {
        if (!written.add(key)) return
        runCatching {
            val file = diskFile(key)
            val tmp = File(diskDir, file.name + ".tmp")
            tmp.outputStream().use { icon.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { Log.w(TAG, "Cannot keep the icon of $key", it) }
    }

    private fun fromDisk(key: String): ImageBitmap? = runCatching {
        val file = diskFile(key)
        if (!file.exists()) return null
        BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also { it.prepareToDraw() }
    }.getOrNull()

    /** Icons in the wallpaper colors, for apps that have a monochrome icon. */
    @Volatile var themed = false
        private set

    fun setThemed(enabled: Boolean) {
        if (enabled == themed) return
        themed = enabled
        appCache.evictAll()
        written.clear()
        generation++
    }

    /**
     * The app's monochrome icon on a tinted background, in the system's dynamic colors as on Pixel
     * phones (light: light background, dark symbol; dark the other way round); null if it has none.
     */
    private fun themedIcon(app: AppTarget, info: LauncherActivityInfo): Drawable? {
        if (!themed) return null
        val icon = info.getIcon(density) as? AdaptiveIconDrawable ?: return null
        val mono = icon.monochrome ?: return null
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val background = context.getColor(if (dark) android.R.color.system_neutral1_800 else android.R.color.system_accent1_100)
        val symbol = context.getColor(if (dark) android.R.color.system_accent1_100 else android.R.color.system_neutral2_700)
        val drawable = AdaptiveIconDrawable(ColorDrawable(background), mono.mutate().apply { setTint(symbol) })
        return context.packageManager.getUserBadgedIcon(drawable, app.user)
    }

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
        const val TAG = "IconCache"
        const val ICON_DP = 60
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MAX_APP_BYTES = 40 * 1024 * 1024
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

/**
 * An app's icon. When the system could not give the real one (the robot, or the earlier icon from
 * disk is shown), it asks again a few times, so a passing moment does not leave the robot for good.
 */
@Composable
fun rememberAppIcon(cache: IconCache, app: AppTarget): State<ImageBitmap?> {
    val generation = cache.generation
    val state = remember(cache, app.key, generation) { mutableStateOf(cache.cached(app.key)) }
    LaunchedEffect(cache, app, generation) {
        if (state.value != null && cache.cached(app.key) != null) return@LaunchedEffect
        for (wait in APP_ICON_RETRIES_MS) {
            val icon = withContext(Dispatchers.IO) { cache.loadAppIcon(app) } ?: return@LaunchedEffect
            state.value = icon.bitmap
            if (icon.real) return@LaunchedEffect
            delay(wait)
        }
    }
    return state
}

private val APP_ICON_RETRIES_MS = listOf(2_000L, 10_000L, 60_000L, 300_000L)

