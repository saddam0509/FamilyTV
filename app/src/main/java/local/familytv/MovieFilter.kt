package local.familytv

private val showYearTag = Regex("""\[((?:19|20)\d{2})\]""")
private val showBracketTag = Regex("""\s*\[[^\]]*\]""")

/** Splits a series folder name like "Abar Proloy [2023]" into ("Abar Proloy", "2023"). */
fun showTitleYear(raw: String): Pair<String, String?> {
    val year = showYearTag.find(raw)?.groupValues?.get(1)
    return raw.replace(showBracketTag, "").trim() to year
}

/** Client-side sort options for the movie grid. */
enum class MovieSort(val label: String) {
    DEFAULT("Default"),
    YEAR_DESC("Newest"),
    YEAR_ASC("Oldest"),
    TITLE("A–Z"),
    TOP_RATED("Top rated"),
}

/**
 * Applies the current decade filter and sort to a loaded movie list.
 *
 * Pure function (no Android/network), so it is unit-tested on the JVM. The
 * filter/sort is client-side over whatever pages have loaded; titles without a
 * usable year are dropped when a decade is selected and sorted to the end
 * otherwise.
 */
fun arrangeMovies(
    movies: List<Movie>,
    sort: MovieSort,
    years: IntRange?,
    rating: (Movie) -> Double? = { null },
): List<Movie> {
    val filtered = if (years == null) movies
    else movies.filter { (it.year?.toIntOrNull() ?: -1) in years }
    return when (sort) {
        MovieSort.DEFAULT -> filtered
        MovieSort.YEAR_DESC -> filtered.sortedByDescending { it.year?.toIntOrNull() ?: Int.MIN_VALUE }
        MovieSort.YEAR_ASC -> filtered.sortedBy { it.year?.toIntOrNull() ?: Int.MAX_VALUE }
        MovieSort.TITLE -> filtered.sortedBy { titleKey(it.title) }
        MovieSort.TOP_RATED -> filtered.sortedByDescending { rating(it) ?: -1.0 }
    }
}
