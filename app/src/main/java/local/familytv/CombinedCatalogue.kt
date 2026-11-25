package local.familytv

import android.net.Uri
import org.jsoup.Jsoup

object CombinedCatalogue {
    fun movies(url: String, includeRecent: Boolean): MoviePage {
        if (url.startsWith("archive://")) return ArchiveOrg.movies(url)
        val primary = runCatching { Movies.page(url) }
        if (!includeRecent) return primary.getOrThrow()
        val recent = runCatching { recentMovies(url) }.getOrDefault(emptyList())
        if (recent.isEmpty()) return primary.getOrThrow()
        return primary.getOrNull()?.let { it.copy(movies = merge(it.movies, recent)) }
            ?: MoviePage(recent, null, null, emptyList())
    }

    fun search(query: String): SearchResults {
        val primary = runCatching { Movies.page(Movies.search(query)).movies }
        val secondary = runCatching { Mazeda.search(query) }
        val archive = runCatching { ArchiveOrg.searchFilms(query) }
        if (primary.isFailure && secondary.isFailure && archive.isFailure) primary.getOrThrow()
        val term = titleKey(query)
        val movies = merge(
            merge(primary.getOrDefault(emptyList()), secondary.getOrNull()?.movies.orEmpty()),
            archive.getOrDefault(emptyList()),
        )
            .sortedBy { movie ->
                val name = titleKey(movie.title).removePrefix("the ")
                when {
                    name == term -> 0
                    name.startsWith(term) -> 1
                    name.contains(term) -> 2
                    else -> 3
                }
            }
        return SearchResults(
            movies,
            secondary.getOrNull()?.shows.orEmpty(),
        )
    }

    fun recentMovies(url: String): List<Movie> {
        val page = Uri.parse(url).getQueryParameter("page")?.toIntOrNull()?.coerceIn(1, 10_000) ?: 1
        return Mazeda.recent().drop((page - 1) * 18).take(18)
    }

    fun merge(primary: List<Movie>, secondary: List<Movie>): List<Movie> {
        val merged = primary.toMutableList()
        val indexes = mutableMapOf<String, Int>()
        val ids = mutableMapOf<String, Int>()
        fun key(movie: Movie) = movie.year?.let {
            val title = titleKey(movie.title).removePrefix("the ")
            "$title|$it"
        }
        primary.forEachIndexed { index, movie ->
            key(movie)?.let { indexes.putIfAbsent(it, index) }
            ids.putIfAbsent(movie.id, index)
        }
        for (movie in secondary) {
            val key = key(movie)
            val match = ids[movie.id] ?: key?.let(indexes::get)
            if (match == null) {
                if (key != null) indexes[key] = merged.size
                ids[movie.id] = merged.size
                merged.add(movie)
            } else {
                val existing = merged[match]
                val preferred = if (existing.details.isBlank() && movie.details.isNotBlank()) movie else existing
                merged[match] = preferred.copy(files = (existing.files + movie.files).distinct())
            }
        }
        return merged
    }
}
