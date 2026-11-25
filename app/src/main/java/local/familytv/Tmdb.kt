package local.familytv

import android.net.Uri
import org.json.JSONObject
import org.jsoup.Jsoup

/** Genres, a rating (movies), and a poster URL (TV shows) from TMDB. */
data class MovieMeta(val genres: List<String>, val rating: Double?, val poster: String? = null) {
    val known: Boolean get() = genres.isNotEmpty() || rating != null
}

/** Serialize for the on-disk cache as "rating|genre,genre|poster". Pure (JVM-testable). */
fun encodeMeta(meta: MovieMeta): String =
    "${meta.rating ?: ""}|${meta.genres.joinToString(",")}|${meta.poster ?: ""}"

fun decodeMeta(value: String): MovieMeta {
    val parts = value.split("|", limit = 3)
    val rating = parts.getOrNull(0)?.toDoubleOrNull()
    val genres = parts.getOrNull(1)?.split(",")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    val poster = parts.getOrNull(2)?.takeIf(String::isNotEmpty)
    return MovieMeta(genres, rating, poster)
}

/**
 * True when a TMDB show name confidently matches the wanted (titleKey'd) title:
 * an exact normalised match, or — for titles of 4+ chars — a whole-word prefix
 * either way (so "The Office" accepts "The Office (US)" but "3" rejects "3 Women").
 */
internal fun tvNameMatches(wantKey: String, name: String): Boolean {
    val got = titleKey(name)
    return got == wantKey ||
        (wantKey.length >= 4 && (got.startsWith("$wantKey ") || wantKey.startsWith("$got ")))
}

/**
 * Enriches movies with genre and rating from The Movie Database.
 *
 * BDIX movies carry an IMDb id (ttNNNN) and resolve exactly via /find; others
 * (Mazeda, Archive) fall back to a title+year search. All HTTPS, so no cleartext
 * change. Lookups are best-effort — a miss simply leaves a movie unenriched.
 */
object Tmdb {
    private val key: String get() = ServerConfig.tmdbApiKey.takeIf { it.isNotBlank() } ?: "eb511502fe080d106ac09a9c1ae301d6"
    private val imdbId = Regex("""tt\d+""")
    private val genreNames = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
        80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
        14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
        53 to "Thriller", 10752 to "War", 37 to "Western", 10770 to "TV Movie",
    )
    // TV genre ids differ from movie ids; map to the same display names where they overlap.
    private val tvGenreNames = mapOf(
        10759 to "Action", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
        99 to "Documentary", 18 to "Drama", 10751 to "Family", 10762 to "Kids",
        9648 to "Mystery", 10765 to "Science Fiction", 37 to "Western",
        10768 to "War", 10763 to "News", 10764 to "Reality", 10766 to "Soap", 10767 to "Talk",
    )

    fun lookup(movie: Movie): MovieMeta {
        val result = if (imdbId.matches(movie.id)) findByImdb(movie.id)
        else searchByTitle(movie.title, movie.year)
        result ?: return MovieMeta(emptyList(), null)
        val genres = result.optJSONArray("genre_ids")?.let { ids ->
            (0 until ids.length()).mapNotNull { genreNames[ids.optInt(it)] }
        }.orEmpty()
        val rating = result.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
        return MovieMeta(genres, rating)
    }

    private fun findByImdb(imdb: String): JSONObject? {
        val body = get("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id")
        val results = JSONObject(body).optJSONArray("movie_results") ?: return null
        return results.optJSONObject(0)
    }

    private fun searchByTitle(title: String, year: String?): JSONObject? {
        val query = Uri.encode(title)
        val yearParam = year?.takeIf { it.isNotBlank() }?.let { "&year=$it" }.orEmpty()
        val body = get("https://api.themoviedb.org/3/search/movie?api_key=$key&query=$query$yearParam")
        val results = JSONObject(body).optJSONArray("results") ?: return null
        return results.optJSONObject(0)
    }

    private data class TvHit(val name: String, val meta: MovieMeta)

    /**
     * Poster + genres + rating for a TV show from one TMDB search, or empty
     * metadata if there is no confident match.
     *
     * Prefers an exact (normalised) title match over TMDB's fuzzy ranking so a
     * different show isn't used, and retries without the year because folder
     * years are often wrong.
     */
    fun tvMeta(title: String, year: String?): MovieMeta {
        val clean = title.replace('.', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        val want = titleKey(clean)
        if (want.isBlank()) return MovieMeta(emptyList(), null)
        val tries = if (year.isNullOrBlank()) listOf<String?>(null) else listOf(year, null)
        var fallback: MovieMeta? = null
        for (attemptYear in tries) {
            val hits = searchTv(clean, attemptYear)
            hits.firstOrNull { titleKey(it.name) == want }?.let { return it.meta }
            if (fallback == null && want.length >= 4) {
                fallback = hits.firstOrNull { tvNameMatches(want, it.name) }?.meta
            }
        }
        return fallback ?: MovieMeta(emptyList(), null)
    }

    private fun searchTv(query: String, year: String?): List<TvHit> {
        val yearParam = year?.takeIf { it.isNotBlank() }?.let { "&first_air_date_year=$it" }.orEmpty()
        val body = get("https://api.themoviedb.org/3/search/tv?api_key=$key&query=${Uri.encode(query)}$yearParam")
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), 8)).mapNotNull(results::optJSONObject).map { item ->
            val path = item.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
            val genres = item.optJSONArray("genre_ids")?.let { ids ->
                (0 until ids.length()).mapNotNull { tvGenreNames[ids.optInt(it)] }
            }.orEmpty().distinct()
            val rating = item.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
            TvHit(item.optString("name"), MovieMeta(genres, rating, path?.let { "https://image.tmdb.org/t/p/w342$it" }))
        }
    }

    private fun get(url: String): String =
        Jsoup.connect(url).ignoreContentType(true).timeout(6000).maxBodySize(1_000_000).execute().body()
}
