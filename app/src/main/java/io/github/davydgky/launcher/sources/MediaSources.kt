package io.github.davydgky.launcher.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.Log
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.index.ContentIndexJob
import io.github.davydgky.launcher.index.DocumentEntry
import io.github.davydgky.launcher.index.ImageLabelNames
import io.github.davydgky.launcher.index.IndexStore
import io.github.davydgky.launcher.index.MediaCatalog
import io.github.davydgky.launcher.search.FileTarget
import io.github.davydgky.launcher.search.ImageTarget
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pictures on the device, findable by what they show ("Hund", "Strand", "Kuchen"), the text in them,
 * their album ("Screenshots", "WhatsApp Images") and when they were taken ("Oktober 2025"). Album and
 * date work at once; text and content are recognised on the device by [ContentIndexJob] while it
 * charges. Needs READ_MEDIA_IMAGES (or the user's partial selection).
 */
class ImagesSource(private val context: Context, private val store: IndexStore) : SearchSource(ID) {

    override fun missing(context: Context): Requirement? = if (hasAccess(context)) null else Requirement.Images

    override suspend fun load(): List<SearchItem> {
        val images = MediaCatalog.images(context)
        val texts = store.imageTexts()
        val labels = store.imageLabels()
        store.retainImages(images.mapTo(HashSet()) { it.id })
        val german = Locale.getDefault().language == "de"
        val zone = ZoneId.systemDefault()
        var pending = 0
        val items = ArrayList<SearchItem>(images.size)
        for (image in images) {
            val text = texts[image.id]?.takeIf { it.version == image.version }?.text?.trim()
            val found = labels[image.id]?.takeIf { it.version == image.version }?.text?.split(',')?.filter { it.isNotBlank() }
            if (image.worthRecognising && (text == null || found == null)) pending++
            val shown = found.orEmpty().map { label -> (if (german) ImageLabelNames.german(label) else null) ?: label }
            val date = DateUtils.formatDateTime(context, image.dateTaken, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
            val taken = Instant.ofEpochMilli(image.dateTaken).atZone(zone)
            items += SearchItem(
                key = "image:${image.id}",
                kind = SourceKind.Image,
                title = text?.lineSequence()?.firstOrNull()?.take(TITLE_LENGTH)?.takeIf { it.isNotBlank() }
                    ?: shown.take(3).joinToString(", ").ifEmpty { image.album.ifEmpty { image.name } },
                subtitle = listOf(date, image.album).filter { it.isNotBlank() }.joinToString(" · "),
                keywords = buildList {
                    if (image.album.isNotBlank()) add(image.album)
                    found.orEmpty().forEach { label ->
                        add(label)
                        ImageLabelNames.german(label)?.let(::add)
                    }
                    // "Oktober 2025", "october 2025": month in both languages, and the year.
                    add(taken.month.getDisplayName(TextStyle.FULL, Locale.GERMAN))
                    add(taken.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    add(taken.year.toString())
                },
                body = text.orEmpty(),
                payload = ImageTarget(image.uri, image.dateTaken),
            )
        }
        if (pending > 0) {
            Log.i(ID, "$pending pictures wait for recognition while charging")
            ContentIndexJob.schedule(context)
        }
        return items
    }

    override fun observe(onChange: () -> Unit) = context.observeUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, onChange)

    companion object {
        const val ID = "images"
        private const val TITLE_LENGTH = 80

        fun hasAccess(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Documents anywhere in shared storage, by name and folder; text, Markdown and PDF files also by
 * content (read by [ContentIndexJob] while charging). Needs "all files access".
 */
class DocumentsSource(
    private val context: Context,
    private val store: IndexStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : SearchSource(ID) {

    override fun missing(context: Context): Requirement? =
        if (Environment.isExternalStorageManager()) null else Requirement.AllFiles

    override suspend fun load(): List<SearchItem> {
        val now = clock()
        val documents = MediaCatalog.documents(context)
        val texts = store.docTexts()
        store.retainDocs(documents.mapTo(HashSet()) { it.id })
        var pending = 0
        val items = documents.map { doc ->
            val stored = texts[doc.id]?.takeIf { it.version == doc.version }
            if (stored == null && doc.hasReadableText) pending++
            SearchItem(
                key = "document:${doc.id}",
                kind = SourceKind.Document,
                title = doc.name,
                subtitle = doc.folder,
                body = stored?.text.orEmpty(),
                boost = recency(doc, now),
                payload = FileTarget(doc.uri, doc.mimeType, doc.extension),
            )
        }
        if (pending > 0) ContentIndexJob.schedule(context)
        return items
    }

    private fun recency(doc: DocumentEntry, now: Long): Float {
        val days = (now - doc.modified).coerceAtLeast(0) / DateUtils.DAY_IN_MILLIS.toFloat()
        return if (days > 30f) 0f else 0.3f * (1f - days / 30f)
    }

    override fun observe(onChange: () -> Unit) =
        context.observeUri(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), onChange)

    companion object {
        const val ID = "documents"
    }
}
