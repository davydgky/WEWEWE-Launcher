package io.github.davydgky.launcher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import io.github.davydgky.launcher.BuildConfig
import io.github.davydgky.launcher.R
import io.github.davydgky.launcher.data.LauncherSettings
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState

    /** Downloaded and verified; installs after a tap (Android asks for confirmation). */
    data class Ready(val release: Release) : UpdateState

    /** The launcher may install apps only after the user allows it in the system settings. */
    data class NeedsPermission(val release: Release) : UpdateState

    /** Installs on its own as soon as the home screen is no longer in the foreground. */
    data class Scheduled(val release: Release) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(@param:StringRes val message: Int) : UpdateState
}

/**
 * Keeps the launcher up to date from the GitHub releases of [BuildConfig.UPDATE_REPOSITORY]. This class
 * is the only network code of the app; CI checks that nothing else opens a connection.
 *
 * Safety: only HTTPS downloads from the repository's own release URLs are accepted, the file must
 * match GitHub's SHA-256 digest, and it must be signed by the launcher's current key (or a successor in its
 * rotation lineage) before it is handed to the system installer, which checks the signature again.
 *
 * Once the launcher installed itself one time it is the "installer of record" and Android lets it
 * install later updates without asking. Those wait until another app is in the foreground, so an
 * update never restarts the home screen while it is being used.
 */
