package io.github.davydgky.launcher.update

import org.json.JSONObject

/** A published launcher build that can be installed as an update. */
data class Release(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the APK as reported by GitHub, if available. */
    val sha256: String?,
    val notes: String,
)

/** Reads GitHub's "latest release" API response. */
object ReleaseParser {

    private val ApkName = Regex("""^launcher-(\d+)\.apk$""")
    private val Sha256 = Regex("""^sha256:([0-9a-fA-F]{64})$""")

    /**
     * Returns the APK of the release, or null if there is none. Only download URLs of [repository]'s
     * own releases are accepted, so a tampered response cannot point the updater somewhere else.
     */
    fun parse(json: String, repository: String): Release? = runCatching {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val assets = root.optJSONArray("assets") ?: return null
        val prefix = "https://github.com/$repository/releases/download/"
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val match = ApkName.matchEntire(asset.optString("name")) ?: continue
            val url = asset.optString("browser_download_url")
            if (!url.startsWith(prefix, ignoreCase = true)) continue
            return Release(
                versionCode = match.groupValues[1].toLong(),
                versionName = root.optString("tag_name").removePrefix("v").ifBlank { match.groupValues[1] },
                apkUrl = url,
                sizeBytes = asset.optLong("size"),
                sha256 = Sha256.matchEntire(asset.optString("digest"))?.groupValues?.get(1)?.lowercase(),
                notes = root.optString("body").trim(),
            )
        }
        null
    }.getOrNull()
}
