package io.github.davydgky.launcher.sources

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.index.IndexStore
import io.github.davydgky.launcher.search.FileTarget

/**
 * Notes stored as text files in folders the user shared through the Storage Access Framework:
 * Obsidian vaults, Markor, Logseq, Orgzly and Joplin's file sync. Android has no notes API.
 */
class NotesSource(
    private val context: Context,
    private val store: IndexStore,
    private val folders: () -> Set<String>,
) : SearchSource(ID) {

    override fun missing(context: Context): Requirement? = if (accessibleFolders().isEmpty()) Requirement.NoteFolder else null

    // Document providers do not report changes reliably, so folders are scanned again regularly.
    override val refreshIntervalMs: Long = 5 * 60 * 1000L

    private class NoteFile(val uri: Uri, val name: String, val folder: String, val relativePath: String, val modified: Long, val size: Long)

    private fun accessibleFolders(): List<Uri> {
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        return folders().filter { it in granted }.map(Uri::parse)
    }

    override suspend fun load(): List<SearchItem> {
        val stored = store.noteTexts()
        val items = ArrayList<SearchItem>()
        val seen = HashSet<String>()
        for (tree in accessibleFolders()) {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val rootName = displayName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) ?: continue
            val files = ArrayList<NoteFile>()
            var isVault = false
            walk(tree, rootId, rootName, "", 0, files) { isVault = true }
            for (file in files) {
                val key = file.uri.toString()
                seen += key
                val text = stored[key]?.takeIf { it.version == file.modified }?.text
                    ?: readText(file)?.also { store.putNoteText(key, file.modified, it) }
                    ?: continue
                val extension = file.name.substringAfterLast('.', "").lowercase()
                val pathWithoutExtension = file.relativePath.substringBeforeLast('.')
                items += SearchItem(
                    key = "note:$key",
                    kind = SourceKind.Note,
                    title = file.name.substringBeforeLast('.'),
                    subtitle = file.folder,
                    body = text,
                    payload = FileTarget(
                        uri = file.uri,
                        mimeType = mimeFor(extension),
                        extension = extension,
                        obsidianUri = if (isVault) obsidianUri(rootName, pathWithoutExtension) else null,
                    ),
                )
            }
        }
        store.retainNotes(seen)
        return items
    }

    private fun walk(tree: Uri, docId: String, folder: String, relative: String, depth: Int, out: MutableList<NoteFile>, onVault: () -> Unit) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        val directories = ArrayList<Pair<String, String>>()
        try {
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name == ".obsidian" && depth == 0) onVault()
                    // Hidden folders hold app settings, trash and version control, not notes.
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        directories += id to name
                    } else if (name.substringAfterLast('.', "").lowercase() in EXTENSIONS) {
                        out += NoteFile(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            name = name, folder = folder, relativePath = relative + name,
                            modified = c.getLong(3), size = c.getLong(4),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list $children", e)
            return
        }
        if (depth >= MAX_DEPTH) return
        for ((id, name) in directories) walk(tree, id, "$folder/$name", "$relative$name/", depth + 1, out, onVault)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun readText(file: NoteFile): String? {
        if (file.size > MAX_BYTES) return ""
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().decodeToString() }
        }.onFailure { Log.w(TAG, "Cannot read ${file.uri}", it) }.getOrNull()
    }

    companion object {
        const val ID = "notes"
        private const val TAG = "NotesSource"
        private const val MAX_DEPTH = 8
        private const val MAX_BYTES = 512 * 1024L
        private val EXTENSIONS = setOf("md", "markdown", "txt", "org")

        fun mimeFor(extension: String) = when (extension) {
            "md", "markdown" -> "text/markdown"
            "org" -> "text/x-org"
            else -> "text/plain"
        }

        /** Opens a note in Obsidian: vault name and the path inside the vault without extension. */
        fun obsidianUri(vault: String, path: String): Uri = Uri.Builder()
            .scheme("obsidian").authority("open")
            .appendQueryParameter("vault", vault)
            .appendQueryParameter("file", path)
            .build()
    }
}
