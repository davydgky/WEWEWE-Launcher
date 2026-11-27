package io.github.davydgky.launcher.organizer

import android.util.Log
import java.io.File
import java.time.LocalDate

/**
 * Copies of the reminders on the device, so none are lost for good: one per day (the last state of
 * that day, kept two weeks), one before each merge with the other app that would leave fewer open
 * reminders, and the saved file itself if it ever cannot be read. The settings list them to restore.
 */
class OrganizerBackups(filesDir: File) {

    private val dir = File(filesDir, "organizer-backups")

    /** A copy: when, why, and how many open reminders it holds. */
    data class Entry(val file: File, val kind: Kind, val time: Long, val openReminders: Int, val reminders: Int)

    enum class Kind { Daily, BeforeSync, Unreadable }

    /** Keeps today's copy up to date; called with every save. */
    fun daily(text: String) = save("daily-${LocalDate.now()}.json", text) { prune("daily-", DAYS) }

    fun beforeSync(text: String) = save("sync-${System.currentTimeMillis()}.json", text) { prune("sync-", SYNCS) }

    /** The saved file could not be read: it is kept aside instead of being overwritten. */
    fun unreadable(file: File) {
        runCatching {
            dir.mkdirs()
            file.copyTo(File(dir, "unreadable-${System.currentTimeMillis()}.json"), overwrite = true)
        }.onFailure { Log.e(TAG, "Cannot keep the unreadable reminders", it) }
    }

    /** All copies, newest first. */
    fun list(): List<Entry> = dir.listFiles().orEmpty().mapNotNull { file ->
        val kind = when {
            file.name.startsWith("daily-") -> Kind.Daily
            file.name.startsWith("sync-") -> Kind.BeforeSync
            file.name.startsWith("unreadable-") -> Kind.Unreadable
            else -> return@mapNotNull null
        }
        // An unreadable file is listed all the same: it is kept to be looked at, not lost.
        val state = read(file) ?: if (kind == Kind.Unreadable) OrganizerState() else return@mapNotNull null
        Entry(file, kind, file.lastModified(), state.todos.count { !it.done }, state.todos.size)
    }.sortedByDescending { it.time }

    fun read(file: File): OrganizerState? = runCatching { OrganizerCodec.decode(file.readText()) }.getOrNull()

    private fun save(name: String, text: String, after: () -> Unit) {
        runCatching {
            dir.mkdirs()
            File(dir, name).writeText(text)
            after()
        }.onFailure { Log.w(TAG, "Cannot back up the reminders", it) }
    }

    private fun prune(prefix: String, keep: Int) {
        dir.listFiles { f -> f.name.startsWith(prefix) }.orEmpty().sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    private companion object {
        const val TAG = "OrganizerBackups"
        const val DAYS = 14
        const val SYNCS = 10
    }
}
