package local.familytv

import android.net.Uri
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

object Zflix {
    private val base: String get() = ServerConfig.zflixHost.trimEnd('/')
    private val baseHost: String get() = Uri.parse(ServerConfig.zflixHost).host ?: "zflixbd.com"
    private val channelNames = mapOf(
        "tsports-1" to "T Sports",
        "ptv" to "PTV Sports",
    )
    private val configuration = Regex("""OvooVideojs\.mount\((\{[^<]+?\})\)""")

    fun channels(): List<Programme> {
        if (!ServerConfig.enableLocalIspSources) return emptyList()
        val catalogue = Jsoup.connect("$base/live-tv.html").timeout(4500).get()
        val sections = catalogue.select("a[href*='/live-tv/category/']").mapNotNull {
            val uri = Uri.parse(it.absUrl("href"))
            val slug = uri.lastPathSegment?.removeSuffix(".html") ?: return@mapNotNull null
            if ((uri.host != baseHost && uri.host != "zflixbd.com") || !slug.matches(Regex("[a-z-]+"))) null else slug to uri.toString()
        }.distinct()
        val pool = Executors.newFixedThreadPool(4)
        return try {
            val byUrl = linkedMapOf<String, Programme>()
            readPage(catalogue, "").forEach { byUrl[it.id] = it }
            val results = pool.invokeAll(sections.map { (category, url) ->
                Callable {
                    runCatching { readPage(Jsoup.connect(url).timeout(4000).get(), category) }
                        .getOrDefault(emptyList())
                }
            })
            for (channel in results.flatMap { it.get() }) {
                val existing = byUrl[channel.id]
                byUrl[channel.id] = existing?.copy(groups = existing.groups + channel.groups) ?: channel
            }
            byUrl.values.toList()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun readPage(doc: org.jsoup.nodes.Document, category: String): List<Programme> =
        doc.select(".product:has(.product-title a)").mapNotNull { card ->
            val link = card.selectFirst(".product-title a[href]") ?: return@mapNotNull null
            val address = Uri.parse(link.absUrl("href"))
            val slug = address.lastPathSegment?.removeSuffix(".html") ?: return@mapNotNull null
            val title = channelNames[slug] ?: link.text().trim()
            if ((address.host != baseHost && address.host != "zflixbd.com") || address.path != "/live-tv/$slug.html" || title.isBlank()) return@mapNotNull null
            Programme("zflix:$slug", title, address.toString(), true,
                logo = card.selectFirst("img[src]")?.absUrl("src"),
                groups = ChannelGroup.classify(title, setOf(category)))
        }.distinctBy(Programme::id)

    fun playback(channel: Programme): Programme {
        val doc = Jsoup.connect(channel.url).timeout(6000).followRedirects(false).get()
        val data = doc.select("script").firstNotNullOfOrNull {
            configuration.find(it.data())?.groupValues?.get(1)
        } ?: throw IOException("Player source unavailable")
        val source = Uri.parse(JSONObject(data).optString("source"))
        if ((source.host != baseHost && source.host != "zflixbd.com") || source.scheme !in setOf("http", "https") ||
            source.lastPathSegment?.endsWith(".m3u8") != true
        ) throw IOException("Unsupported channel source")
        return channel.copy(url = source.toString())
    }
}
