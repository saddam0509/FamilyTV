package local.familytv

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieFilterTest {
    private fun m(title: String, year: String?) = Movie("id:$title", title, "", null, year, null)

    private val sample = listOf(
        m("Zebra", "2021"),
        m("Apple", "1998"),
        m("Mango", null),
        m("Bravo", "2015"),
    )

    @Test fun defaultKeepsSourceOrder() {
        assertEquals(listOf("Zebra", "Apple", "Mango", "Bravo"), arrangeMovies(sample, MovieSort.DEFAULT, null).map { it.title })
    }

    @Test fun newestSortsYearDescendingWithNullsLast() {
        assertEquals(listOf("Zebra", "Bravo", "Apple", "Mango"), arrangeMovies(sample, MovieSort.YEAR_DESC, null).map { it.title })
    }

    @Test fun oldestSortsYearAscendingWithNullsLast() {
        assertEquals(listOf("Apple", "Bravo", "Zebra", "Mango"), arrangeMovies(sample, MovieSort.YEAR_ASC, null).map { it.title })
    }

    @Test fun titleSortsAlphabetically() {
        assertEquals(listOf("Apple", "Bravo", "Mango", "Zebra"), arrangeMovies(sample, MovieSort.TITLE, null).map { it.title })
    }

    @Test fun decadeFiltersByYearRange() {
        assertEquals(listOf("Bravo"), arrangeMovies(sample, MovieSort.DEFAULT, 2010..2019).map { it.title })
    }

    @Test fun decadeDropsTitlesWithoutYear() {
        assertEquals(listOf("Zebra"), arrangeMovies(sample, MovieSort.DEFAULT, 2020..2029).map { it.title })
    }

    @Test fun topRatedSortsByRatingWithUnratedLast() {
        val films = listOf(m("Low", "2020"), m("High", "2020"), m("None", "2020"))
        val ratings = mapOf("id:Low" to 5.0, "id:High" to 9.0)
        val sorted = arrangeMovies(films, MovieSort.TOP_RATED, null) { ratings[it.id] }
        assertEquals(listOf("High", "Low", "None"), sorted.map { it.title })
    }

    @Test fun decadeAndSortCombine() {
        val films = listOf(m("B", "2011"), m("A", "2013"), m("C", "2001"))
        assertEquals(listOf("A", "B"), arrangeMovies(films, MovieSort.YEAR_DESC, 2010..2019).map { it.title })
    }
}
