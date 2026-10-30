package io.github.davydgky.launcher.crash

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.davydgky.launcher.BuildConfig
import java.io.File

/**
 * Keeps crash reports on the device (the settings can share them; nothing is sent by itself) and
 * notices a crash loop: after [LOOP_CRASHES] crashes within [LOOP_WINDOW_MS] the launcher starts in
 * safe mode, without third-party widgets and background work, so the phone stays usable and the next
 * update can arrive. A run that lasts [STABLE_MS] ends the loop.
 */
class CrashGuard(filesDir: File) {

    private val dir = File(filesDir, "crashes")
    private val recent = File(dir, "recent")

    /** True when this start follows a crash loop. */
    val safeMode: Boolean

    init {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val times = runCatching { recent.readLines().mapNotNull { it.trim().toLongOrNull() } }.getOrDefault(emptyList())
            .filter { now - it < LOOP_WINDOW_MS }
        safeMode = times.size >= LOOP_CRASHES
        if (safeMode) Log.w(TAG, "Starting in safe mode after ${times.size} crashes")
    }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }
        // Still running after a while: whatever crashed before is not a loop any more.
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { recent.delete() } }, STABLE_MS)
    }

    /** Saved reports, newest first. */
    fun reports(): List<File> = dir.listFiles { f -> f.name.startsWith("crash-") }.orEmpty().sortedByDescending { it.name }

    fun clear() {
        reports().forEach { it.delete() }
        recent.delete()
    }

    private fun record(thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        recent.appendText("$now\n")
        val report = buildString {
            appendLine("WEWEWE ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(Log.getStackTraceString(error))
        }
        File(dir, "crash-$now.txt").writeText(report)
        reports().drop(MAX_REPORTS).forEach { it.delete() }
    }

    private companion object {
        const val TAG = "CrashGuard"
        const val LOOP_CRASHES = 3
        const val LOOP_WINDOW_MS = 3 * 60_000L
        const val STABLE_MS = 60_000L
        const val MAX_REPORTS = 5
    }
}
