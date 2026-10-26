package io.github.davydgky.launcher.index

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns

/** A picture as MediaStore lists it. [version] is MediaStore's generation of its last change. */
class ImageEntry(
    val id: Long,
    val uri: Uri,
    val name: String,
    val album: String,
    val dateTaken: Long,
    val version: Long,
    val width: Int,
    val height: Int,
) {
    /** Icons and tiny images carry no readable text. */
    val worthRecognising: Boolean get() = minOf(width, height) >= MIN_SIDE || width == 0 || height == 0

    private companion object {
        const val MIN_SIDE = 200
    }
}

/** A document of another app; only visible with "all files access". */
class DocumentEntry(
    val id: Long,
    val uri: Uri,
    val name: String,
    val folder: String,
    val mimeType: String,
    val modified: Long,
    val size: Long,
    val version: Long,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** Whether the content job can read text out of it. */
    val hasReadableText: Boolean get() = extension in TEXT_EXTENSIONS || extension == "pdf"

    companion object {
        val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "org", "log")
    }
}

/** Queries MediaStore; shared by the search sources and the background content job. */
object MediaCatalog {

    fun images(context: Context): List<ImageEntry> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.GENERATION_MODIFIED,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
        )
        val out = ArrayList<ImageEntry>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += ImageEntry(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    name = c.getString(1).orEmpty(),
                    album = c.getString(2).orEmpty(),
                    dateTaken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000),
                    version = c.getLong(5),
                    width = c.getInt(6),
                    height = c.getInt(7),
                )
            }
        }
        return out
    }

    fun documents(context: Context): List<DocumentEntry> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.RELATIVE_PATH, FileColumns.MIME_TYPE,
            FileColumns.DATE_MODIFIED, FileColumns.SIZE, FileColumns.GENERATION_MODIFIED,
        )
        val mimePlaceholders = DOCUMENT_MIME_TYPES.joinToString(",") { "?" }
        val extensionClauses = DOCUMENT_EXTENSIONS.joinToString(" OR ") { "${FileColumns.DISPLAY_NAME} LIKE ?" }
        val selection = "(${FileColumns.MIME_TYPE} IN ($mimePlaceholders) OR $extensionClauses) AND " +
            "${FileColumns.RELATIVE_PATH} NOT LIKE ?"
        val args = DOCUMENT_MIME_TYPES + DOCUMENT_EXTENSIONS.map { "%.$it" } + "Android/%"
        val out = ArrayList<DocumentEntry>()
        context.contentResolver.query(collection, projection, selection, args.toTypedArray(), "${FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                out += DocumentEntry(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = name,
                    folder = c.getString(2).orEmpty().removeSuffix("/"),
                    mimeType = c.getString(3) ?: "application/octet-stream",
                    modified = c.getLong(4) * 1000,
                    size = c.getLong(5),
                    version = c.getLong(6),
                )
            }
        }
        return out
    }

    private val DOCUMENT_MIME_TYPES = listOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/rtf",
        "application/epub+zip",
        "text/plain",
        "text/markdown",
        "text/csv",
    )

    // Files whose MIME type MediaStore does not know.
    private val DOCUMENT_EXTENSIONS = listOf("md", "markdown", "org")
}
