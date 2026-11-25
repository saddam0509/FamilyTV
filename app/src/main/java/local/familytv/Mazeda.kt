package local.familytv

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class LibraryEntry(
    val title: String,
    val url: String,
    val folder: Boolean,
    val year: String? = null,
)

object Mazeda {
    private val api: String get() = ServerConfig.mazedaApiHost.trimEnd('/') + "/"
    private val videoExtensions = setOf("mkv", "mp4", "webm", "avi", "m4v", "ts", "mov")
    private val yearPattern = Regex("""(?<!\d)((?:19|20)\d{2})(?!\d)""")
    private val episodePattern = Regex("""(?i)S(\d{1,3})[ ._-]*E(\d{1,4})""")
    private val seasonPattern = Regex("""(?i)^(?:season[ ._-]*|s)(\d{1,3})\b""")

    @Volatile private var recentCache: Pair<Long, List<Movie>>? = null
    @Volatile private var showCache: Pair<Long, List<LibraryEntry>>? = null

    fun recent(): List<Movie> {
        if (!ServerConfig.enableLocalIspSources) return emptyList()
        val now = android.os.SystemClock.elapsedRealtime()
        recentCache?.takeIf { now - it.first < 10 * 60 * 1000 }?.let { return it.second }
        val entries = JSONObject(json("recom.php")).getJSONArray("recom")
        val movies = objects(entries).mapNotNull { entry ->
            if (!entry.optString("recom_url_dir").endsWith("Movies", ignoreCase = true)) return@mapNotNull null
            asMovie(entry.optString("recom_name"), entry.optString("recom_url_dl"))
        }.distinctBy(Movie::id).toList()
        recentCache = now to movies
        return movies
    }

    fun search(query: String): SearchResults {
        if (!ServerConfig.enableLocalIspSources) return SearchResults(emptyList(), emptyList())
        val route = "search.php?q=" + Uri.encode(query)
        val results = JSONArray(json(route))
        val movies = mutableListOf<Movie>()
        val programmes = linkedMapOf<String, LibraryEntry>()
        val words = titleKey(query).split(' ').filter(String::isNotBlank)
        for (entry in objects(results)) {
            val url = entry.optString("search_url")
            val uri = Uri.parse(url)
            if (!valid(uri) || !isVideo(uri)) continue
            val directory = uri.getQueryParameter("dir").orEmpty()
            val fileName = uri.getQueryParameter("file") ?: entry.optString("search_name")
            val folder = directory.trimEnd('/').substringAfterLast('/')
            if (isSeries(directory, fileName)) {
                val parts = directory.trimEnd('/').split('/')
                val root = parts.indexOfFirst { it.startsWith("tv_shows", ignoreCase = true) }
                val show = parts.getOrNull(root + 1).takeIf { root >= 0 } ?: folder
                if (show.isBlank() || words.any { it !in titleKey(show) }) continue
                val showPath = if (root >= 0) parts.take(root + 2).joinToString("/") + "/" else directory
                programmes.putIfAbsent(titleKey(show), LibraryEntry(folderTitle(show), folderUrl(uri, showPath), true))
            } else if (isMovieDirectory(directory)) {
                val candidate = folder.takeIf { yearPattern.containsMatchIn(it) } ?: fileName
                val movie = asMovie(candidate, url) ?: continue
                if (words.all { it in titleKey(movie.title) }) movies.add(movie)
            }
        }
        return SearchResults(movies.distinctBy(Movie::id), programmes.values.toList())
    }

    fun categories(type: String): List<LibraryEntry> {
        if (!ServerConfig.enableLocalIspSources) return emptyList()
        val categories = JSONObject(json("categories.json")).getJSONArray("categories")
        return objects(categories).filter { it.optString("category") == type }.flatMap {
            objects(it.getJSONArray("links")).mapNotNull { link ->
                val address = link.optString("url")
                val title = link.optString("label")
                if (valid(Uri.parse(address)) && title.isNotBlank()) LibraryEntry(title, address, true) else null
            }
        }.distinctBy(LibraryEntry::url).toList()
    }

    @Synchronized
    fun showIndex(): List<LibraryEntry> {
        val now = android.os.SystemClock.elapsedRealtime()
        showCache?.takeIf { now - it.first < 10 * 60 * 1000 }?.let { return it.second }
        val sections = categories("tv_series")
        val worker = Executors.newFixedThreadPool(4)
        val results = try {
            worker.invokeAll(
                sections.map { category ->
                    Callable {
                        // One retry per folder: a big folder can time out under parallel load.
                        runCatching { folder(category.url) }.getOrNull()
                            ?: runCatching { folder(category.url) }.getOrDefault(emptyList())
                    }
                },
            ).map { it.get() }
        } finally {
            worker.shutdownNow()
        }
        val merged = results.flatten().filter(LibraryEntry::folder).distinctBy(LibraryEntry::url)
        // Only cache a complete index (every section returned something), so a
        // transient failure of one folder isn't remembered for 10 minutes.
        if (merged.isNotEmpty() && sections.isNotEmpty() && results.none(List<LibraryEntry>::isEmpty)) {
            showCache = now to merged
        }
        return merged
    }

    fun matchShows(query: String, index: List<LibraryEntry>): List<LibraryEntry> {
        val words = titleKey(query).split(' ').filter(String::isNotBlank)
        return index.filter { entry -> words.all { it in titleKey(entry.title) } }
    }

