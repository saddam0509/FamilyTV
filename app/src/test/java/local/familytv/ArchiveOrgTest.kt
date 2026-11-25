package local.familytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for Internet Archive playable-file selection. */
class ArchiveOrgTest {
    private fun file(name: String, format: String) = ArchiveFile(name, format)

    @Test fun prefersSmallMp4OverOriginalAndOgv() {
        val files = listOf(
            file("feature.ogv", "Ogg Video"),
            file("feature.mp4", "h.264"),
            file("feature_512kb.mp4", "512Kb MPEG4"),
            file("feature_archive.torrent", "Archive BitTorrent"),
            file("__ia_thumb.jpg", "JPEG Thumb"),
        )
        assertEquals("feature_512kb.mp4", ArchiveOrg.pickPlayable(files))
    }

    @Test fun fallsBackToOgvWhenNoMp4() {
        val files = listOf(file("reel.ogv", "Ogg Video"), file("poster.gif", "Animated GIF"))
        assertEquals("reel.ogv", ArchiveOrg.pickPlayable(files))
    }

    @Test fun skipsThumbnailVideosAndReturnsNullWhenNothingPlayable() {
        val files = listOf(file("movie.thumbs.mp4", "Thumbnail"), file("cover.jpg", "JPEG"))
        assertNull(ArchiveOrg.pickPlayable(files))
    }
}
