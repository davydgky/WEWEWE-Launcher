package io.github.davydgky.launcher.index

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Text that is expensive to obtain, together with the version of the file it was taken from. */
class StoredText(val version: Long, val text: String)

/**
 * Persistent part of the index: text recognised in pictures, text extracted from documents and the
 * contents of note files. Everything else is cheap to read again at start-up.
 */
class IndexStore(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE image_text (id INTEGER PRIMARY KEY, version INTEGER NOT NULL, text TEXT NOT NULL)")
        db.execSQL("CREATE TABLE doc_text (id INTEGER PRIMARY KEY, version INTEGER NOT NULL, text TEXT NOT NULL)")
        db.execSQL("CREATE TABLE note_text (uri TEXT PRIMARY KEY, version INTEGER NOT NULL, text TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf("image_text", "doc_text", "note_text").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    fun imageTexts(): Map<Long, StoredText> = readById("image_text")
    fun putImageText(id: Long, version: Long, text: String) = put("image_text", "id", id, version, text)
    fun retainImages(ids: Set<Long>) = retainIds("image_text", ids)

    fun docTexts(): Map<Long, StoredText> = readById("doc_text")
    fun putDocText(id: Long, version: Long, text: String) = put("doc_text", "id", id, version, text)
    fun retainDocs(ids: Set<Long>) = retainIds("doc_text", ids)

    fun noteTexts(): Map<String, StoredText> {
        val out = HashMap<String, StoredText>()
        readableDatabase.rawQuery("SELECT uri, version, text FROM note_text", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = StoredText(c.getLong(1), c.getString(2))
        }
        return out
    }

    fun putNoteText(uri: String, version: Long, text: String) = put("note_text", "uri", uri, version, text)

    fun retainNotes(uris: Set<String>) {
        val stale = noteTexts().keys - uris
        if (stale.isEmpty()) return
        writableDatabase.transaction { stale.forEach { delete("note_text", "uri = ?", arrayOf(it)) } }
    }

    fun clear() {
        writableDatabase.transaction { listOf("image_text", "doc_text", "note_text").forEach { delete(it, null, null) } }
    }

    private fun readById(table: String): Map<Long, StoredText> {
        val out = HashMap<Long, StoredText>()
        readableDatabase.rawQuery("SELECT id, version, text FROM $table", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = StoredText(c.getLong(1), c.getString(2))
        }
        return out
    }

    private fun put(table: String, keyColumn: String, key: Any, version: Long, text: String) {
        val values = ContentValues().apply {
            when (key) {
                is Long -> put(keyColumn, key)
                else -> put(keyColumn, key.toString())
            }
            put("version", version)
            put("text", text)
        }
        writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun retainIds(table: String, ids: Set<Long>) {
        val stale = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT id FROM $table", null).use { c ->
            while (c.moveToNext()) c.getLong(0).let { if (it !in ids) stale += it }
        }
        if (stale.isEmpty()) return
        writableDatabase.transaction { stale.forEach { delete(table, "id = ?", arrayOf(it.toString())) } }
    }

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val NAME = "content_index.db"
        const val VERSION = 1
    }
}
