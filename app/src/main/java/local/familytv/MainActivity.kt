package local.familytv

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val requests = Executors.newSingleThreadExecutor()
    private val previews = Executors.newFixedThreadPool(3)
    private val suggestions = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val main = Handler(Looper.getMainLooper())
    private val artwork by lazy { Artwork(cacheDir.resolve("thumbnails")) }
    private val settings by lazy { getSharedPreferences("watch", MODE_PRIVATE) }
    private val library by lazy { LocalLibrary(getSharedPreferences("library", MODE_PRIVATE)) }
    private val enrichment by lazy { Enrichment(getSharedPreferences("tmdb", MODE_PRIVATE)) }
    private var playingMovie: Movie? = null
    private var episodeTitle: String? = null
    private val pages = LruCache<String, MoviePage>(4)
    private val searches = LruCache<String, SearchResults>(12)
    private val directories = LruCache<String, List<LibraryEntry>>(8)
    private var channels = emptyList<Programme>()
    private var screen = Screen.HOME
    private var lastHomeScreen = Screen.LIVE
    private var selectedChannel: String? = null
    private var channelGroup = ChannelGroup.BANGLADESH
    private var openingChannel = false
    private var moviePageUrl = Movies.firstPage
    private var categoryName = "Recent movies"
    private var searchTerm: String? = null
    private var selectedPoster = 0
    private var generation = 0
    private var player: ExoPlayer? = null
    private var playing: Programme? = null
    private var playbackView: PlayerView? = null
    private var alternatives = emptyList<Programme>()
    private val folderStack = ArrayDeque<LibraryEntry>()
    private val folderPositions = LruCache<String, Int>(12)
    private var directoryType = "tv_series"
    private var seriesQuery: String? = null
    private var directorySelection = 0
    private var seriesCategoryName: String? = null
    private var movieSort = MovieSort.DEFAULT
    private var movieDecade: IntRange? = null
    private val decadeOptions: List<Pair<String, IntRange?>> = listOf(
        "All years" to null,
        "2020s" to 2020..2029,
        "2010s" to 2010..2019,
        "2000s" to 2000..2009,
        "1990s" to 1990..1999,
        "Classic" to 0..1989,
    )
    private var movieGenre: String? = null
    private val genreOptions: List<Pair<String, String?>> = listOf(
        "All genres" to null,
        "Action" to "Action",
        "Comedy" to "Comedy",
        "Drama" to "Drama",
        "Horror" to "Horror",
        "Thriller" to "Thriller",
        "Romance" to "Romance",
        "Sci-Fi" to "Science Fiction",
        "Animation" to "Animation",
        "Family" to "Family",
        "Crime" to "Crime",
        "Documentary" to "Documentary",
    )

    private enum class Screen { HOME, LIVE, MOVIES, DETAILS, DIRECTORY }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        movieSort = runCatching { MovieSort.valueOf(settings.getString("movie-sort", "") ?: "") }
            .getOrDefault(MovieSort.DEFAULT)
        movieDecade = settings.getString("movie-decade", null)
            ?.let { saved -> decadeOptions.firstOrNull { it.first == saved }?.second }
        movieGenre = settings.getString("movie-genre", null)
            ?.let { saved -> genreOptions.firstOrNull { it.first == saved }?.second }
        seriesCategoryName = settings.getString("series-category", null)
        showHome()
    }

    private fun showHome() {
        screen = Screen.HOME
        generation++
        val page = page("Family TV")
        val lastChannelId = settings.getString("last-channel", null)
        val lastMovie = library.recent().firstOrNull { it.movie != null }
        val watchChannel = lastChannelCard(lastChannelId)
        val resumeMovie = continueMovieCard(lastMovie)
        val cardGap = dp(16)
        val shortcuts = LinearLayout(this).apply {
            addView(watchChannel, LinearLayout.LayoutParams(0, dp(100), 1f))
            addView(resumeMovie, LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginStart = cardGap })
        }
        page.addView(shortcuts, LinearLayout.LayoutParams(-1, -2))
        val movies = homeCard("Movies", "Browse and search films", R.drawable.home_movies) { showMovies() }
        val live = homeCard("Live TV", "Browse channels", R.drawable.home_live_tv) { showLive() }
        val series = homeCard("Dramas & Series", "Browse shows and episodes", R.drawable.home_dramas) {
            openFolders("tv_series")
        }
        val row = LinearLayout(this).apply {
            isBaselineAligned = false
            listOf(live, movies, series).forEachIndexed { index, card ->
                addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (index > 0) marginStart = cardGap
                })
            }
        }
        val gallery = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cardsWidth = (MeasureSpec.getSize(widthMeasureSpec) - 2 * cardGap).coerceAtLeast(0)
                // The artwork is 16:9. Cap the row if the TV has less vertical space.
                row.layoutParams.height = minOf(cardsWidth * 9 / (3 * 16), MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        gallery.addView(row, FrameLayout.LayoutParams(-1, 0, Gravity.CENTER_VERTICAL))
        page.addView(gallery, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })
        when {
            lastMovie != null -> resumeMovie
            lastChannelId != null -> watchChannel
            lastHomeScreen == Screen.MOVIES -> movies
            lastHomeScreen == Screen.DIRECTORY -> series
            else -> live
        }.requestFocus()
    }

    private fun lastChannelCard(channelId: String?): LinearLayout {
        val channel = channels.firstOrNull { it.id == channelId }
        val name = channel?.title ?: settings.getString("last-channel-title", null)
        val title = if (channelId == null) "Browse live TV" else name ?: "Resume live TV"
        val badge = label("▶", 22f, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF581521.toInt(), 12)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return homeShortcut("Last Channel", title, badge) {
            if (channelId == null) {
                showLive()
            } else {
                screen = Screen.LIVE
                lastHomeScreen = Screen.LIVE
                channelGroup = ChannelGroup.ALL
                if (channel != null) selectChannel(channel)
                else load(::allChannels, { loaded ->
                    channels = loaded
                    loaded.firstOrNull { it.id == channelId }?.let(::selectChannel) ?: showLive()
                }, { showLive() })
            }
        }
    }

    private fun continueMovieCard(entry: ResumeEntry?): LinearLayout {
        val movie = entry?.movie
        val poster = movie?.poster?.let { url ->
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                artwork.bind(this, url, dp(44), dp(66), CARD)
            }
        }
        return homeShortcut("Continue Watching", entry?.title ?: "Browse movies", poster, details = {
            if (entry != null) {
                val minutes = entry.position / 60_000
                val position = if (minutes == 0L) "${entry.position / 1000}s" else "$minutes min"
                addView(label("Resume at $position", 13f, color = SECONDARY))
                if (entry.duration > 0) {
                    addView(android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progress = ((entry.position.toDouble() / entry.duration) * 100).toInt().coerceIn(1, 100)
                        progressTintList = ColorStateList.valueOf(ACCENT)
                    }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(4) })
                }
            }
        }) {
            if (movie == null) showMovies()
            else load({ Movies.playback(movie) }, { sources ->
                playingMovie = movie
                episodeTitle = null
                startPlayback(sources)
            }, {
                android.widget.Toast.makeText(this, "This title is unavailable.", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun homeShortcut(
        heading: String,
        title: String,
        image: View?,
        details: LinearLayout.() -> Unit = {},
        open: () -> Unit,
    ) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 14)
            foreground = outline(14)
            isFocusable = true
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { open() }
            if (image != null) {
                addView(image, LinearLayout.LayoutParams(dp(44), dp(66)).apply { marginEnd = dp(12) })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(heading, 14f, bold = true, color = SECONDARY))
                addView(label(title, 20f, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                details()
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }

    private fun homeCard(
        title: String,
        description: String,
        fixedArtwork: Int,
        open: () -> Unit,
    ): FrameLayout {
        val card = FrameLayout(this).apply {
            background = rounded(CARD, 20)
            foreground = outline(20)
            clipToOutline = true
            isFocusable = true
            contentDescription = "$title. $description"
            setOnClickListener { open() }
        }
        card.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageResource(fixedArtwork)
        }, FrameLayout.LayoutParams(-1, -1))
        val caption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xF20F1011.toInt()),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(label(title, 25f, bold = true))
            addView(label(description, 15f, color = SECONDARY))
        }
        card.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return card
    }

    private fun showLive() {
        screen = Screen.LIVE
        lastHomeScreen = screen
        val page = page("Live TV")
        if (channels.isNotEmpty()) {
            showChannels(page)
            return
        }
        val status = message(page, "Loading channels…")
        load(::allChannels, { loaded ->
            channels = loaded
            status.visibility = View.GONE
            if (loaded.isEmpty()) showRetry(page, "Channels are unavailable.") { showLive() }
            else showChannels(page)
        }, {
            status.visibility = View.GONE
            showRetry(page, "Channels are unavailable.") { showLive() }
        })
    }

    private fun allChannels(): List<Programme> {
        val primary = runCatching { BuddyTv.channels() }.getOrDefault(emptyList())
        val additional = runCatching { Zflix.channels() }.getOrDefault(emptyList())
        val public = runCatching { IptvOrg.channels() }.getOrDefault(emptyList())
        fun key(channel: Programme) = titleKey(channel.title).replace(" ", "")
        val direct = primary.associateBy(::key)
        val zflixNames = additional.map(::key).toSet()
        val local = additional.distinctBy(::key).map {
            val backup = direct[key(it)]
            it.copy(fallbackUrl = backup?.url, groups = it.groups + backup?.groups.orEmpty())
        } +
            primary.filter { key(it) !in zflixNames }
        // Public internet channels extend coverage to any network. Keep the
        // local BDIX catalogue first (those streams are faster on the ISP) and
        // append only public channels not already provided locally.
        val localKeys = local.map(::key).toSet()
        return local + public.distinctBy(::key).filter { key(it) !in localKeys }
    }

    private fun showChannels(page: LinearLayout) {
        val groups = ChannelGroup.entries.filter { group ->
            group == ChannelGroup.ALL || channels.any { group in it.groups }
        }
        if (channelGroup !in groups) channelGroup = ChannelGroup.ALL
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val rail = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }
        page.addView(rail, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(15) })
        var available = filteredChannels()
        val list = GridView(this).apply {
            numColumns = if (resources.configuration.screenWidthDp >= 780) 5 else 4
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = object : BaseAdapter() {
                override fun getCount() = available.size
                override fun getItem(position: Int) = available[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView as? ChannelTile ?: ChannelTile()
                    row.bind(getItem(position))
                    return row
                }
            }
            setOnItemClickListener { _, _, position, _ -> selectChannel(available[position]) }
        }
        list.id = View.generateViewId()
        val chips = mutableMapOf<ChannelGroup, Button>()
        fun changeCategory(group: ChannelGroup) {
            if (channelGroup != group) {
                channelGroup = group
                available = filteredChannels()
                chips.forEach { (tag, control) -> control.isSelected = tag == group }
                (list.adapter as BaseAdapter).notifyDataSetChanged()
                list.nextFocusUpId = chips.getValue(group).id
                list.setSelection(0)
            }
        }
        for (group in groups) {
            val chip = button(group.label) {
                changeCategory(group)
                list.requestFocus()
            }.apply {
                id = View.generateViewId()
                isSelected = group == channelGroup
                nextFocusDownId = list.id
                setOnFocusChangeListener { _, focused -> if (focused) changeCategory(group) }
            }
            chips[group] = chip
            tabs.addView(chip)
        }
        list.nextFocusUpId = chips.getValue(channelGroup).id
        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.requestFocus()
        list.setSelection(available.indexOfFirst { it.id == selectedChannel }.coerceAtLeast(0))
    }

    private fun filteredChannels() = channels.filter { channelGroup == ChannelGroup.ALL || channelGroup in it.groups }

    private inner class ChannelTile : LinearLayout(this@MainActivity) {
        private val logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(0xFF29282C.toInt(), 10)
            clipToOutline = true
        }
        private val name = label("", 17f, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(132)
            background = rounded(CARD, 16)
            foreground = outline(16)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            addView(logo, LinearLayout.LayoutParams(dp(95), dp(62)).apply { bottomMargin = dp(8) })
            addView(name, LinearLayout.LayoutParams(-1, -2))
        }

        fun bind(channel: Programme) {
            contentDescription = channel.title
            name.text = channel.title
            artwork.bind(logo, channel.logo, dp(95), dp(62), Color.TRANSPARENT)
        }
    }

    private fun selectChannel(channel: Programme) {
        generation++
        openingChannel = false
        selectedChannel = channel.id
        if (channel.id.startsWith("zflix:")) {
            openingChannel = true
            playing = null
            val waiting = page(channel.title)
            val status = message(waiting, "Opening channel…")
            val fallback = channel.fallbackUrl?.let { channel.copy(url = it, fallbackUrl = null) }
            load({ Zflix.playback(channel) }, {
                openingChannel = false
                startPlayback(listOfNotNull(it, fallback))
            }, {
                if (fallback != null) {
                    openingChannel = false
                    startPlayback(listOf(fallback))
                } else {
                    status.text = "This channel is unavailable. Press Back to return."
                    channelActions(waiting, channel)
                }
            })
            return
        }
        alternatives = emptyList()
        playing = channel
        openPlayer(channel)
    }

    private fun showMovies() {
        screen = Screen.MOVIES
        lastHomeScreen = screen
        val page = page("Movies")
        val cached = pages.get(moviePageUrl)
        if (cached != null) {
            showPosters(page, cached)
            return
        }
        val status = message(page, if (searchTerm == null) "Loading movies…" else "Searching movies…")
        val url = moviePageUrl
        val query = searchTerm
        val recent = categoryName == "Recent movies"
        if (query == null && recent) {
            val saved = if (url == Movies.firstPage) library.moviePage() else null
            val update = saved?.let {
                status.visibility = View.GONE
                showPosters(page, it)
            }
            loadRecentProgressively(page, status, url, update, saved)
            return
        }
        load({
            if (query == null) CombinedCatalogue.movies(url, recent)
            else MoviePage(find(query).movies, null, null, emptyList())
        }, { result ->
            pages.put(url, result)
            status.visibility = View.GONE
            showPosters(page, result)
        }, {
            status.visibility = View.GONE
            showRetry(page, "Movies are unavailable right now.") { showMovies() }
            page.addView(button("Search movies") { showSearch() })
            page.addView(button("Public Domain Films") { switchMoviePage(ArchiveOrg.firstPage, "Public Domain Films") })
        })
    }

    private fun loadRecentProgressively(
        page: LinearLayout, status: TextView, url: String,
        initialUpdate: ((MoviePage) -> Unit)?, saved: MoviePage?,
    ) {
        val token = ++generation
        var primary: MoviePage? = null
        var secondary: List<Movie>? = null
        var completed = 0
        var update = initialUpdate
        var onscreen = saved?.movies.orEmpty()
        fun publish() {
            if (generation != token || isDestroyed || screen != Screen.MOVIES) return
            completed++
            val available = CombinedCatalogue.merge(primary?.movies.orEmpty(), secondary.orEmpty())
            if (available.isEmpty()) {
                if (completed == 2 && saved == null) {
                    status.visibility = View.GONE
                    showRetry(page, "Movies are unavailable right now.") { showMovies() }
                    page.addView(button("Search movies") { showSearch() })
                    page.addView(button("Public Domain Films") { switchMoviePage(ArchiveOrg.firstPage, "Public Domain Films") })
                }
                return
            }
            onscreen = CombinedCatalogue.merge(onscreen, available)
            val result = (primary ?: saved)?.copy(movies = onscreen) ?: MoviePage(onscreen, null, null, emptyList())
            if (completed == 2) {
                pages.put(url, result)
                if (url == Movies.firstPage) library.saveMoviePage(result.copy(movies = available))
            }
            status.visibility = View.GONE
            if (update == null) update = showPosters(page, result) else update?.invoke(result)
        }
        previews.execute {
            val value = runCatching { Movies.page(url) }.getOrNull()
            main.post { primary = value; publish() }
        }
        previews.execute {
            val value = runCatching { CombinedCatalogue.recentMovies(url) }.getOrDefault(emptyList())
            main.post { secondary = value; publish() }
        }
    }

    private fun buildMovieFilterBar(gridId: Int, onChange: () -> Unit): View {
        fun heading(text: String, leading: Int) =
            label(text, 13f, color = SECONDARY).apply { setPadding(dp(leading), 0, dp(4), 0) }

        // Row 1: Sort + Decade (DOWN falls through to the Genre row below).
        val sortDecade = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sortDecade.addView(heading("Sort", 2))
        val sortChips = mutableMapOf<MovieSort, Button>()
        for (sort in MovieSort.entries) {
            val chip = button(sort.label) {
                if (movieSort != sort) {
                    movieSort = sort
                    settings.edit().putString("movie-sort", sort.name).apply()
                    sortChips.forEach { (option, control) -> control.isSelected = option == sort }
                    onChange()
                }
            }.apply { isSelected = sort == movieSort }
            sortChips[sort] = chip
            sortDecade.addView(chip)
        }
        sortDecade.addView(heading("Decade", 14))
        val decadeChips = mutableListOf<Pair<IntRange?, Button>>()
        for ((name, range) in decadeOptions) {
            val chip = button(name) {
                if (movieDecade != range) {
                    movieDecade = range
                    settings.edit().putString("movie-decade", name).apply()
                    decadeChips.forEach { (option, control) -> control.isSelected = option == range }
                    onChange()
                }
            }.apply { isSelected = range == movieDecade }
            decadeChips.add(range to chip)
            sortDecade.addView(chip)
        }

        // Row 2: Genre (needs TMDB metadata; DOWN goes to the grid).
        val genreRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        genreRow.addView(heading("Genre", 2))
        val genreChips = mutableListOf<Pair<String?, Button>>()
        for ((name, value) in genreOptions) {
            val chip = button(name) {
                if (movieGenre != value) {
                    movieGenre = value
                    settings.edit().putString("movie-genre", name).apply()
                    genreChips.forEach { (option, control) -> control.isSelected = option == value }
                    onChange()
                }
            }.apply { isSelected = value == movieGenre; nextFocusDownId = gridId }
            genreChips.add(value to chip)
            genreRow.addView(chip)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(sortDecade)
                },
            )
            addView(
                HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(genreRow)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
                },
            )
        }
    }

    private fun showPosters(page: LinearLayout, result: MoviePage): (MoviePage) -> Unit {
        var model = result
        var nextUrl = result.next
        var prefetching = false
        var loaded = result.movies
        val posters = PosterAdapter(arrangeMovies(loaded, movieSort, movieDecade) { enrichment.cached(it)?.rating })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val scope = searchTerm?.let { "Results for “$it”" } ?: categoryName
        val pageNumber = android.net.Uri.parse(moviePageUrl).getQueryParameter("page") ?: "1"
        val heading = if (searchTerm != null) scope else "$scope  ·  Page $pageNumber"
        row.addView(label(heading, 19f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val search = button("Search") { showSearch() }
        row.addView(search)
        val filters = if (searchTerm != null) button("Browse") { resetMovies() }
        else button("Categories") { chooseCategory(model.categories) }
        row.addView(filters)
        val grid = GridView(this).apply {
            val width = resources.configuration.screenWidthDp
            numColumns = if (width >= 860) 5 else 4
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setDrawSelectorOnTop(false)
            adapter = posters
            setOnItemClickListener { _, _, position, _ ->
                selectedPoster = position
                showMovie(posters.getItem(position))
            }
        }
        grid.id = View.generateViewId()
        val previous = result.previous?.let { url ->
            button("Previous") { switchMoviePage(url, categoryName, searchTerm) }.also(row::addView)
        }
        val more = button("Next") { nextUrl?.let { switchMoviePage(it, categoryName, searchTerm) } }
            .also(row::addView)
        more.visibility = if (nextUrl == null) View.GONE else View.VISIBLE
        page.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        search.nextFocusDownId = grid.id
        filters.nextFocusDownId = grid.id

        fun render(resetPosition: Boolean) {
            val keep = if (resetPosition) 0 else grid.selectedItemPosition
            val byGenre = movieGenre?.let { genre ->
                loaded.filter {
                    enrichment.cached(it)?.genres?.any { name -> name.equals(genre, ignoreCase = true) } == true
                }
            } ?: loaded
            posters.movies = arrangeMovies(byGenre, movieSort, movieDecade) { enrichment.cached(it)?.rating }
            posters.notifyDataSetChanged()
            val last = posters.count - 1
            if (last >= 0 && grid.hasFocus()) grid.setSelection(keep.coerceIn(0, last))
        }

        // When a genre or Top-rated sort is active, enrich loaded movies that
        // aren't cached yet; each completion re-renders so results fill in.
        fun ensureEnriched(movies: List<Movie>) {
            if (movieGenre == null && movieSort != MovieSort.TOP_RATED) return
            movies.forEach { movie ->
                if (enrichment.cached(movie) == null) enrichment.enrich(movie) { render(false) }
            }
        }

        if (loaded.isEmpty()) {
            message(page, "No movies found. Try a different title.")
            search.requestFocus()
            return {}
        }
        page.addView(
            buildMovieFilterBar(grid.id) { render(true); ensureEnriched(loaded) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        page.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        more.nextFocusDownId = grid.id
        previous?.nextFocusDownId = grid.id
        grid.requestFocus()
        grid.setSelection(selectedPoster.coerceIn(0, (posters.count - 1).coerceAtLeast(0)))
        ensureEnriched(loaded)
        val rootUrl = moviePageUrl
        val includeRecent = categoryName == "Recent movies"
        val query = searchTerm
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, state: Int) = Unit
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                val target = nextUrl ?: return
                if (!grid.isAttachedToWindow || visible == 0 || prefetching || query != null ||
                    loaded.size >= 110 || first + visible + 5 < total
                ) return
                prefetching = true
                previews.execute {
                    val next = runCatching { CombinedCatalogue.movies(target, includeRecent) }.getOrNull()
                    main.post {
                        if (!grid.isAttachedToWindow || isDestroyed || nextUrl != target) return@post
                        if (next == null) return@post // The visible Next button still allows a retry.
                        nextUrl = next.next?.takeUnless { it == target }
                        loaded = CombinedCatalogue.merge(loaded, next.movies)
                        model = model.copy(movies = loaded, next = nextUrl)
                        pages.put(rootUrl, model)
                        render(false)
                        ensureEnriched(loaded)
                        more.visibility = if (nextUrl == null) View.GONE else View.VISIBLE
                        prefetching = false
                    }
                }
            }
        })
        return { updated ->
            if (!prefetching) nextUrl = updated.next
            loaded = CombinedCatalogue.merge(loaded, updated.movies)
            model = model.copy(movies = loaded, next = nextUrl)
            render(false)
            ensureEnriched(loaded)
            more.visibility = if (nextUrl == null) View.GONE else View.VISIBLE
        }
    }

    private fun showSearch(seriesOnly: Boolean = false) {
        val field = EditText(this).apply {
            hint = if (seriesOnly) "Show title" else "Movie title"
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            filters = arrayOf(InputFilter.LengthFilter(40))
            setText((if (seriesOnly) seriesQuery else searchTerm).orEmpty())
            setSelection(length())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(field, LinearLayout.LayoutParams(-1, -2))
        }
        val status = label("Type at least two letters", 15f, color = SECONDARY)
        val choices = ListView(this)
        box.addView(status)
        box.addView(choices, LinearLayout.LayoutParams(-1, dp(156)))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (seriesOnly) "Search dramas & series" else "Search movies")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search", null)
            .create()
        fun submit() {
            val query = field.text.toString().trim()
            if (query.length < 2) {
                field.error = "Enter at least two letters"
                return
            }
            dialog.dismiss()
            if (seriesOnly) {
                seriesQuery = query
                folderStack.clear()
                showSeries()
            } else {
                switchMoviePage(Movies.search(query), "Movies", query)
            }
        }
        var request: Runnable? = null
        fun updateSuggestions() {
            request?.let(main::removeCallbacks)
            choices.adapter = null
            val query = field.text.toString().trim()
            if (query.length < 2) {
                status.text = "Type at least two letters"
                return
            }
            status.text = "Searching…"
            val work = Runnable {
                suggestions.execute {
                    val result = runCatching {
                        if (seriesOnly) SearchResults(emptyList(), findShows(query)) else find(query)
                    }
                    main.post {
                        if (!dialog.isShowing || isDestroyed || field.text.toString().trim() != query) {
                            return@post
                        }
                        val found = result.getOrNull()
                        if (found == null) {
                            status.text = "Search is unavailable right now."
                            return@post
                        }
                        val names = if (seriesOnly) found.shows.map(LibraryEntry::title)
                        else found.movies.map { listOfNotNull(it.title, it.year).joinToString("  ·  ") }
                        status.text = if (names.isEmpty()) "No matches yet" else "Suggestions"
                        choices.adapter = ArrayAdapter(
                            this, android.R.layout.simple_list_item_1, names.take(8),
                        )
                        choices.setOnItemClickListener { _, _, index, _ ->
                            dialog.dismiss()
                            directorySelection = 0
                            if (seriesOnly) {
                                seriesQuery = query
                                folderStack.clear()
                                folderStack.addLast(found.shows[index])
                                showDirectory()
                            } else {
                                searchTerm = query
                                moviePageUrl = Movies.search(query)
                                categoryName = "Movies"
                                selectedPoster = index
                                pages.put(moviePageUrl, MoviePage(found.movies, null, null, emptyList()))
                                showMovie(found.movies[index])
                            }
                        }
                    }
                }
            }
            request = work
            main.postDelayed(work, 350)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) =
                updateSuggestions()
            override fun afterTextChanged(text: Editable?) = Unit
        })
        field.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) false else {
                submit()
                true
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            field.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            updateSuggestions()
        }
        dialog.setOnDismissListener { request?.let(main::removeCallbacks) }
        dialog.show()
    }

    private fun find(query: String): SearchResults {
        val key = titleKey(query)
        return searches.get(key) ?: CombinedCatalogue.search(query).also { searches.put(key, it) }
    }

    private fun findShows(query: String): List<LibraryEntry> {
        val index = library.shows()
        if (index != null) {
            val matches = Mazeda.matchShows(query, index)
            if (matches.isNotEmpty()) return matches
        }
        val live = Mazeda.showIndex()
        if (live.isNotEmpty()) library.saveShows(live)
        return Mazeda.matchShows(query, live).ifEmpty { Mazeda.search(query).shows }
    }

    private fun resetMovies() = switchMoviePage(Movies.firstPage, "Recent movies")

    private fun chooseCategory(categories: List<MovieCategory>) {
        // Put the most-used entries first so the common picks need no scrolling,
        // then the source's region/language folders, then the folder browser.
        val imdb = categories.filter { it.name.contains("IMDB", ignoreCase = true) }
        val regions = categories.filter { !it.name.contains("IMDB", ignoreCase = true) }
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(label: String, open: () -> Unit) {
            labels.add(label)
            actions.add(open)
        }
        add("Recent movies") { switchMoviePage(Movies.firstPage, "Recent movies") }
        add("Public Domain Films  ·  free, any network") {
            switchMoviePage(ArchiveOrg.firstPage, "Public Domain Films")
        }
        imdb.forEach { category -> add(category.name) { switchMoviePage(category.url, category.name) } }
        regions.forEach { category -> add(category.name) { switchMoviePage(category.url, category.name) } }
        add("More movie folders…") { openFolders("movie") }
        android.app.AlertDialog.Builder(this)
            .setTitle("Browse movies")
            .setItems(labels.toTypedArray()) { _, position -> actions[position]() }
            .show()
    }

    private fun switchMoviePage(url: String, category: String, query: String? = null) {
        moviePageUrl = url
        categoryName = category
        searchTerm = query
        selectedPoster = 0
        showMovies()
    }

    private fun openFolders(type: String) {
        directoryType = type
        seriesQuery = null
        folderStack.clear()
        directorySelection = 0
        if (type == "tv_series") showSeries() else showDirectory()
    }

    private fun directoryKey() = folderStack.lastOrNull()?.url ?: "$directoryType:${seriesQuery.orEmpty()}"

    private fun showDirectory() {
        screen = Screen.DIRECTORY
        val section = if (directoryType == "movie") "Movie folders" else "Dramas & Series"
        val current = folderStack.lastOrNull()
        val page = page(section)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = current?.title ?: seriesQuery?.let { "Results for “$it”" } ?: "Browse collections"
        row.addView(label(heading, 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (directoryType == "tv_series") {
            row.addView(button("Search") { showSearch(seriesOnly = true) })
            if (seriesQuery != null) row.addView(button("Browse") { openFolders("tv_series") })
        }
        page.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val key = directoryKey()
        val cached = directories.get(key)
        if (cached != null) {
            showEntries(page, cached)
            return
        }
        val query = seriesQuery
        val type = directoryType
        fun fetch(): List<LibraryEntry> =
            when {
                current != null -> Mazeda.folder(current.url)
                query != null -> findShows(query)
                else -> Mazeda.categories(type)
            }

        val saved = if (query == null) library.directory(key) else null
        if (saved != null) {
            showEntries(page, saved)
            previews.execute {
                val fresh = runCatching { fetch() }.getOrNull()
                if (!fresh.isNullOrEmpty()) {
                    directories.put(key, fresh)
                    library.saveDirectory(key, fresh)
                }
            }
            return
        }
        val status = message(page, "Loading…")
        load(::fetch, {
            directories.put(key, it)
            if (query == null) library.saveDirectory(key, it)
            status.visibility = View.GONE
            showEntries(page, it)
        }, {
            status.visibility = View.GONE
            showRetry(page, "This collection is unavailable right now.") { showDirectory() }
        })
    }

    private var seriesCategories: List<MovieCategory> = emptyList()

    // Dramas & Series landing: a Movies-style poster grid of a collection (or
    // search results), with Search, Categories picker and the shared filter bar.
    private fun showSeries() {
        screen = Screen.DIRECTORY
        directoryType = "tv_series"
        folderStack.clear()
        lastHomeScreen = Screen.DIRECTORY
        val page = page("Dramas & Series")
        val query = seriesQuery
        val mixDefault = query == null && (seriesCategoryName == null || seriesCategoryName == ALL_SHOWS)

        // The mix is slow to assemble (4 folders), so show the saved index instantly
        // and refresh it in the background; only the first-ever open waits.
        val cachedMix = if (mixDefault) library.shows()?.takeIf { it.isNotEmpty() } else null
        if (cachedMix != null) {
            seriesCategoryName = ALL_SHOWS
            if (seriesCategories.isEmpty()) {
                load({ Mazeda.categories("tv_series").map { MovieCategory(it.title, it.url) } },
                    { seriesCategories = it }, {})
            }
            renderSeriesGrid(page, ALL_SHOWS, cachedMix)
            previews.execute { runCatching { Mazeda.showIndex() }.getOrNull()?.let(library::saveShows) }
            return
        }

        val status = message(page, if (query == null) "Loading shows…" else "Searching…")
        load({
            if (query != null) {
                Triple(seriesCategories, "Results for “$query”", findShows(query))
            } else {
                val cats = Mazeda.categories("tv_series").map { MovieCategory(it.title, it.url) }
                val selected = cats.firstOrNull { it.name == seriesCategoryName }
                // No specific category (default, "All Shows", or unknown) → the merged mix.
                if (selected == null) Triple(cats, ALL_SHOWS, Mazeda.showIndex())
                else Triple(cats, selected.name, Mazeda.folder(selected.url))
            }
        }, { (cats, heading, shows) ->
            status.visibility = View.GONE
            if (query == null) {
                seriesCategories = cats
                seriesCategoryName = heading
            }
            renderSeriesGrid(page, heading, shows)
        }, {
            status.visibility = View.GONE
            showRetry(page, "Shows are unavailable right now.") { showSeries() }
        })
        if (query == null) previews.execute {
            runCatching { Mazeda.showIndex() }.getOrNull()?.let(library::saveShows)
        }
    }

    private fun renderSeriesGrid(page: LinearLayout, heading: String, shows: List<LibraryEntry>) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(heading, 19f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val search = button("Search") { showSearch(seriesOnly = true) }
        row.addView(search)
        if (seriesQuery != null) {
            row.addView(button("Browse") { seriesQuery = null; showSeries() })
        } else {
            row.addView(button("Categories") { chooseSeriesCategory() })
        }
        page.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        if (shows.isEmpty()) {
            message(page, if (seriesQuery != null) "No shows found." else "No shows here.")
            search.requestFocus()
            return
        }
        val adapter = SeriesAdapter(arrangeShows(shows))
        val grid = GridView(this).apply {
            val width = resources.configuration.screenWidthDp
            numColumns = if (width >= 860) 5 else 4
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setDrawSelectorOnTop(false)
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                val entry = adapter.getItem(position)
                directorySelection = position
                folderPositions.put(directoryKey(), position)
                folderStack.addLast(entry)
                directorySelection = 0
                showDirectory()
            }
        }
        grid.id = View.generateViewId()
        search.nextFocusDownId = grid.id

        fun render(resetPosition: Boolean) {
            val keep = if (resetPosition) 0 else grid.selectedItemPosition
            adapter.entries = arrangeShows(shows)
            adapter.notifyDataSetChanged()
            val last = adapter.count - 1
            if (last >= 0 && grid.hasFocus()) grid.setSelection(keep.coerceIn(0, last))
        }
        fun ensureEnriched() {
            if (movieGenre == null && movieSort != MovieSort.TOP_RATED) return
            shows.forEach { entry ->
                val (title, year) = showTitleYear(entry.title)
                if (enrichment.cachedShow(title, year) == null) {
                    enrichment.showMeta(title, year) { render(false) }
                }
            }
        }
        page.addView(
            buildMovieFilterBar(grid.id) { render(true); ensureEnriched() },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        page.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        grid.requestFocus()
        grid.setSelection(directorySelection.coerceIn(0, (adapter.count - 1).coerceAtLeast(0)))
        ensureEnriched()
    }

    private inner class SeriesAdapter(var entries: List<LibraryEntry>) : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tile = convertView as? PosterTile ?: PosterTile()
            val entry = entries[position]
            val (title, year) = showTitleYear(entry.title)
            tile.title.text = title
            tile.year.text = year.orEmpty()
            tile.year.visibility = if (year == null) View.GONE else View.VISIBLE
            tile.posterTitle.text = title
            tile.posterTitle.visibility = View.VISIBLE
            tile.contentDescription = title
            tile.showRating(null)
            tile.boundId = entry.url
            artwork.bind(tile.poster, null, dp(190), dp(190), Color.TRANSPARENT)
            enrichment.showMeta(title, year) { meta ->
                if (tile.boundId == entry.url) {
                    tile.showRating(meta.rating)
                    meta.poster?.let {
                        tile.posterTitle.visibility = View.GONE
                        artwork.bind(tile.poster, it, dp(190), dp(190), Color.TRANSPARENT)
                    }
                }
            }
            return tile
        }
    }

    private fun arrangeShows(entries: List<LibraryEntry>): List<LibraryEntry> {
        fun yearOf(entry: LibraryEntry) = showTitleYear(entry.title).second?.toIntOrNull()
        fun metaOf(entry: LibraryEntry) = showTitleYear(entry.title).let { enrichment.cachedShow(it.first, it.second) }
        var list = entries
        movieDecade?.let { range -> list = list.filter { (yearOf(it) ?: -1) in range } }
        movieGenre?.let { genre ->
            list = list.filter { metaOf(it)?.genres?.any { name -> name.equals(genre, ignoreCase = true) } == true }
        }
        return when (movieSort) {
            MovieSort.DEFAULT -> list
            MovieSort.YEAR_DESC -> list.sortedByDescending { yearOf(it) ?: Int.MIN_VALUE }
            MovieSort.YEAR_ASC -> list.sortedBy { yearOf(it) ?: Int.MAX_VALUE }
            MovieSort.TITLE -> list.sortedBy { titleKey(it.title) }
            MovieSort.TOP_RATED -> list.sortedByDescending { metaOf(it)?.rating ?: -1.0 }
        }
    }

    private fun chooseSeriesCategory() {
        val cats = seriesCategories
        if (cats.isEmpty()) return
        val labels = listOf(ALL_SHOWS) + cats.map(MovieCategory::name)
        android.app.AlertDialog.Builder(this)
            .setTitle("Browse shows")
            .setItems(labels.toTypedArray()) { _, position ->
                seriesCategoryName = labels[position]
                settings.edit().putString("series-category", labels[position]).apply()
                directorySelection = 0
                showSeries()
            }
            .show()
    }

    private fun showEntries(page: LinearLayout, entries: List<LibraryEntry>) {
        if (entries.isEmpty()) {
            message(page, if (seriesQuery != null && folderStack.isEmpty()) "No shows found." else "No playable titles here.")
            return
        }
        val list = ListView(this).apply {
            adapter = object : ArrayAdapter<LibraryEntry>(this@MainActivity, android.R.layout.simple_list_item_1, entries) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val entry = getItem(position) ?: return super.getView(position, convertView, parent)
                    return (convertView as? TextView ?: label("", 21f).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minHeight = dp(53)
                        setPadding(dp(16), 0, dp(16), 0)
                        background = rounded(CARD, 8)
                        foreground = outline(8)
                    }).apply {
                        text = if (entry.folder) "${entry.title}  ›" else "${entry.title}  ▶"
                        contentDescription = entry.title
                    }
                }
            }
            divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(6)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setOnItemClickListener { _, _, position, _ ->
                val entry = entries[position]
                directorySelection = position
                folderPositions.put(directoryKey(), position)
                if (entry.folder) {
                    folderStack.addLast(entry)
                    directorySelection = 0
                    showDirectory()
                } else {
                    load({ Mazeda.playback(entry) }, {
                        playingMovie = null
                        val show = folderStack.lastOrNull { folder -> !folder.title.startsWith("Season ") }
                        episodeTitle = listOfNotNull(show?.title?.substringBefore(" ["), entry.title).joinToString(" · ")
                        startPlayback(listOf(it))
                    }, {
                        android.widget.Toast.makeText(this@MainActivity, "This video is unavailable.", android.widget.Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.requestFocus()
        list.setSelection(directorySelection.coerceIn(0, entries.lastIndex))
    }

    private fun backFromDirectory() {
        when {
            folderStack.size > 1 -> {
                folderStack.removeLast()
                directorySelection = folderPositions.get(directoryKey()) ?: 0
                showDirectory()
            }
            folderStack.size == 1 -> {
                folderStack.removeLast()
                directorySelection = folderPositions.get(directoryKey()) ?: 0
                if (directoryType == "tv_series") showSeries() else showDirectory()
            }
            seriesQuery != null -> {
                seriesQuery = null
                showSeries()
            }
            directoryType == "movie" -> showMovies()
            else -> showHome()
        }
    }

    private fun showMovie(movie: Movie) {
        screen = Screen.DETAILS
        val page = page("Movies")
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(CARD, 12)
            clipToOutline = true
        }
        row.addView(poster, LinearLayout.LayoutParams(dp(184), dp(276)).apply { marginEnd = dp(28) })
        artwork.bind(poster, movie.poster, dp(184), dp(276), Color.TRANSPARENT)
        val metaLine = label("", 16f, color = ACCENT).apply { visibility = View.GONE }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(movie.title, 34f, bold = true).apply { maxLines = 2 })
            movie.year?.let { addView(label(it, 17f, color = ACCENT)) }
            addView(metaLine)
            movie.summary?.let {
                addView(label(it, 17f, color = SECONDARY).apply {
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(10), 0, dp(10))
                })
            }
        }
        row.addView(panel, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(row)
        enrichment.enrich(movie) { meta ->
            val parts = listOfNotNull(
                meta.rating?.let { "★ %.1f".format(it) },
                meta.genres.take(3).joinToString(" · ").takeIf(String::isNotEmpty),
            )
            metaLine.text = parts.joinToString("      ")
            metaLine.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
        val status = label("", 18f)
        val play = button("Play") {}
        play.setOnClickListener {
            play.isEnabled = false
            status.text = "Opening movie…"
            load({ Movies.playback(movie) }, {
                playingMovie = movie
                episodeTitle = null
                startPlayback(it)
            }, {
                play.isEnabled = true
                play.requestFocus()
                status.text = "This movie is unavailable right now."
            })
        }
        panel.addView(play, LinearLayout.LayoutParams(dp(180), dp(66)))
        panel.addView(status)
        play.requestFocus()
    }

    private inner class PosterAdapter(var movies: List<Movie>) : BaseAdapter() {
        override fun getCount() = movies.size
        override fun getItem(position: Int) = movies[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tile = convertView as? PosterTile ?: PosterTile()
            val movie = getItem(position)
            tile.title.text = movie.title
            tile.year.text = movie.year.orEmpty()
            tile.year.visibility = if (movie.year == null) View.GONE else View.VISIBLE
            tile.posterTitle.text = movie.title
            tile.posterTitle.visibility = if (movie.poster == null) View.VISIBLE else View.GONE
            tile.contentDescription = listOfNotNull(movie.title, movie.year).joinToString(", ")
            artwork.bind(tile.poster, movie.poster, dp(190), dp(190), Color.TRANSPARENT)
            tile.boundId = movie.id
            tile.showRating(null)
            enrichment.enrich(movie) { meta -> if (tile.boundId == movie.id) tile.showRating(meta.rating) }
            return tile
        }
    }

    private inner class PosterTile : LinearLayout(this@MainActivity) {
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF22324A.toInt(), 8)
            clipToOutline = true
        }
        val title = label("", 16f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(5), dp(4), 0)
        }
        val year = label("", 13f, color = SECONDARY).apply { setPadding(dp(4), 0, dp(4), dp(4)) }
        val posterTitle = label("", 20f, bold = true, color = SECONDARY).apply {
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(10), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        var boundId: String? = null
        val rating = label("", 12f, bold = true).apply {
            background = rounded(0xCC0A0A0C.toInt(), 8)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        init {
            orientation = VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(CARD, 12)
            foreground = outline(12)
            addView(FrameLayout(context).apply {
                addView(poster, FrameLayout.LayoutParams(-1, -1))
                addView(posterTitle, FrameLayout.LayoutParams(-1, -1))
                addView(rating, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(6)
                    marginEnd = dp(6)
                })
            }, LinearLayout.LayoutParams(-1, dp(190)))
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(year, LinearLayout.LayoutParams(-1, -2))
        }

        fun showRating(value: Double?) {
            if (value == null) {
                rating.visibility = View.GONE
            } else {
                rating.text = "★ %.1f".format(value)
                rating.visibility = View.VISIBLE
            }
        }
    }

    private fun <T> load(work: () -> T, success: (T) -> Unit, failure: () -> Unit) {
        val token = ++generation
        requests.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (token != generation || isDestroyed || isFinishing) return@runOnUiThread
                result.fold(success) { failure() }
            }
        }
    }

    private fun startPlayback(sources: List<Programme>) {
        alternatives = sources.drop(1)
        val first = sources.first()
        playing = first
        openPlayer(first)
    }

    private fun openPlayer(item: Programme) {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply { keepScreenOn = true }
        val status = label("Opening ${item.title}…", 20f).apply {
            setBackgroundColor(BACKGROUND)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val banner = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xED170A0D.toInt(), 14)
            setPadding(dp(14), dp(10), dp(16), dp(10))
            visibility = View.GONE
            if (item.live) {
                val logo = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                addView(logo, LinearLayout.LayoutParams(dp(62), dp(34)).apply { marginEnd = dp(10) })
                artwork.bind(logo, item.logo, dp(62), dp(34))
                addView(label(item.title, 21f, bold = true))
            }
        }
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        frame.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        frame.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            marginStart = dp(32)
            topMargin = dp(24)
        })
        setContentView(frame)
        playbackView = view
        val engine = ExoPlayer.Builder(this).build()
        player = engine
        view.player = engine
        var bannerShown = false
        engine.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    status.visibility = View.GONE
                    if (item.live && !bannerShown) {
                        bannerShown = true
                        settings.edit().putString("last-channel", item.id)
                            .putString("last-channel-title", item.title).apply()
                        banner.visibility = View.VISIBLE
                        main.postDelayed({ banner.visibility = View.GONE }, 2600)
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val next = alternatives.firstOrNull()
                if (next != null) {
                    main.post {
                        if (player !== engine) return@post
                        alternatives = alternatives.drop(1)
                        releasePlayer(saveProgress = false)
                        playing = next
                        openPlayer(next)
                    }
                    return
                }
                status.text = "This video is unavailable. Press Back to return."
                status.visibility = View.VISIBLE
                if (item.live) {
                    status.visibility = View.GONE
                    val panel = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = rounded(BACKGROUND, 16)
                        setPadding(dp(22), dp(14), dp(22), dp(16))
                        addView(label("${item.title} is unavailable", 22f, bold = true))
                    }
                    frame.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                    channelActions(panel, item)
                }
            }
        })
        val media = MediaItem.Builder().setUri(item.url)
        if (item.live) media.setMimeType(MimeTypes.APPLICATION_M3U8)
        engine.setMediaItem(media.build())
        if (!item.live) engine.seekTo(settings.getLong("position:${item.id}", 0L))
        engine.prepare()
        engine.playWhenReady = true
        view.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP -> 1
            else -> return super.dispatchKeyEvent(event)
        }
        if (playing?.live == true || openingChannel) {
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
            switchChannel(step)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun switchChannel(step: Int) {
        val lineup = filteredChannels()
        val index = lineup.indexOfFirst { it.id == selectedChannel }
        if (index < 0 || lineup.size < 2) return
        releasePlayer()
        selectChannel(lineup[(index + step + lineup.size) % lineup.size])
    }

    private fun channelActions(panel: LinearLayout, channel: Programme) {
        val retry = button("Retry") {
            releasePlayer()
            selectChannel(channels.firstOrNull { it.id == channel.id } ?: channel)
        }
        panel.addView(retry)
        if (filteredChannels().size > 1) panel.addView(button("Next channel") { switchChannel(1) })
        retry.requestFocus()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Uses classic TV remote Back navigation")
    override fun onBackPressed() {
        generation++
        if (openingChannel) {
            openingChannel = false
            showLive()
            return
        }
        if (playing != null) {
            releasePlayer()
            playing = null
            alternatives = emptyList()
            when (screen) {
                Screen.LIVE -> showLive()
                Screen.HOME -> showHome()
                Screen.DIRECTORY -> showDirectory()
                else -> showMovies()
            }
            return
        }
        when (screen) {
            Screen.HOME -> super.onBackPressed()
            Screen.LIVE -> showHome()
            Screen.MOVIES -> if (searchTerm == null) showHome() else resetMovies()
            Screen.DETAILS -> showMovies()
            Screen.DIRECTORY -> backFromDirectory()
        }
    }

    override fun onStart() {
        super.onStart()
        playing?.takeIf { player == null }?.let(::openPlayer)
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        requests.shutdownNow()
        previews.shutdownNow()
        suggestions.shutdownNow()
        main.removeCallbacksAndMessages(null)
        artwork.close()
        enrichment.close()
        super.onDestroy()
    }

    private fun savePosition(id: String, position: Long) {
        // Keep resume points for only the most recent handful of titles.
        val editor = settings.edit().putLong("position:$id", position)
        val stored = settings.getString("position-order", "")?.split("\n")?.filter(String::isNotEmpty).orEmpty()
        val (kept, removed) = capOrder(stored, id, 30)
        removed.forEach { editor.remove("position:$it") }
        editor.putString("position-order", kept.joinToString("\n")).apply()
    }

    private fun releasePlayer(saveProgress: Boolean = true) {
        val current = player ?: return
        playing?.takeIf { saveProgress && !it.live }?.let {
            val finished = current.playbackState == Player.STATE_ENDED
            val position = if (finished) 0L else current.currentPosition
            if (finished || position > 0L) {
                savePosition(it.id, position)
                library.remember(ResumeEntry(it.id, playingMovie?.title ?: episodeTitle ?: it.title,
                    position, current.duration, playingMovie, it.url.takeIf { playingMovie == null }))
            }
        }
        playbackView?.player = null
        playbackView = null
        current.release()
        player = null
    }

    private fun page(title: String): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(14), dp(30), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF29090F.toInt(), BACKGROUND, 0xFF050506.toInt()),
            )
        }
        layout.addView(label(title, 30f, bold = true).apply {
            setPadding(0, 0, 0, dp(8))
            setAccessibilityHeading(true)
        })
        setContentView(layout)
        return layout
    }

    private fun label(label: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun message(layout: LinearLayout, text: String) =
        label(text, 20f).also { layout.addView(it) }

    private fun showRetry(page: LinearLayout, status: String, retry: () -> Unit) {
        message(page, status)
        page.addView(button("Try again", retry).also { it.post { it.requestFocus() } })
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        isFocusable = true
        val states = arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf())
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(ACCENT, 20))
            addState(intArrayOf(android.R.attr.state_selected), rounded(0xFF45121A.toInt(), 20))
            addState(intArrayOf(), rounded(CARD, 20))
        }
        setTextColor(ColorStateList(states, intArrayOf(Color.WHITE, Color.WHITE, Color.WHITE)))
        minHeight = dp(42)
        minWidth = dp(66)
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(6) }
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun outline(radius: Int) = StateListDrawable().apply {
        fun border() = rounded(Color.TRANSPARENT, radius).apply { setStroke(dp(4), ACCENT) }
        addState(intArrayOf(android.R.attr.state_focused), border())
        addState(intArrayOf(android.R.attr.state_selected), border())
        addState(intArrayOf(), rounded(Color.TRANSPARENT, radius))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val ALL_SHOWS = "All Shows"
        val BACKGROUND = 0xFF0D0D0F.toInt()
        val CARD = 0xFF1D171B.toInt()
        val ACCENT = 0xFFFF384F.toInt()
        val SECONDARY = 0xFFD3C9C2.toInt()
    }
}
