package io.github.davydgky.launcher.core

/**
 * Removes tracking parameters from links ("utm_source", "fbclid", "si" on YouTube and Spotify …),
 * so a shared link says only where it leads. Everything else of the link stays as it was.
 */
object UrlCleaner {

    /** Parameters that only ever track, on every site. */
    private val trackers = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid",
        "igshid", "igsh", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id",
        "vero_id", "rb_clickid", "s_cid", "ncid", "ref_src", "ref_url", "spm", "scm", "trk", "trkCampaign",
        "share_id", "sharing_id", "_ga", "_gl", "epik", "wt_mc", "wt_zmc", "at_medium", "at_campaign",
        "xtor", "srsltid",
    )

    private val trackerPrefixes = listOf("utm_", "pk_", "mtm_", "hsa_", "itm_")

    /** Parameters that track only on these sites (elsewhere they may mean something). */
    private val siteTrackers = mapOf(
        "youtube.com" to setOf("si", "pp", "feature"),
        "youtu.be" to setOf("si", "feature"),
        "spotify.com" to setOf("si", "context", "nd"),
        "instagram.com" to setOf("igsh", "img_index"),
        "twitter.com" to setOf("s", "t"),
        "x.com" to setOf("s", "t"),
        "amazon.de" to setOf("ref", "ref_", "pd_rd_r", "pd_rd_w", "pd_rd_wg", "pf_rd_p", "pf_rd_r", "content-id", "psc", "smid", "th", "linkCode", "tag", "linkId", "crid", "sprefix", "qid", "sr"),
        "amazon.com" to setOf("ref", "ref_", "pd_rd_r", "pd_rd_w", "pd_rd_wg", "pf_rd_p", "pf_rd_r", "content-id", "psc", "smid", "th", "linkCode", "tag", "linkId", "crid", "sprefix", "qid", "sr"),
        "tiktok.com" to setOf("is_from_webapp", "sender_device", "sender_web_id", "_r", "_t"),
        "reddit.com" to setOf("share_id", "utm_name", "rdt"),
        "linkedin.com" to setOf("trk", "lipi", "trackingId", "refId"),
        "google.com" to setOf("ved", "ei", "sa", "usg", "sca_esv", "sca_upv", "gs_lcrp", "sourceid", "oq", "aqs", "uact"),
    )

    private val linkPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** The first link in [text], cleaned; null if there is none. */
    fun firstLink(text: String): String? = linkPattern.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?')?.let(::clean)

    /** [url] without its tracking parameters; [url] itself if it has none or is not a web link. */
    fun clean(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val fragmentStart = url.indexOf('#').let { if (it < 0) url.length else it }
        val queryStart = url.indexOf('?').takeIf { it in 0 until fragmentStart } ?: return url
        val host = hostOf(url, schemeEnd)
        val forSite = siteTrackers.entries.firstOrNull { (site, _) -> host == site || host.endsWith(".$site") }?.value.orEmpty()
        val params = url.substring(queryStart + 1, fragmentStart).split('&').filter { it.isNotEmpty() }
        val kept = params.filterNot { param ->
            val name = param.substringBefore('=')
            name in trackers || name in forSite || trackerPrefixes.any { name.startsWith(it, ignoreCase = true) }
        }
        if (kept.size == params.size) return url
        val base = url.substring(0, queryStart)
        val fragment = url.substring(fragmentStart)
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    private fun hostOf(url: String, schemeEnd: Int): String {
        val rest = url.substring(schemeEnd + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        return rest.substring(0, end).substringAfter('@').substringBefore(':').lowercase().removePrefix("www.").removePrefix("m.")
    }
}
