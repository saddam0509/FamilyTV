package local.familytv

import android.net.Uri
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Programme(
    val id: String,
    val title: String,
    val url: String,
    val live: Boolean,
    val logo: String? = null,
    val fallbackUrl: String? = null,
    val groups: Set<ChannelGroup> = emptySet(),
)

object BuddyTv {
    fun channels(): List<Programme> {
        if (!ServerConfig.enableLocalIspSources) return emptyList()
        val home = ServerConfig.buddyTvHost
        val catalogue = home.removeSuffix("/") + "/api/channels.json"
        val connection = (URL(catalogue).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = false
        }
        try {
            if (connection.responseCode != 200) throw IOException("Channel list unavailable")
            val entries = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val ordered = (0 until entries.length()).map(entries::getJSONObject)
                .sortedByDescending { it.optString("category").equals("Bangla", ignoreCase = true) }
            return ordered.mapNotNull { item ->
                val rawUrl = item.optString("stream_url")
                if (rawUrl.isBlank()) return@mapNotNull null
                val stream = runCatching { Uri.parse(URL(URL(catalogue), rawUrl).toString()) }
                    .getOrNull() ?: return@mapNotNull null
                val id = item.optString("id")
                val title = item.optString("name")
                val supportedScheme = stream.scheme == "https" || stream.scheme == "http"
                if (id.isBlank() || title.isBlank() || !supportedScheme || stream.host.isNullOrBlank()) {
                    null
                } else {
                    val logo = item.optString("logo").takeIf(String::isNotBlank)?.let {
                        runCatching { URL(URL(home), it).toString() }.getOrNull()
                    }
                    Programme("buddy:$id", title, stream.toString(), live = true, logo = logo,
                        groups = ChannelGroup.classify(title, setOf(item.optString("category").lowercase())))
                }
            }.distinctBy(Programme::id)
        } finally {
            connection.disconnect()
        }
    }
}
