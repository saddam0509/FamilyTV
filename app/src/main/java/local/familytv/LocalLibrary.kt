package local.familytv

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ResumeEntry(
    val id: String,
    val title: String,
    val position: Long,
    val duration: Long,
    val movie: Movie? = null,
    val episodeUrl: String? = null,
)

class LocalLibrary(private val state: SharedPreferences) {
    fun recent(): List<ResumeEntry> = runCatching {
        items(JSONArray(state.getString("continue", "[]"))).map {
            ResumeEntry(it.getString("id"), it.getString("title"), it.getLong("position"),
                it.getLong("duration"), it.optJSONObject("movie")?.let(::readMovie),
                it.nullable("episode"))
        }
    }.getOrDefault(emptyList())

    fun remember(entry: ResumeEntry) {
        val previous = recent().filter { it.id != entry.id }
        val entries = if (entry.position > 0) listOf(entry) + previous else previous
        val json = JSONArray()
        for (item in entries.take(4)) {
            json.put(JSONObject().put("id", item.id).put("title", item.title)
                .put("position", item.position).put("duration", item.duration)
                .put("movie", item.movie?.let(::movieJson)).put("episode", item.episodeUrl))
        }
        state.edit().putString("continue", json.toString()).apply()
    }

    fun moviePage(): MoviePage? = runCatching {
        val data = JSONObject(state.getString("recent-movies", null) ?: return null)
        MoviePage(items(data.getJSONArray("movies")).map(::readMovie), data.nullable("next"), null,
            items(data.getJSONArray("categories")).map { MovieCategory(it.getString("name"), it.getString("url")) })
    }.getOrNull()

    fun saveMoviePage(page: MoviePage) {
        if (page.movies.isEmpty()) return
        val data = JSONObject().put("movies", JSONArray(page.movies.take(50).map(::movieJson)))
            .put("next", page.next)
            .put("categories", JSONArray(page.categories.map {
                JSONObject().put("name", it.name).put("url", it.url)
            }))
        state.edit().putString("recent-movies", data.toString()).apply()
    }

    fun directory(key: String): List<LibraryEntry>? = runCatching {
        items(JSONArray(state.getString("directory:$key", null) ?: return null)).map {
            LibraryEntry(it.getString("title"), it.getString("url"), it.getBoolean("folder"), it.nullable("year"))
        }
    }.getOrNull()

    @Synchronized
    fun saveDirectory(key: String, entries: List<LibraryEntry>) {
        if (entries.isEmpty() || entries.size > if (key == "show-index") 4000 else 1200) return
        val oldKeys = JSONArray(state.getString("directory-keys", "[]"))
        val keys = (listOf(key) + (0 until oldKeys.length()).map(oldKeys::getString)).distinct()
        val data = JSONArray(entries.map {
            JSONObject().put("title", it.title).put("url", it.url).put("folder", it.folder).put("year", it.year)
        })
        val update = state.edit().putString("directory:$key", data.toString())
            .putString("directory-keys", JSONArray(keys.take(6)).toString())
        keys.drop(6).forEach { update.remove("directory:$it") }
        update.apply()
    }

    fun shows(): List<LibraryEntry>? = directory("show-index")

    fun saveShows(entries: List<LibraryEntry>) = saveDirectory("show-index", entries)

    private fun movieJson(movie: Movie) = JSONObject()
        .put("id", movie.id).put("title", movie.title).put("details", movie.details)
        .put("poster", movie.poster).put("year", movie.year).put("summary", movie.summary)
        .put("files", JSONArray(movie.files))

    private fun readMovie(data: JSONObject): Movie {
        val files = data.optJSONArray("files") ?: JSONArray()
        return Movie(data.getString("id"), data.getString("title"), data.getString("details"),
            data.nullable("poster"), data.nullable("year"), data.nullable("summary"),
            (0 until files.length()).map(files::getString))
    }

    private fun JSONObject.nullable(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun items(array: JSONArray) = (0 until array.length()).mapNotNull(array::optJSONObject)
}
