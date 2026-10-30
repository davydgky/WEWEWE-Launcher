package io.github.davydgky.launcher.search

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.UserHandle

/** What a search result opens; carried as [io.github.davydgky.launcher.core.SearchItem.payload]. */
sealed interface Target

class AppTarget(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val isWorkProfile: Boolean,
    val info: LauncherActivityInfo,
    /** Its profile is paused (work apps turned off): shown greyed, a tap offers to turn it back on. */
    val paused: Boolean = false,
    /** Belongs to the private space: never placed on the home screen by itself. */
    val isPrivate: Boolean = false,
    /** Hidden by the user: not in the App Library, the search or suggestions. */
    val hidden: Boolean = false,
    /** The app's own name, when the user gave it another one ([label]). */
    val originalLabel: String = label,
) : Target {
    val packageName: String get() = component.packageName

    fun with(label: String, hidden: Boolean) = AppTarget(key, label, component, user, isWorkProfile, info, paused, isPrivate, hidden, originalLabel)
}

class ShortcutTarget(val key: String, val info: ShortcutInfo, val appLabel: String) : Target

class ContactTarget(val lookupUri: Uri, val photoUri: Uri?, val phone: String?, val initials: String, val email: String? = null) : Target

class EventTarget(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
) : Target

class WebTarget(val url: String, val isBookmark: Boolean) : Target

/** A note or document. [obsidianUri] opens the note in Obsidian when it lives in a vault. */
class FileTarget(val uri: Uri, val mimeType: String, val extension: String, val obsidianUri: Uri? = null) : Target

class ImageTarget(val uri: Uri, val dateTaken: Long) : Target

/** A page of the system settings ([action]), or something the launcher does itself. */
class SettingTarget(val action: String?, val special: Special?) : Target {
    enum class Special { Torch, Launcher }
}

/** A calculation or conversion; tapping copies [copyText]. */
class AnswerTarget(val copyText: String) : Target
