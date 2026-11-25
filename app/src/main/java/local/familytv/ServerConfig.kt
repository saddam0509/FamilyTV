package local.familytv

/**
 * Global configuration for external services, API keys, and local ISP endpoints.
 *
 * Out of the box, Family TV includes support for public open sources:
 * - Public Live TV via iptv-org (Bengali & international HTTPS streams)
 * - Public Domain Feature Films via Internet Archive
 *
 * If your ISP provides local network/BDIX media portals, you can customize the
 * server endpoints below or toggle [enableLocalIspSources].
 */
object ServerConfig {
    /**
     * TMDB (The Movie Database) v3 API key for genre/rating lookups and TV poster enrichment.
     * Obtain a key from https://www.themoviedb.org/ if desired.
     */
    var tmdbApiKey: String = ""

    /**
     * Toggle whether local ISP / BDIX scrapers are queried.
     * When set to false, only public HTTPS internet sources are used.
     */
    var enableLocalIspSources: Boolean = true

    /** Local ISP Live TV Endpoint: BuddyTV */
    var buddyTvHost: String = "http://172.27.27.3/"

    /** Local ISP Live TV Endpoint: Zflix */
    var zflixHost: String = "http://zflixbd.com"

    /** Local ISP Movie Portal Host */
    var moviesHost: String = "172.27.27.1"

    /** Local ISP Drama & Series API Endpoint: Mazeda FTP */
    var mazedaApiHost: String = "http://172.22.22.101/webhome/mazeda-ftp-be/api/"
}
