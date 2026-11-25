package local.familytv

import android.net.Uri
import org.jsoup.Jsoup
import java.io.IOException

data class Movie(
    val id: String,
    val title: String,
    val details: String,
    val poster: String?,
    val year: String?,
    val summary: String?,
    val files: List<String> = emptyList(),
)
data class MoviePage(
    val movies: List<Movie>,
    val next: String?,
    val previous: String?,
    val categories: List<MovieCategory>,
)
data class MovieCategory(val name: String, val url: String)

object Movies {
    private val host: String get() = ServerConfig.moviesHost
    val firstPage: String get() = "http://${ServerConfig.moviesHost}/allmovies.php?page=1&entries=24&w=grid"
    private val movieId = Regex("""tt\d+""")

    fun search(query: String): String = Uri.parse("http://$host/msearch.php").buildUpon()
        .appendQueryParameter("q", "M")
        .appendQueryParameter("searchquery", query)
        .build().toString()

    fun page(url: String): MoviePage {
        val pageUri = Uri.parse(url)
        check(pageUri.host == host)
        val document = Jsoup.connect(url).timeout(8000).followRedirects(false).get()
        val movies = document.select("a.thumb.boxHov1[href]").mapNotNull { cover ->
            val details = cover.absUrl("href")
            val address = Uri.parse(details)
            if (address.host != host || address.lastPathSegment != "movie.php") return@mapNotNull null
            val id = address.getQueryParameter("imdbid") ?: return@mapNotNull null
            if (!movieId.matches(id)) return@mapNotNull null
            val metadata = cover.parent()?.selectFirst(".MovieTitle")
            val extra = document.getElementById("div$id")
                ?.children()?.filter { it.hasClass("col-md-12") }.orEmpty()
            val title = (extra.firstOrNull()?.text()?.takeIf(String::isNotBlank)
                ?: metadata?.selectFirst("a")?.text()?.takeIf(String::isNotBlank))
                ?: return@mapNotNull null
            val poster = cover.selectFirst("img[src]")?.absUrl("src")?.takeIf(String::isNotBlank)
            val year = metadata?.selectFirst("span")?.text()?.takeIf { it.matches(Regex("\\d{4}")) }
            val summary = extra.getOrNull(1)?.ownText()?.takeIf(String::isNotBlank)
            Movie(id, title, details, poster, year, summary)
        }.distinctBy(Movie::id)
        if (movies.isEmpty() && pageUri.lastPathSegment != "msearch.php") {
            throw IOException("No movie cards returned")
        }

        val categories = document.select("option[value*='Category=']").mapNotNull { option ->
            val address = option.absUrl("value")
            val title = option.text()
            val uri = Uri.parse(address)
            if (title.isBlank() || uri.host != host || title != uri.getQueryParameter("Category")) {
                return@mapNotNull null
            }
            MovieCategory(title, address)
        }.distinctBy(MovieCategory::url)
        val next = document.selectFirst(".pagination-next a")?.absUrl("href")
            ?.takeIf { Uri.parse(it).host == host }
        val previous = document.selectFirst(".pagination a:has(.glyphicon-chevron-left)")
            ?.absUrl("href")?.takeIf { Uri.parse(it).host == host }
        return MoviePage(movies, next, previous, categories)
    }

    fun playback(movie: Movie): List<Programme> {
        if (movie.id.startsWith("archive:")) return ArchiveOrg.playback(movie)
        val sources = mutableListOf<String>()
        if (movie.details.isNotBlank()) {
            runCatching { primaryUrl(movie) }.getOrNull()?.let(sources::add)
        }
        sources.addAll(movie.files.filter(Mazeda::isVideoUrl))
        if (sources.isEmpty()) throw IOException("No supported video link")
        return sources.distinct().map { Programme("movie:${movie.id}", movie.title, it, live = false) }
    }

    private fun primaryUrl(movie: Movie): String {
        check(Uri.parse(movie.details).host == host)
        val document = Jsoup.connect(movie.details).timeout(8000).followRedirects(false).get()
        val url = (document.select("video source[src]").map { it.absUrl("src") } +
            document.select("a[title=Download Movie]").map { it.absUrl("href") })
            .firstOrNull {
                val uri = Uri.parse(it)
                val supportedScheme = uri.scheme == "http" || uri.scheme == "https"
                val supportedHost = uri.host == "172.21.22.23" || uri.host == host
                val extension = uri.lastPathSegment?.substringAfterLast('.')?.lowercase()
                supportedScheme && supportedHost && extension in setOf("mkv", "mp4", "webm", "m3u8")
            } ?: throw IOException("No supported video link")
        return url.replace(" ", "%20")
    }
}
