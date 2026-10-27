package io.github.davydgky.launcher.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {

    private val repo = "davydgky/WEWEWE-Launcher"

    private fun release(
        name: String = "launcher-42.apk",
        url: String = "https://github.com/$repo/releases/download/v0.2.42/$name",
        extra: String = "",
    ) = """
        {"tag_name": "v0.2.42", "draft": false, "prerelease": false, "body": " Fixes \n", $extra
         "assets": [
           {"name": "notes.txt", "browser_download_url": "https://github.com/$repo/releases/download/v0.2.42/notes.txt"},
           {"name": "$name", "size": 1234, "browser_download_url": "$url",
            "digest": "sha256:ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789"}
         ]}
    """.trimIndent()

    @Test
    fun `reads version, url, size and digest of the apk`() {
        val result = ReleaseParser.parse(release(), repo)!!
        assertEquals(42L, result.versionCode)
        assertEquals("0.2.42", result.versionName)
        assertEquals(1234L, result.sizeBytes)
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", result.sha256)
        assertEquals("Fixes", result.notes)
    }

    @Test
    fun `rejects downloads from other repositories or hosts`() {
        assertNull(ReleaseParser.parse(release(url = "https://github.com/evil/WEWEWE-Launcher/releases/download/x/launcher-42.apk"), repo))
        assertNull(ReleaseParser.parse(release(url = "http://github.com/$repo/releases/download/x/launcher-42.apk"), repo))
        assertNull(ReleaseParser.parse(release(url = "https://example.com/launcher-42.apk"), repo))
    }

    @Test
    fun `ignores other files, prereleases and broken json`() {
        assertNull(ReleaseParser.parse(release(name = "launcher-debug.apk"), repo))
        assertNull(ReleaseParser.parse(release(extra = "\"prerelease\": true,").replace("\"prerelease\": false,", ""), repo))
        assertNull(ReleaseParser.parse("not json", repo))
    }
}