    fun featuredShows(): List<LibraryEntry> {
        val collections = categories("tv_series")
        val featured = collections.filterIndexed { index, category ->
            index == 0 || category.title.contains("Bangla", true) || category.title.contains("Documentary", true)
        }
        return featured.asSequence().take(3).mapNotNull { collection ->
            runCatching { folder(collection.url).firstOrNull() }.getOrNull() ?: collection
        }.toList()
    }

    fun folder(url: String): List<LibraryEntry> {
        val current = Uri.parse(url)
        check(valid(current))
        val currentPath = cleanPath(current.getQueryParameter("dir").orEmpty())
        val doc = Jsoup.connect(url).timeout(10000).maxBodySize(8_000_000).followRedirects(false).get()
        return doc.select("a[href*='dir=']").mapNotNull { link ->
            val address = link.absUrl("href")
            val uri = Uri.parse(address)
            if (!valid(uri) || uri.host != current.host || uri.encodedPath != current.encodedPath) return@mapNotNull null
            val directory = cleanPath(uri.getQueryParameter("dir").orEmpty())
            val filename = uri.getQueryParameter("file")
            if (filename != null) {
                if (!isVideo(uri) || directory != currentPath) return@mapNotNull null
                val episode = episodePattern.find(filename)
                val title = if (episode == null) movieTitle(filename).first else
                    "Season ${episode.groupValues[1].toInt()} · Episode ${episode.groupValues[2].toInt()}"
                return@mapNotNull LibraryEntry(title, address, false, movieTitle(filename).second)
            }
            if (uri.getQueryParameter("sort") != null || directory == currentPath || directory.isBlank()) {
                return@mapNotNull null
            }
            val parent = directory.substringBeforeLast('/', "")
            if (parent != currentPath) return@mapNotNull null
            val name = directory.substringAfterLast('/')
            val season = seasonPattern.find(name)?.groupValues?.get(1)?.toIntOrNull()
            LibraryEntry(season?.let { "Season $it" } ?: folderTitle(name), address, true)
        }.distinctBy(LibraryEntry::url)
    }

    private fun cleanPath(value: String) = value.removePrefix("./").trim('/')

    fun playback(entry: LibraryEntry): Programme {
        val uri = Uri.parse(entry.url)
        if (!valid(uri) || !isVideo(uri)) throw IOException("Unsupported file")
        return Programme("mazeda:${entry.url}", entry.title, entry.url, false)
    }

    fun isVideoUrl(url: String): Boolean = Uri.parse(url).let { valid(it) && isVideo(it) }

    private fun asMovie(value: String, url: String): Movie? {
        if (!isVideoUrl(url)) return null
        val (title, year) = movieTitle(value)
        if (title.isBlank()) return null
        return Movie("mazeda:${titleKey(title)}:$year", title, "", null, year, null, listOf(url))
    }

    internal fun movieTitle(value: String): Pair<String, String?> {
        val filename = value.replace(Regex("""\.(mkv|mp4|webm|avi|m4v|ts|mov)$""", RegexOption.IGNORE_CASE), "")
        val year = yearPattern.findAll(filename).lastOrNull()
        val base = if (year != null && year.range.first > 0) filename.substring(0, year.range.first) else filename
        val title = base.replace('.', ' ').replace('_', ' ').trim(' ', '-', '(', '[', '+')
        return title to year?.value
    }

    private fun folderTitle(value: String) =
        value.replace(Regex("""\s*\[(?:\d{3,4}p|Complete)\]$""", RegexOption.IGNORE_CASE), "").trim()

    private fun folderUrl(uri: Uri, directory: String) = uri.buildUpon().clearQuery()
        .appendQueryParameter("dir", directory).build().toString()

    private fun valid(uri: Uri): Boolean {
        val host = uri.host ?: return false
        val configuredHost = Uri.parse(ServerConfig.mazedaApiHost).host
        return uri.scheme in setOf("http", "https") &&
            (host == configuredHost || host.endsWith(".mazedanetworks.net") || host in setOf("172.22.22.103", "172.22.22.106", "172.22.22.101"))
    }

    private fun isVideo(uri: Uri): Boolean {
        val file = uri.getQueryParameter("file") ?: uri.lastPathSegment.orEmpty()
        return file.substringAfterLast('.', "").lowercase(Locale.ROOT) in videoExtensions &&
            !file.contains(Regex("""(?i)\b(sample|trailer)\b"""))
    }

    private fun isMovieDirectory(path: String) =
        path.contains(Regex("""(?i)movie|bluray|bollywood|hollywood"""))

    private fun isSeries(path: String, file: String) =
        path.contains("tv_shows", ignoreCase = true) || episodePattern.containsMatchIn(file)

    private fun json(route: String): String = Jsoup.connect(api + route)
        .ignoreContentType(true).timeout(6500).maxBodySize(3_000_000).followRedirects(false).execute().body()

    private fun objects(array: JSONArray) =
        (0 until array.length()).asSequence().mapNotNull(array::optJSONObject)
}

data class SearchResults(val movies: List<Movie>, val shows: List<LibraryEntry>)

fun titleKey(title: String): String = title.lowercase(Locale.ROOT)
    .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
