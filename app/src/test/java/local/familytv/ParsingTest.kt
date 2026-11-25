package local.familytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM characterization tests for the scraping/parsing helpers.
 *
 * These lock in the current behaviour of the fragile string handling that
 * turns scraped markup and filenames into catalogue entries, so a regression
 * (or an accidental change) is caught on the build machine instead of on the TV.
 * None of these touch Android or the network.
 */
class TitleKeyTest {
    @Test fun lowercasesAndStripsPunctuation() {
        assertEquals("the dark knight 2008", titleKey("The Dark Knight (2008)"))
    }

    @Test fun collapsesSeparatorsAndTrims() {
        assertEquals("hello world", titleKey("  Hello_World!!  "))
    }

    @Test fun apostropheBecomesSpace() {
        assertEquals("grey s anatomy", titleKey("Grey's Anatomy"))
    }
}

class ChannelGroupTest {
    @Test fun bangladeshiNewsChannel() {
        val groups = ChannelGroup.classify("Somoy TV", emptySet())
        assertTrue(groups.containsAll(setOf(ChannelGroup.BANGLADESH, ChannelGroup.NEWS)))
    }

    @Test fun kidsChannel() {
        assertEquals(setOf(ChannelGroup.KIDS), ChannelGroup.classify("Cartoon Network", emptySet()))
    }

    @Test fun unknownFallsBackToEntertainment() {
        assertEquals(setOf(ChannelGroup.ENTERTAINMENT), ChannelGroup.classify("HBO", emptySet()))
    }

    @Test fun providerHintAddsSports() {
        assertTrue(ChannelGroup.classify("Ten 1", setOf("sports")).contains(ChannelGroup.SPORTS))
    }

    @Test fun tSportsIsBangladeshiAndSports() {
        val groups = ChannelGroup.classify("T Sports", emptySet())
        assertTrue(groups.containsAll(setOf(ChannelGroup.BANGLADESH, ChannelGroup.SPORTS)))
    }
}

class MovieTitleTest {
    @Test fun extractsTitleAndYearFromDottedName() {
        val (title, year) = Mazeda.movieTitle("The.Raid.Redemption.2011.1080p.mkv")
        assertEquals("The Raid Redemption", title)
        assertEquals("2011", year)
    }

    @Test fun stripsExtensionAndUnderscores() {
        val (title, year) = Mazeda.movieTitle("Avengers_Endgame_2019.mp4")
        assertEquals("Avengers Endgame", title)
        assertEquals("2019", year)
    }

    @Test fun noYearLeavesYearNull() {
        val (title, year) = Mazeda.movieTitle("SomeClip.webm")
        assertEquals("SomeClip", title)
        assertEquals(null, year)
    }

    @Test fun lastYearWins() {
        val (title, year) = Mazeda.movieTitle("2001 A Space Odyssey 1968.mkv")
        assertEquals("2001 A Space Odyssey", title)
        assertEquals("1968", year)
    }
}

class MergeTest {
    private fun movie(
        id: String,
        title: String,
        year: String?,
        details: String = "",
        files: List<String> = emptyList(),
    ) = Movie(id, title, details, null, year, null, files)

    @Test fun combinesByTitleAndYearIgnoringThePrefix() {
        val primary = listOf(movie("1", "The Matrix", "1999", files = listOf("f1")))
        val secondary = listOf(movie("2", "Matrix", "1999", details = "d2", files = listOf("f2")))
        val merged = CombinedCatalogue.merge(primary, secondary)
        assertEquals(1, merged.size)
        assertEquals("d2", merged[0].details)
        assertEquals(listOf("f1", "f2"), merged[0].files)
    }

    @Test fun combinesBySharedId() {
        val merged = CombinedCatalogue.merge(
            listOf(movie("x", "A", "2000", files = listOf("f1"))),
            listOf(movie("x", "A alt", "2000", details = "da", files = listOf("f2"))),
        )
        assertEquals(1, merged.size)
        assertEquals(listOf("f1", "f2"), merged[0].files)
    }

    @Test fun keepsDistinctTitles() {
        val merged = CombinedCatalogue.merge(
            listOf(movie("1", "Alpha", "2001")),
            listOf(movie("2", "Beta", "2002")),
        )
        assertEquals(2, merged.size)
    }

    @Test fun nullYearDoesNotCollapseDifferentIds() {
        val merged = CombinedCatalogue.merge(
            listOf(movie("1", "Gamma", null)),
            listOf(movie("2", "Gamma", null)),
        )
        assertEquals(2, merged.size)
    }
}