class AppUpdater(
    private val context: Context,
    private val preferences: LauncherSettings,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val packageManager = context.packageManager
    private val updateDir = File(context.cacheDir, "updates")
    private var job: Job? = null

    /** Confirmation screen from the system installer, kept for when the launcher is in the background. */
    private var pendingConfirmation: Intent? = null
    private var scheduledSessionId: Int? = null

    /** Set by the activity; the installer confirmation can only be shown while the launcher is visible. */
    @Volatile
    var isAppVisible = false

    val currentVersionName: String = BuildConfig.VERSION_NAME
    private val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()

    /** Checks at most every few hours; downloads automatically on Wi-Fi and installs in the background. */
    fun onAppStarted() {
        cleanUpOldDownloads()
        scope.launch {
            val settings = preferences.prefs.first()
            if (!settings.autoUpdate) return@launch
            val last = preferences.lastUpdateCheck()
            if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return@launch
            checkForUpdate(automatic = true)
        }
    }

    /** Runs the next step for the current state: check, download or install. */
    fun performAction() {
        when (val current = _state.value) {
            UpdateState.Idle, UpdateState.UpToDate, is UpdateState.Failed -> checkForUpdate(automatic = false)
            is UpdateState.Available -> download(current.release, automatic = false)
            is UpdateState.Ready, is UpdateState.NeedsPermission, is UpdateState.Scheduled -> install(releaseOf(current)!!)
            UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> Unit
        }
    }

    private fun checkForUpdate(automatic: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            val release = runCatching { fetchLatestRelease() }
                .onFailure { Log.w(TAG, "Update check failed", it) }
                .getOrElse { error ->
                    val message = if (error is ReleasesNotFound) R.string.update_error_not_found else R.string.update_error_network
                    _state.value = if (automatic) UpdateState.Idle else UpdateState.Failed(message)
                    return@launch
                }
            preferences.setLastUpdateCheck(System.currentTimeMillis())
            if (release == null || release.versionCode <= currentVersionCode) {
                _state.value = UpdateState.UpToDate
                return@launch
            }
            _state.value = UpdateState.Available(release)
            if (!automatic || !isMetered()) downloadNow(release, automatic)
        }
    }

    private fun download(release: Release, automatic: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch { downloadNow(release, automatic) }
    }

    private suspend fun downloadNow(release: Release, automatic: Boolean) {
        val file = apkFile(release)
        val ok = runCatching {
            if (!verified(file, release)) {
                fetchApk(release, file)
                if (!verified(file, release)) {
                    file.delete()
                    error("downloaded file failed verification")
                }
            }
        }.onFailure { Log.w(TAG, "Update download failed", it) }.isSuccess
        if (!ok) {
            _state.value = if (automatic) UpdateState.Available(release) else UpdateState.Failed(R.string.update_error_download)
            return
        }
        if (automatic && canInstallSilently()) {
            schedule(release)
        } else {
            _state.value = UpdateState.Ready(release)
        }
    }

    private suspend fun fetchLatestRelease(): Release? = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "WEWEWE-Launcher/${BuildConfig.VERSION_NAME}")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                // A private repository answers like one without releases; neither offers an update.
                HttpURLConnection.HTTP_NOT_FOUND -> throw ReleasesNotFound()
                else -> throw IOException("HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            ReleaseParser.parse(body, BuildConfig.UPDATE_REPOSITORY)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun fetchApk(release: Release, target: File) = withContext(Dispatchers.IO) {
        updateDir.mkdirs()
        val partial = File(updateDir, target.name + ".part")
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "WEWEWE-Launcher/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            // GitHub redirects to its download host; refuse anything that ends up without TLS.
            if (connection.url.protocol != "https") throw IOException("insecure redirect")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != lastReported) {
                            lastReported = percent
                            _state.value = UpdateState.Downloading(release, (percent / 100f).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
            if (release.sha256 != null && hash != release.sha256) {
                partial.delete()
                throw IOException("SHA-256 mismatch")
            }
            if (!partial.renameTo(target)) throw IOException("could not store update")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun verified(file: File, release: Release): Boolean =
        withContext(Dispatchers.IO) { file.exists() && isValidUpdate(file, release) }

    /** Same package, newer version, and signed by our key or its successor in the rotation lineage. */
    private fun isValidUpdate(file: File, release: Release): Boolean {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = packageManager.getPackageArchiveInfo(file.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        if (archive.longVersionCode != release.versionCode || archive.longVersionCode <= currentVersionCode) return false
        val archiveSigning = archive.signingInfo ?: return false
        val ownSigning = packageManager.getPackageInfo(context.packageName, flags).signingInfo ?: return false
        if (archiveSigning.hasMultipleSigners() || ownSigning.hasMultipleSigners()) return false
        val trusted: Set<Signature> = archiveSigning.signingCertificateHistory.toSet()
        return ownSigning.apkContentsSigners.all { it in trusted }
    }

    private fun install(release: Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return
        }
        pendingConfirmation?.let { confirmation ->
            // The system installer already waits for the user; show its confirmation again.
            pendingConfirmation = null
            _state.value = UpdateState.Installing(release)
            if (runCatching { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        scheduledSessionId?.let { id -> runCatching { packageManager.packageInstaller.abandonSession(id) } }
        scheduledSessionId = null
        _state.value = UpdateState.Installing(release)
        scope.launch {
            val sessionId = runCatching { writeSession(release) }.getOrElse {
                Log.w(TAG, "Could not create install session", it)
                _state.value = UpdateState.Failed(R.string.update_error_install)
                return@launch
            }
            packageManager.packageInstaller.openSession(sessionId).use { it.commit(statusReceiver(sessionId)) }
        }
    }

    /** Installs as soon as the launcher is not in the foreground, without asking (install constraints). */
    private fun schedule(release: Release) {
        scope.launch {
            val sessionId = runCatching { writeSession(release) }.getOrElse {
                _state.value = UpdateState.Ready(release)
                return@launch
            }
            val constraints = PackageInstaller.InstallConstraints.Builder()
                .setAppNotForegroundRequired()
                .build()
            val scheduled = runCatching {
                packageManager.packageInstaller.commitSessionAfterInstallConstraintsAreMet(
                    sessionId,
                    statusReceiver(sessionId),
                    constraints,
                    TimeUnit.DAYS.toMillis(7),
                )
            }.onFailure { Log.w(TAG, "Could not schedule the update", it) }.isSuccess
            if (scheduled) {
                scheduledSessionId = sessionId
                _state.value = UpdateState.Scheduled(release)
            } else {
                runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
                _state.value = UpdateState.Ready(release)
            }
        }
    }

    private suspend fun writeSession(release: Release): Int = withContext(Dispatchers.IO) {
        val file = apkFile(release)
        check(file.exists() && isValidUpdate(file, release)) { "update file missing or invalid" }
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("launcher.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
            }
        } catch (error: Throwable) {
            installer.abandonSession(sessionId)
            throw error
        }
        sessionId
    }

    private fun statusReceiver(sessionId: Int) = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
        // The installer adds the status as extras, so the intent has to stay mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    ).intentSender

    /** Called by [InstallResultReceiver]. On success the process is replaced, so there is nothing to do. */
    internal fun onInstallStatus(status: Int, confirmation: Intent?) {
        val release = releaseOf(_state.value)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirmation == null || release == null) return
                scheduledSessionId = null
                if (isAppVisible) {
                    _state.value = UpdateState.Installing(release)
                    runCatching { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure {
                            pendingConfirmation = confirmation
                            _state.value = UpdateState.Ready(release)
                        }
                } else {
                    pendingConfirmation = confirmation
                    _state.value = UpdateState.Ready(release)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                scheduledSessionId = null
                if (release != null) _state.value = UpdateState.Ready(release)
            }
            else -> {
                scheduledSessionId = null
                _state.value = UpdateState.Failed(R.string.update_error_install)
            }
        }
    }

    fun setAutoUpdate(enabled: Boolean) {
        scope.launch { preferences.setAutoUpdate(enabled) }
    }

    /** Back from the "install unknown apps" settings: continue if the permission was granted. */
    fun onAppResumed() {
        val current = _state.value
        if (current is UpdateState.NeedsPermission && packageManager.canRequestPackageInstalls()) install(current.release)
    }

    private fun canInstallSilently(): Boolean {
        if (!packageManager.canRequestPackageInstalls()) return false
        val source = runCatching { packageManager.getInstallSourceInfo(context.packageName) }.getOrNull()
        return source?.installingPackageName == context.packageName
    }

    private fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    private fun apkFile(release: Release) = File(updateDir, "launcher-${release.versionCode}.apk")

    private fun cleanUpOldDownloads() {
        scope.launch(Dispatchers.IO) {
            updateDir.listFiles()?.forEach { file ->
                val code = file.name.removePrefix("launcher-").substringBefore('.').toLongOrNull()
                if (code == null || code <= currentVersionCode) file.delete()
            }
        }
    }

    private fun releaseOf(state: UpdateState): Release? = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        is UpdateState.NeedsPermission -> state.release
        is UpdateState.Scheduled -> state.release
        is UpdateState.Installing -> state.release
        else -> null
    }

    private companion object {
        const val TAG = "LauncherUpdate"
        const val TIMEOUT_MS = 15_000
        val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)
    }
}

/** GitHub lists no release: none was published yet, or the repository is private. */
private class ReleasesNotFound : IOException("No releases found")
