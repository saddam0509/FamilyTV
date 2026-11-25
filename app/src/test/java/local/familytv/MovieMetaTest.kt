package local.familytv

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieMetaTest {
    @Test fun roundTripsRatingAndGenres() {
        val meta = MovieMeta(listOf("Drama", "Thriller"), 7.8)
        val decoded = decodeMeta(encodeMeta(meta))
        assertEquals(listOf("Drama", "Thriller"), decoded.genres)
        assertEquals(7.8, decoded.rating!!, 0.001)
    }

    @Test fun handlesMissingRating() {
        val decoded = decodeMeta(encodeMeta(MovieMeta(listOf("Comedy"), null)))
        assertEquals(null, decoded.rating)
        assertEquals(listOf("Comedy"), decoded.genres)
    }

    @Test fun handlesEmptyMeta() {
        val decoded = decodeMeta(encodeMeta(MovieMeta(emptyList(), null)))
        assertEquals(emptyList<String>(), decoded.genres)
        assertEquals(null, decoded.rating)
        assertEquals(false, decoded.known)
    }

    @Test fun ratingOnlyIsKnown() {
        assertEquals(true, MovieMeta(emptyList(), 6.5).known)
    }

    @Test fun roundTripsPoster() {
        val decoded = decodeMeta(encodeMeta(MovieMeta(emptyList(), null, "https://img/x.jpg")))
        assertEquals("https://img/x.jpg", decoded.poster)
    }

    @Test fun decodesOldTwoFieldFormatWithoutPoster() {
        val decoded = decodeMeta("7.8|Drama,Thriller")
        assertEquals(listOf("Drama", "Thriller"), decoded.genres)
        assertEquals(7.8, decoded.rating!!, 0.001)
        assertEquals(null, decoded.poster)
    }
}
