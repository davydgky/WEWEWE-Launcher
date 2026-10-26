package io.github.davydgky.launcher.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.index.ContentIndexJob
import io.github.davydgky.launcher.index.DocumentEntry
import io.github.davydgky.launcher.index.IndexStore
import io.github.davydgky.launcher.index.MediaCatalog
import io.github.davydgky.launcher.search.FileTarget
import io.github.davydgky.launcher.search.ImageTarget

/**
 * Pictures with text in them. The text is recognised by [ContentIndexJob] while the device charges;
 * this source only lists pictures whose text is already known. Needs READ_MEDIA_IMAGES (or the
 * user's partial selection).
 */
class ImagesSource(private val context: Context, private val store: IndexStore) : SearchSource(ID) {

    override fun missing(context: Context): Requirement? = if (hasAccess(context)) null else Requirement.Images

    override suspend fun load(): List<SearchItem> {
        val images = MediaCatalog.images(context)
        val texts = store.imageTexts()
        store.retainImages(images.mapTo(HashSet()) { it.id })
        var pending = 0
        val items = ArrayList<SearchItem>()
        for (image in images) {
            val stored = texts[image.id]
            if (stored == null || stored.version != image.version) {
                if (image.worthRecognising) pending++
                continue
            }
            val text = stored.text.trim()
            if (text.isEmpty()) continue
            val date = DateUtils.formatDateTime(context, image.dateTaken, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
            items += SearchItem(
                key = "image:${image.id}",
                kind = SourceKind.Image,
                title = text.lineSequence().first().take(TITLE_LENGTH),
                subtitle = listOf(date, image.album).filter { it.isNotBlank() }.joinToString(" · "),
                body = text,
                payload = ImageTarget(image.uri, image.dateTaken),
            )
        }
        if (pending > 0) ContentIndexJob.schedule(context)
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
