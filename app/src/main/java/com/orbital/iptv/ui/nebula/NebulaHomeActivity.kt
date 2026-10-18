package com.orbital.iptv.ui.nebula

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.orbital.iptv.R
import com.orbital.iptv.data.emby.EmbyItem
import com.orbital.iptv.data.emby.EmbyRepository
import com.orbital.iptv.data.model.FavType
import com.orbital.iptv.data.model.FavouriteItem
import com.orbital.iptv.data.model.getDecodedTitle
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.ui.vod.MovieDetailActivity
import com.orbital.iptv.utils.ContentCache
import com.orbital.iptv.utils.EpgCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.orbital.iptv.databinding.ActivityNebulaHomeBinding
import com.orbital.iptv.ui.emby.EmbyBrowserActivity
import com.orbital.iptv.ui.favourites.FavouritesActivity
import com.orbital.iptv.ui.home.HomeActivity
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.ui.search.GlobalSearchActivity
import com.orbital.iptv.ui.series.SeriesActivity
import com.orbital.iptv.ui.sports.SportsActivity
import com.orbital.iptv.ui.vod.VodActivity
import com.orbital.iptv.utils.EmbyPrefsManager
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PlayerLauncher
import com.orbital.iptv.utils.PrefsManager
import com.orbital.iptv.utils.RecentChannelsManager
import com.orbital.iptv.utils.ThemeManager
import com.orbital.iptv.utils.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home screen for the NEBULA theme: icon rail on the left, a featured "continue watching" hero,
 * then rows for Live TV (recent channels), Movies, Series and (if signed in) Emby. Everything
 * else (Live TV guide, settings) is the classic HomeActivity launched with EXTRA_CLASSIC.
 */
class NebulaHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNebulaHomeBinding
    private val embyRepo = EmbyRepository()
    private var refreshGen = 0
    private var heroSet = false
    private var firstFocusDone = false
    private var updateChecked = false

    private val xtream = XtreamRepository()
    private data class HeroMovie(
        val streamId: Int, val name: String, val posterUrl: String?, val meta: String,
        val plot: String, val ext: String, val rating: String
    )
    private var heroMovies: List<HeroMovie> = emptyList()
    private var heroIndex = 0
    private var heroLoadedAt = 0L
    private var heroShowingMovies = false
    private val heroHandler = Handler(Looper.getMainLooper())
    private val rotateRunnable = object : Runnable {
        override fun run() {
            if (heroShowingMovies && heroMovies.size > 1) {
                heroIndex = (heroIndex + 1) % heroMovies.size
                showHeroMovie(heroIndex)
                heroHandler.postDelayed(this, HERO_ROTATE_MS)
            }
        }
    }
    private var liveAdapter: CardAdapter? = null
    private var lastProfileId: String? = null

    private companion object { const val HERO_ROTATE_MS = 8_000L }

    private data class Card(
        val title: String,
        val subtitle: String,
        val imageUrl: String?,
        val progress: Float = 0f,
        val landscape: Boolean = false,
        val onLongPress: (() -> Unit)? = null,
        val onClick: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ThemeManager.load(this)
        if (ThemeManager.current != ThemeManager.AppTheme.NEBULA) {
            routeToClassicHome(); return
        }
        binding = ActivityNebulaHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNav()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@NebulaHomeActivity, ThemeManager.dialogStyle())
                    .setTitle("EXIT ORBITAL")
                    .setMessage("Are you sure you want to exit?")
                    .setPositiveButton("EXIT") { _, _ -> finishAffinity() }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        // Theme was changed away from NEBULA from the settings screen we launched — hand back to
        // whichever home that theme uses.
        ThemeManager.load(this)
        if (ThemeManager.current != ThemeManager.AppTheme.NEBULA) {
            routeToClassicHome(); return
        }
        NebulaRail.refresh(this, binding.rail)
        // A different server was picked in Settings: drop the previous server's movie hero.
        val profileId = PrefsManager.getActiveProfileId(this)
        if (profileId != lastProfileId) {
            if (lastProfileId != null) { heroMovies = emptyList(); heroLoadedAt = 0L; heroIndex = 0 }
            lastProfileId = profileId
        }
        refreshRows()
        com.orbital.iptv.utils.EpgCache.autoRefreshIfStale(this)
        if (!updateChecked) { updateChecked = true; checkForUpdates() }
    }

    override fun onPause() {
        super.onPause()
        heroHandler.removeCallbacks(rotateRunnable)
    }

    private fun routeToClassicHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun openClassic(openSettings: Boolean = false) {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            putExtra(HomeActivity.EXTRA_CLASSIC, true)
            putExtra(HomeActivity.EXTRA_OPEN_SETTINGS, openSettings)
        })
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            val update = UpdateChecker.check(this@NebulaHomeActivity) ?: return@launch
            AlertDialog.Builder(this@NebulaHomeActivity, ThemeManager.dialogStyle())
                .setTitle("UPDATE AVAILABLE — v${update.versionName}")
                .setMessage(update.releaseNotes.uppercase().ifEmpty { "A NEW VERSION OF ORBITAL IS AVAILABLE." })
                .setPositiveButton("UPDATE NOW") { _, _ -> openClassic() }
                .setNegativeButton("LATER", null)
                .show()
        }
    }

    private fun setupNav() {
        NebulaRail.bind(
            this, binding.rail, NebulaRail.Item.HOME,
            onHome = { binding.nbScroll.smoothScrollTo(0, 0); binding.nbBtnWatch.requestFocus() },
            onLive = { openLive() }
        )
        binding.nbBtnGuide.setOnClickListener { openClassic() }
    }

    private fun openLive() = startActivity(Intent(this, NebulaLiveActivity::class.java))

    // ── Rows ──────────────────────────────────────────────────────────────────

    private fun refreshRows() {
        val gen = ++refreshGen
        binding.nbRows.removeAllViews()
        heroSet = false

        val recentLive = RecentChannelsManager.getAll(this)
        val liveCards = recentLive.map { item ->
            Card(
                title = item.title, subtitle = "Live TV", imageUrl = item.artUrl.ifBlank { null },
                landscape = true,
                onLongPress = { confirmRemove(item.title) { RecentChannelsManager.remove(this, item.streamId); refreshRows() } },
                onClick = { playLive(item) }
            )
        }
        val all = FavouritesManager.getAll(this)
        val movieCards = all.filter { it.type == FavType.MOVIE && it.hasResume }.map { item ->
            Card(
                title = item.title,
                subtitle = "${(item.progressFraction * 100).toInt()}% watched",
                imageUrl = item.artUrl.ifBlank { null },
                progress = item.progressFraction,
                onLongPress = { confirmRemove(item.title) { FavouritesManager.remove(this, item.id); refreshRows() } },
                onClick = { playFavourite(item) }
            )
        }
        val seriesCards = all.filter { it.type == FavType.EPISODE }.map { item ->
            val se = buildString {
                if (item.season.isNotBlank()) append("S${item.season} ")
                if (item.episodeNum > 0) append("E${item.episodeNum}")
            }.trim()
            Card(
                title = item.title,
                subtitle = if (item.isUpNext) "Up next" + (if (se.isNotEmpty()) " · $se" else "") else se,
                imageUrl = item.artUrl.ifBlank { null },
                progress = item.progressFraction,
                onLongPress = { confirmRemove(item.title) { FavouritesManager.remove(this, item.id); refreshRows() } },
                onClick = { playFavourite(item) }
            )
        }

        liveAdapter = addRow("Continue Watching: Live TV", liveCards)
        loadLiveNow(recentLive)
        addRow("Continue Watching: Movies", movieCards)
        addRow("Continue Watching: Series", seriesCards)

        // Hero rotates through the newest movies on the server; until those load (or with no
        // server library) it falls back to the most recent thing being watched.
        if (heroMovies.isNotEmpty()) {
            showHeroMovie(heroIndex)
            startHeroRotation()
        } else {
            val heroCard = movieCards.firstOrNull() ?: seriesCards.firstOrNull() ?: liveCards.firstOrNull()
            if (heroCard != null) setHero(heroCard, if (heroCard.landscape) "LIVE TV" else "CONTINUE WATCHING")
            else setHeroEmpty()
        }
        loadHeroMovies()

        updateEmptyState()
        loadEmbyRow(gen)

        if (!firstFocusDone) {
            firstFocusDone = true
            binding.root.post { binding.nbBtnWatch.requestFocus() }
        }
    }

    private fun loadEmbyRow(gen: Int) {
        val s = EmbyPrefsManager.getSession(this) ?: return
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                embyRepo.getResumeItems(s.serverUrl, s.userId, s.token).getOrDefault(emptyList())
            }
            if (gen != refreshGen || items.isEmpty()) return@launch
            val cards = items.map { item ->
                val prog = ((item.userData?.playedPercentage ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                Card(
                    title = if (item.type == "Episode") (item.seriesName ?: item.name) else item.name,
                    subtitle = if (item.type == "Episode") embyEpisodeLabel(item) else (item.productionYear?.toString() ?: "Movie"),
                    imageUrl = embyArtworkUrl(item, s),
                    progress = prog,
                    onClick = { playEmby(item) }
                )
            }
            addRow("Continue Watching: Emby", cards)
            if (!heroSet) setHero(cards.first(), "EMBY")
            updateEmptyState()
        }
    }

    private fun embyEpisodeLabel(item: EmbyItem): String {
        val se = item.parentIndexNumber?.let { "S$it" } ?: ""
        val ep = item.indexNumber?.let { "E%02d".format(it) } ?: ""
        return "$se$ep ${item.name}".trim()
    }

    private fun embyArtworkUrl(item: EmbyItem, s: EmbyPrefsManager.EmbySession): String? {
        val tag = item.imageTags?.get("Primary")
        if (tag != null) return embyRepo.buildArtworkUrl(s.serverUrl, item.id, s.token, tag)
        if (item.type == "Episode" && item.seriesId != null && item.seriesPrimaryImageTag != null)
            return embyRepo.buildArtworkUrl(s.serverUrl, item.seriesId, s.token, item.seriesPrimaryImageTag)
        return null
    }

    private fun updateEmptyState() {
        binding.nbEmpty.visibility = if (binding.nbRows.childCount == 0) View.VISIBLE else View.GONE
    }

    private fun addRow(title: String, cards: List<Card>): CardAdapter? {
        if (cards.isEmpty()) return null
        val d = resources.displayMetrics.density

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((32 * d).toInt(), (20 * d).toInt(), (32 * d).toInt(), (6 * d).toInt())
        }
        header.addView(View(this).apply {
            setBackgroundColor(ThemeManager.palette().accent)
            layoutParams = LinearLayout.LayoutParams((3 * d).toInt(), (18 * d).toInt()).also { it.marginEnd = (10 * d).toInt() }
        })
        header.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        binding.nbRows.addView(header)

        val rowAdapter = CardAdapter(cards)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NebulaHomeActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = rowAdapter
            itemAnimator = null
            isNestedScrollingEnabled = false
            clipToPadding = false
            setPadding((26 * d).toInt(), 0, (26 * d).toInt(), 0)
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        binding.nbRows.addView(rv)
        return rowAdapter
    }

    private fun leaveMovieHero() {
        heroShowingMovies = false
        heroHandler.removeCallbacks(rotateRunnable)
        binding.nbHeroPlot.visibility = View.GONE
        binding.nbHeroDots.visibility = View.GONE
        binding.nbBtnGuide.text = "TV Guide"
        binding.nbBtnGuide.setOnClickListener { openClassic() }
    }

    private fun setHero(card: Card, badge: String) {
        leaveMovieHero()
        heroSet = true
        binding.nbHeroBadge.text = badge
        binding.nbHeroTitle.text = card.title
        binding.nbHeroMeta.text = card.subtitle
        binding.nbBtnWatch.visibility = View.VISIBLE
        binding.nbBtnWatch.setOnClickListener { card.onClick() }
        if (card.landscape) {
            // Live channel: no backdrop art, so show its logo on the right instead.
            binding.nbHeroArt.setImageDrawable(null)
            binding.nbHeroLogo.visibility = View.VISIBLE
            Glide.with(this).load(card.imageUrl).fitCenter().into(binding.nbHeroLogo)
        } else {
            binding.nbHeroLogo.visibility = View.GONE
            if (card.imageUrl != null) Glide.with(this).load(card.imageUrl).centerCrop().into(binding.nbHeroArt)
            else binding.nbHeroArt.setImageDrawable(null)
        }
    }

    private fun setHeroEmpty() {
        leaveMovieHero()
        heroSet = false
        binding.nbHeroBadge.text = "WELCOME"
        binding.nbHeroTitle.text = "Orbital"
        binding.nbHeroMeta.text = "Live TV, movies and series in one place."
        binding.nbBtnWatch.setOnClickListener { openClassic() }
        binding.nbHeroArt.setImageDrawable(null)
        binding.nbHeroLogo.visibility = View.GONE
    }

    // ── New-movies hero ───────────────────────────────────────────────────────

    private fun loadHeroMovies() {
        val creds = PrefsManager.getCredentials(this) ?: return
        if (heroMovies.isNotEmpty() && System.currentTimeMillis() - heroLoadedAt < 30 * 60_000L) return
        lifecycleScope.launch {
            val movies = withContext(Dispatchers.IO) {
                // The Movies section's own "Recently Added" list: a category named like
                // recent/new/latest/added, else the first category (the one that section opens on).
                val cats = xtream.getVodCategories(creds.serverUrl, creds.username, creds.password).getOrNull().orEmpty()
                val recentRegex = Regex("recent|latest|new|added", RegexOption.IGNORE_CASE)
                val pick = cats.firstOrNull { recentRegex.containsMatchIn(it.categoryName) } ?: cats.firstOrNull()
                val pool = pick?.let { xtream.getVodStreams(creds.serverUrl, creds.username, creds.password, it.categoryId).getOrNull() }
                    ?: ContentCache.getMovies(this@NebulaHomeActivity, creds.serverUrl).orEmpty()
                pool
                    .filter { !it.streamIcon.isNullOrBlank() }
                    // Newest first when the server says when things were added; otherwise its own order.
                    .sortedByDescending { it.added?.toLongOrNull() ?: 0L }
                    .take(6)
                    .map { m ->
                        async {
                            val info = xtream.getVodInfo(creds.serverUrl, creds.username, creds.password, m.streamId).getOrNull()?.info
                            val year = info?.releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
                            val rating = (info?.rating ?: m.rating)?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" } ?: ""
                            HeroMovie(
                                streamId = m.streamId,
                                name = info?.name?.takeIf { it.isNotBlank() } ?: m.name,
                                posterUrl = info?.coverBig?.takeIf { it.isNotBlank() } ?: info?.movieImage?.takeIf { it.isNotBlank() } ?: m.streamIcon,
                                meta = listOfNotNull(
                                    year,
                                    info?.genre?.split(",", "/")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() },
                                    rating.takeIf { it.isNotBlank() }?.let { "\u2605 $it" }
                                ).joinToString("  \u00B7  "),
                                plot = (info?.plot ?: info?.description ?: "").trim(),
                                ext = m.containerExtension ?: "mp4",
                                rating = rating
                            )
                        }
                    }.awaitAll()
            }
            if (movies.isEmpty()) return@launch
            heroMovies = movies
            heroLoadedAt = System.currentTimeMillis()
            heroIndex = 0
            showHeroMovie(0)
            startHeroRotation()
        }
    }

    private fun startHeroRotation() {
        heroHandler.removeCallbacks(rotateRunnable)
        if (heroMovies.size > 1) heroHandler.postDelayed(rotateRunnable, HERO_ROTATE_MS)
    }

    private fun showHeroMovie(index: Int) {
        val m = heroMovies.getOrNull(index) ?: return
        heroShowingMovies = true
        heroSet = true
        binding.nbHeroBadge.text = "NEW MOVIE"
        binding.nbHeroTitle.text = m.name
        binding.nbHeroMeta.text = m.meta
        binding.nbHeroPlot.text = m.plot
        binding.nbHeroPlot.visibility = if (m.plot.isBlank()) View.GONE else View.VISIBLE
        binding.nbHeroLogo.visibility = View.GONE
        binding.nbBtnWatch.visibility = View.VISIBLE
        binding.nbBtnWatch.setOnClickListener { playHeroMovie(m) }
        binding.nbBtnGuide.text = "Details"
        binding.nbBtnGuide.setOnClickListener { openMovieDetails(m) }
        Glide.with(this).load(m.posterUrl).centerCrop()
            .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(500))
            .into(binding.nbHeroArt)
        buildHeroDots(index)
    }

    private fun buildHeroDots(selected: Int) {
        val dots = binding.nbHeroDots
        val d = resources.displayMetrics.density
        if (dots.childCount != heroMovies.size) {
            dots.removeAllViews()
            heroMovies.indices.forEach { _ ->
                dots.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt()).also { it.marginStart = (6 * d).toInt() }
                })
            }
        }
        for (i in 0 until dots.childCount) {
            val v = dots.getChildAt(i)
            val on = i == selected
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).also { it.width = ((if (on) 22 else 8) * d).toInt() }
            v.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 4 * d
                setColor(if (on) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
            }
        }
        dots.visibility = if (heroMovies.size > 1) View.VISIBLE else View.GONE
    }

    private fun playHeroMovie(m: HeroMovie) {
        val creds = PrefsManager.getCredentials(this) ?: return
        PlayerLauncher.launch(
            activity = this,
            streamUrl = xtream.buildVodUrl(creds.serverUrl, creds.username, creds.password, m.streamId, m.ext),
            title = m.name, streamId = m.streamId, isLive = false,
            favId = "movie_${m.streamId}", artUrl = m.posterUrl ?: ""
        )
    }

    private fun openMovieDetails(m: HeroMovie) {
        val creds = PrefsManager.getCredentials(this) ?: return
        startActivity(Intent(this, MovieDetailActivity::class.java).apply {
            putExtra(MovieDetailActivity.EXTRA_STREAM_ID, m.streamId)
            putExtra(MovieDetailActivity.EXTRA_STREAM_NAME, m.name)
            putExtra(MovieDetailActivity.EXTRA_STREAM_ICON, m.posterUrl ?: "")
            putExtra(MovieDetailActivity.EXTRA_CONTAINER_EXT, m.ext)
            putExtra(MovieDetailActivity.EXTRA_RATING, m.rating)
            putExtra(MovieDetailActivity.EXTRA_SERVER_URL, creds.serverUrl)
            putExtra(MovieDetailActivity.EXTRA_USERNAME, creds.username)
            putExtra(MovieDetailActivity.EXTRA_PASSWORD, creds.password)
        })
    }

    // ── What's on now, under each recent channel ─────────────────────────────

    private fun loadLiveNow(items: List<FavouriteItem>) {
        val adapter = liveAdapter ?: return
        val creds = PrefsManager.getCredentials(this) ?: return
        val gen = refreshGen
        items.forEachIndexed { idx, item ->
            lifecycleScope.launch {
                var listings = EpgCache.get(this@NebulaHomeActivity, item.streamId)
                if (listings == null) {
                    val res = withContext(Dispatchers.IO) {
                        xtream.getShortEpg(creds.serverUrl, creds.username, creds.password, item.streamId)
                    }
                    listings = res.getOrNull()?.listings
                    if (listings != null) EpgCache.put(this@NebulaHomeActivity, item.streamId, listings)
                }
                val now = System.currentTimeMillis() / 1000
                val cur = listings?.firstOrNull {
                    val s = it.startTimestamp?.toLongOrNull() ?: return@firstOrNull false
                    val e = it.stopTimestamp?.toLongOrNull() ?: return@firstOrNull false
                    now in s..e
                } ?: return@launch
                val title = try { cur.getDecodedTitle() } catch (_: Exception) { "" }
                if (title.isBlank() || gen != refreshGen) return@launch
                val s = cur.startTimestamp!!.toLong()
                val e = cur.stopTimestamp!!.toLong()
                val frac = if (e > s) ((now - s).toFloat() / (e - s)).coerceIn(0f, 1f) else 0f
                adapter.update(idx) { it.copy(subtitle = title, progress = frac) }
            }
        }
    }

    private fun confirmRemove(title: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle(title.uppercase())
            .setMessage("Remove from Continue Watching?")
            .setPositiveButton("REMOVE") { _, _ -> onConfirm() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private fun playLive(item: FavouriteItem) {
        RecentChannelsManager.record(this, item.title, item.streamId, item.streamUrl, item.artUrl.ifBlank { null })
        PlayerLauncher.launch(
            activity = this, streamUrl = item.streamUrl, title = item.title,
            streamId = item.streamId, isLive = true
        )
    }

    private fun playFavourite(item: FavouriteItem) {
        PlayerLauncher.launch(
            activity     = this,
            streamUrl    = item.streamUrl,
            title        = item.title,
            streamId     = item.streamId,
            isLive       = false,
            favId        = item.id,
            artUrl       = item.artUrl,
            resumeMs     = item.resumePositionMs,
            seriesId     = item.seriesId,
            season       = item.season,
            episodeNum   = item.episodeNum,
            episodeId    = item.episodeId,
            nextEpUrl    = item.nextEpisodeUrl,
            nextEpTitle  = item.nextEpisodeTitle,
            nextEpNum    = item.nextEpisodeNum,
            nextEpSeason = item.nextEpisodeSeason,
            nextEpId     = item.nextEpisodeId
        )
    }

    private fun playEmby(item: EmbyItem) {
        val s = EmbyPrefsManager.getSession(this) ?: return
        val displayName = if (item.type == "Episode")
            "${item.seriesName ?: ""} ${embyEpisodeLabel(item)}".trim() else item.name
        lifecycleScope.launch {
            val streamUrl = withContext(Dispatchers.IO) {
                val source = embyRepo.getPlaybackInfo(s.serverUrl, s.userId, s.token, item.id)
                    .getOrNull()?.mediaSources?.firstOrNull()
                embyRepo.buildStreamUrl(s.serverUrl, item.id, s.token, source)
            }
            startActivity(Intent(this@NebulaHomeActivity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL,   streamUrl)
                putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, displayName)
                putExtra(PlayerActivity.EXTRA_STREAM_ID,    -1)
                putExtra(PlayerActivity.EXTRA_IS_LIVE,      false)
                putExtra(PlayerActivity.EXTRA_RESUME_MS,    item.resumeMs())
                putExtra(PlayerActivity.EXTRA_EMBY_ITEM_ID, item.id)
            })
        }
    }

    // ── Card adapter ──────────────────────────────────────────────────────────

    private inner class CardAdapter(initial: List<Card>) : RecyclerView.Adapter<CardAdapter.VH>() {
        private val cards = initial.toMutableList()

        fun update(index: Int, change: (Card) -> Card) {
            if (index !in cards.indices) return
            cards[index] = change(cards[index])
            notifyItemChanged(index)
        }


        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val frame: View = v.findViewById(R.id.nb_card_frame)
            val img: ImageView = v.findViewById(R.id.nb_card_img)
            val progress: android.widget.ProgressBar = v.findViewById(R.id.nb_card_progress)
            val title: TextView = v.findViewById(R.id.nb_card_title)
            val sub: TextView = v.findViewById(R.id.nb_card_sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_nebula_card, parent, false))

        override fun getItemCount() = cards.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = cards[position]
            val d = h.itemView.resources.displayMetrics.density
            val w = ((if (c.landscape) 190 else 120) * d).toInt()
            val ht = ((if (c.landscape) 107 else 180) * d).toInt()
            h.frame.layoutParams = h.frame.layoutParams.apply { width = w; height = ht }
            h.title.layoutParams = h.title.layoutParams.apply { width = w }
            h.sub.layoutParams = h.sub.layoutParams.apply { width = w }
            h.title.text = c.title
            h.sub.text = c.subtitle

            if (c.landscape) {
                h.img.setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
                Glide.with(h.img).load(c.imageUrl).fitCenter()
                    .placeholder(R.drawable.ic_channel_placeholder).error(R.drawable.ic_channel_placeholder)
                    .into(h.img)
            } else {
                h.img.setPadding(0, 0, 0, 0)
                Glide.with(h.img).load(c.imageUrl)
                    .transform(CenterCrop(), RoundedCorners((9 * d).toInt()))
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .into(h.img)
            }

            if (c.progress > 0f) {
                h.progress.visibility = View.VISIBLE
                h.progress.progress = (c.progress * 1000).toInt()
            } else h.progress.visibility = View.GONE

            h.frame.setOnClickListener { c.onClick() }
            h.frame.setOnLongClickListener { c.onLongPress?.invoke(); c.onLongPress != null }
            h.frame.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(120).start()
            }
        }
    }
}
