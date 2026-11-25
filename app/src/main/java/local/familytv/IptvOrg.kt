package local.familytv

import org.jsoup.Jsoup

/**
 * Public, internet-wide live channels from the open iptv-org project.
 *
 * Unlike the ISP/BDIX sources (BuddyTv, Zflix), these streams are reachable on
 * any network. The project only indexes publicly posted links, so an individual
 * stream may be offline or unofficial; the player's existing fallback and
 * next-channel handling absorbs the misses.
 *
 * Only HTTPS streams are kept, so the app can keep its cleartext-blocking
 * network-security policy unchanged. The parser is deliberately free of Android
 * and network types so it can be unit-tested on the JVM.
 */
object IptvOrg {
    // Bengali-language playlist: Bangladeshi + Indian Bangla channels, HTTPS HLS.
    private const val playlist = "https://iptv-org.github.io/iptv/languages/ben.m3u"
    private val attribute = Regex("""([a-zA-Z0-9-]+)="([^"]*)"""")
    private val resolution = Regex("""\s*\(\d{3,4}p\)""")
    private val bracketTag = Regex("""\s*\[[^\]]*\]""")

    fun channels(): List<Programme> =
        parse(
            Jsoup.connect(playlist).ignoreContentType(true)
                .timeout(6000).maxBodySize(2_000_000).execute().body(),
        )

    internal fun parse(body: String): List<Programme> {
        val channels = mutableListOf<Programme>()
        var name: String? = null
        var logo: String? = null
        var group = ""
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val attrs = attribute.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.takeIf(String::isNotBlank)
                    group = attrs["group-title"].orEmpty()
                    name = line.substringAfterLast(',').trim().ifBlank { null }
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val label = name
                    // HTTPS only: keeps the cleartext-blocking network policy intact.
                    if (label != null && line.startsWith("https://") && line.length > 8) {
                        val display = clean(label)
                        if (display.isNotBlank()) {
                            channels.add(
                                Programme(
                                    id = "iptv:" + titleKey(display).replace(" ", ""),
                                    title = display,
                                    url = line,
                                    live = true,
                                    logo = logo,
                                    groups = groupsFor(display, group),
                                ),
                            )
                        }
                    }
                    name = null
                    logo = null
                    group = ""
                }
            }
        }
        return channels.distinctBy(Programme::id)
    }

    internal fun clean(name: String): String =
        name.replace(resolution, "").replace(bracketTag, "").trim()

    private fun groupsFor(name: String, groupTitle: String): Set<ChannelGroup> {
        val tokens = groupTitle.lowercase().split(';', ',', '&').map(String::trim).toSet()
        val mapped = buildSet {
            if ("news" in tokens) add(ChannelGroup.NEWS)
            if ("sports" in tokens) add(ChannelGroup.SPORTS)
            if ("movies" in tokens) add(ChannelGroup.MOVIES)
            if ("kids" in tokens || "animation" in tokens) add(ChannelGroup.KIDS)
            if ("documentary" in tokens) add(ChannelGroup.DOCUMENTARY)
        }
        // classify() adds the Bangladeshi-channel whitelist match and the
        // Entertainment fallback; the group-title mapping fills in the
        // categories classify() does not infer from the name alone.
        return ChannelGroup.classify(name, tokens) + mapped
    }
}
