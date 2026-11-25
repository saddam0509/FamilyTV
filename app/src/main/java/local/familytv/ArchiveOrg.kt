package local.familytv

import android.net.Uri
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

data class ArchiveFile(val name: String, val format: String)

/**
 * Free, legal, public-domain feature films from the Internet Archive.
 *
 * Reachable on any network over HTTPS, unlike the BDIX movie catalogue. The
 * grid listing is one search request (identifier/title/year/poster); the
 * playable file is resolved lazily from the item's metadata only when a title
 * is played, so browsing stays fast. Results are sorted by downloads so the
 * recognisable public-domain classics surface first.
 *
 * The file-selection logic (pickPlayable) is kept free of Android/network types
 * so it can be unit-tested on the JVM.
 */
object ArchiveOrg {
    const val firstPage = "archive://films/?page=1"
    private const val perPage = 24
    private const val fields = "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&sort%5B%5D=downloads+desc"
    private const val browse =
        "https://archive.org/advancedsearch.php?q=collection%3A(feature_films)+AND+mediatype%3A(movies)" +
            fields + "&rows=$perPage&output=json"

    fun movies(url: String): MoviePage {
        val page = Uri.parse(url).getQueryParameter("page")?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
        val docs = results("$browse&page=$page")
        val next = if (docs.size < perPage) null else "archive://films/?page=${page + 1}"
        val previous = if (page > 1) "archive://films/?page=${page - 1}" else null
        return MoviePage(docs, next, previous, emptyList())
    }

    fun searchFilms(query: String): List<Movie> {
        val q = Uri.encode("title:($query) AND mediatype:(movies)")
        return results("https://archive.org/advancedsearch.php?q=$q$fields&rows=24&output=json")
    }

    private fun results(url: String): List<Movie> {
        val body = Jsoup.connect(url).ignoreContentType(true)
            .timeout(7000).maxBodySize(2_000_000).execute().body()
        val docs = JSONObject(body).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).mapNotNull(docs::optJSONObject).mapNotNull { doc ->
            val id = doc.optString("identifier").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = doc.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            Movie("archive:$id", title, "", "https://archive.org/services/img/$id", yearOf(doc), null)
        }.distinctBy(Movie::id)
    }

    private fun yearOf(doc: JSONObject): String? {
        val raw = doc.opt("year")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Regex("""(?:19|20)\d{2}""").find(raw)?.value
    }

    fun playback(movie: Movie): List<Programme> {
        val id = movie.id.removePrefix("archive:")
        val body = Jsoup.connect("https://archive.org/metadata/${Uri.encode(id)}")
            .ignoreContentType(true).timeout(7000).maxBodySize(3_000_000).execute().body()
        val files = JSONObject(body).optJSONArray("files") ?: throw IOException("No files listed")
        val entries = (0 until files.length()).mapNotNull(files::optJSONObject)
            .map { ArchiveFile(it.optString("name"), it.optString("format")) }
        val name = pickPlayable(entries) ?: throw IOException("No playable video")
        val url = "https://archive.org/download/$id/" + Uri.encode(name)
        return listOf(Programme(movie.id, movie.title, url, live = false))
    }

    internal fun pickPlayable(files: List<ArchiveFile>): String? {
        val videos = files.filter {
            val extension = it.name.substringAfterLast('.', "").lowercase()
            extension in setOf("mp4", "m4v", "ogv", "webm") && !it.name.contains("thumb", ignoreCase = true)
        }
        // Prefer an MP4 (widest ExoPlayer support), then a smaller/stream-friendly
        // encode (Archive labels these "512Kb MPEG4"), then the shortest filename.
        return videos.sortedWith(
            compareBy(
                { if (it.name.endsWith(".mp4", true) || it.name.endsWith(".m4v", true)) 0 else 1 },
                { if (it.format.contains("512", ignoreCase = true)) 0 else 1 },
                { it.name.length },
            ),
        ).firstOrNull()?.name
    }
}
