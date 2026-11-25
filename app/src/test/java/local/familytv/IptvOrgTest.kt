package local.familytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the iptv-org M3U parser. No Android or network types are
 * touched, so these run on the build machine.
 */
class IptvOrgTest {
    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-logo="https://logo/somoy.png" group-title="News",Somoy TV (720p)
        https://cdn.example.com/somoy/master.m3u8
        #EXTINF:-1 group-title="Entertainment",ATN Bangla (1080p) [Not 24/7]
        https://cdn.example.com/atn/index.m3u8
        #EXTINF:-1 group-title="Movies",Some Insecure Channel (480p)
        http://insecure.example.com/x.m3u8
        #EXTINF:-1 group-title="Kids",Duronto TV
        https://cdn.example.com/duronto/live.m3u8
        #EXTINF:-1 group-title="News",Somoy TV (1080p)
        https://cdn.example.com/somoy-hd/master.m3u8
    """.trimIndent()

    private val channels by lazy { IptvOrg.parse(sample) }

    @Test fun dropsHttpAndDeduplicatesByChannel() {
        // 5 entries: the http one is dropped, the duplicate Somoy TV collapses.
        assertEquals(3, channels.size)
        assertTrue(channels.none { it.url.startsWith("http://") })
        assertTrue(channels.all { it.id.startsWith("iptv:") })
    }

    @Test fun stripsResolutionAndBracketTags() {
        val atn = channels.first { it.id == "iptv:atnbangla" }
        assertEquals("ATN Bangla", atn.title)
    }

    @Test fun keepsFirstUrlForDuplicateChannel() {
        val somoy = channels.first { it.id == "iptv:somoytv" }
        assertEquals("https://cdn.example.com/somoy/master.m3u8", somoy.url)
    }

    @Test fun mapsGroupTitleAndWhitelistToChannelGroups() {
        val somoy = channels.first { it.id == "iptv:somoytv" }
        assertTrue(somoy.groups.containsAll(setOf(ChannelGroup.BANGLADESH, ChannelGroup.NEWS)))
        val duronto = channels.first { it.id == "iptv:durontotv" }
        assertTrue(duronto.groups.contains(ChannelGroup.KIDS))
    }

    @Test fun carriesLogoWhenPresent() {
        val somoy = channels.first { it.id == "iptv:somoytv" }
        assertEquals("https://logo/somoy.png", somoy.logo)
        assertTrue(somoy.live)
    }
}
