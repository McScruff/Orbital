package com.orbital.iptv.ui.series

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.orbital.iptv.R
import com.orbital.iptv.data.model.Episode
import com.orbital.iptv.data.model.FavType
import com.orbital.iptv.data.model.FavouriteItem
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.databinding.ActivitySeriesDetailBinding
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PlayerLauncher
import com.orbital.iptv.utils.PosterCache
import com.orbital.iptv.utils.SubtitlePicker
import com.orbital.iptv.utils.ThemeManager
import kotlinx.coroutines.*

class SeriesDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERIES_ID    = "series_id"
        const val EXTRA_SERIES_NAME  = "series_name"
        const val EXTRA_SERIES_COVER = "series_cover"
        const val EXTRA_RATING       = "series_rating"
        const val EXTRA_SERVER_URL   = "series_server_url"
        const val EXTRA_USERNAME     = "series_username"
        const val EXTRA_PASSWORD     = "series_password"
    }

    private lateinit var binding: ActivitySeriesDetailBinding
    private lateinit var episodeAdapter: EpisodeAdapter
    private val repository = XtreamRepository()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var allEpisodes: Map<String, List<Episode>> = emptyMap()
    private var selectedSeason: String = "1"
    private var seriesId: Int = -1
    private var seriesCoverUrl: String = ""

    private lateinit var serverUrl: String
    private lateinit var username: String
    private lateinit var password: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        seriesId        = intent.getIntExtra(EXTRA_SERIES_ID, -1)
        val seriesName  = intent.getStringExtra(EXTRA_SERIES_NAME) ?: ""
        seriesCoverUrl  = intent.getStringExtra(EXTRA_SERIES_COVER) ?: ""
        val rating      = intent.getStringExtra(EXTRA_RATING) ?: ""
        serverUrl       = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""
        username        = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        password        = intent.getStringExtra(EXTRA_PASSWORD) ?: ""

        ThemeManager.load(this)
        val themeP = ThemeManager.palette()
        binding.root.setBackgroundColor(themeP.bgPrimary)

        binding.btnBack.setBackgroundColor(themeP.bgHeader)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) ThemeManager.palette().focus else ThemeManager.palette().bgHeader)
        }
        binding.tvTitle.text = seriesName
        binding.tvRatingBadge.text = if (rating.isNotBlank() && rating != "0") "★ $rating" else ""

        episodeAdapter = EpisodeAdapter(
            onPlay      = { ep -> onPlayEpisode(ep) },
            onLongPress = { ep -> onEpisodeLongPress(ep) }
        )
        binding.rvEpisodes.apply {
            adapter = episodeAdapter
            layoutManager = LinearLayoutManager(this@SeriesDetailActivity)
            addItemDecoration(DividerItemDecoration(this@SeriesDetailActivity, DividerItemDecoration.VERTICAL))
        }

        if (seriesCoverUrl.isNotEmpty()) {
            scope.launch {
                val bmp = PosterCache.getBitmap(this@SeriesDetailActivity, seriesId, seriesCoverUrl)
                binding.posterLoading.visibility = View.GONE
                if (bmp != null) binding.ivPoster.setImageBitmap(bmp)
            }
        } else {
            binding.posterLoading.visibility = View.GONE
        }

        loadSeriesInfo(seriesId, seriesName)
    }

    private fun loadSeriesInfo(seriesId: Int, seriesName: String) {
        binding.progressBar.visibility = View.VISIBLE
        scope.launch {
            val result = repository.getSeriesInfo(serverUrl, username, password, seriesId)
            binding.progressBar.visibility = View.GONE
            result.onSuccess { info ->
                // Populate show meta
                val detail = info.info
                if (detail != null) {
                    val meta = listOfNotNull(
                        detail.releaseDate?.take(4)?.takeIf { it.isNotBlank() },
                        detail.genre?.takeIf { it.isNotBlank() }
                    ).joinToString("  ·  ")
                    if (meta.isNotBlank()) binding.tvMeta.text = meta
                    binding.tvPlot.text = detail.plot?.takeIf { it.isNotBlank() } ?: ""

                    val richRating = detail.rating?.takeIf { it.isNotBlank() && it != "0" }
                    if (richRating != null) binding.tvRatingBadge.text = "★ $richRating"

                    val richName = detail.name?.takeIf { it.isNotBlank() }
                    if (!richName.isNullOrBlank()) binding.tvTitle.text = richName

                    // Upgrade poster if cover_big available
                    val bigCover = detail.cover
                    if (!bigCover.isNullOrEmpty()) {
                        val bmp = PosterCache.getBitmap(this@SeriesDetailActivity, seriesId, bigCover)
                        if (bmp != null) {
                            binding.ivPoster.setImageBitmap(bmp)
                            binding.ivPoster.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    }
                }

                allEpisodes = info.episodes ?: emptyMap()
                if (allEpisodes.isNotEmpty()) {
                    buildSeasonTabs(allEpisodes.keys.sortedBy { it.toIntOrNull() ?: 0 })
                }
            }
            result.onFailure { e ->
                binding.tvError.text = e.message?.uppercase() ?: "ERROR LOADING SERIES"
                binding.tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun buildSeasonTabs(seasons: List<String>) {
        val container = binding.seasonTabContainer
        container.removeAllViews()

        val dp = resources.displayMetrics.density
        val padH = (16 * dp).toInt()
        val padV = (6 * dp).toInt()

        seasons.forEach { season ->
            val tab = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).also { it.marginEnd = (4 * dp).toInt() }
                gravity = Gravity.CENTER
                text = "S$season"
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
            }
            container.addView(tab)
            tab.setOnClickListener { selectSeason(season) }
            tab.setOnFocusChangeListener { _, hasFocus ->
                val p = ThemeManager.palette()
                val isSelected = selectedSeason == season
                tab.setBackgroundColor(when {
                    hasFocus && !isSelected -> p.focus
                    isSelected              -> p.highlight
                    else                    -> p.bgMid
                })
                tab.setTextColor(if (isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }

        // Select first season by default
        seasons.firstOrNull()?.let { selectSeason(it) }
    }

    private fun selectSeason(season: String) {
        selectedSeason = season
        val dp = resources.displayMetrics.density
        val padH = (16 * dp).toInt()
        val padV = (6 * dp).toInt()

        val p = ThemeManager.palette()
        val container = binding.seasonTabContainer
        for (i in 0 until container.childCount) {
            val tab = container.getChildAt(i) as? TextView ?: continue
            val isSelected = tab.text == "S$season"
            tab.setBackgroundColor(if (isSelected) p.highlight else p.bgMid)
            tab.setTextColor(if (isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }

        val episodes = allEpisodes[season] ?: emptyList()
        val sorted = episodes.sortedBy { it.episodeNum ?: 0 }
        episodeAdapter.submitList(sorted)
        // postDelayed gives RecyclerView time to bind ViewHolders before we query position 0.
        binding.rvEpisodes.postDelayed({
            val first = binding.rvEpisodes.findViewHolderForAdapterPosition(0)?.itemView
            first?.requestFocus() ?: binding.rvEpisodes.requestFocus()
        }, 150)
    }

    private fun resolveNextEpisode(ep: Episode): Episode? {
        // ep.season is null on many providers — fall back to scanning all buckets by episode ID
        val epSeasonKey = ep.season?.toString()
            ?: allEpisodes.entries.firstOrNull { (_, eps) -> eps.any { it.id == ep.id } }?.key
            ?: ""
        val seasonEps = (allEpisodes[epSeasonKey] ?: emptyList()).sortedBy { it.episodeNum ?: 0 }
        val idx = seasonEps.indexOfFirst { it.id == ep.id }
        if (idx >= 0 && idx + 1 < seasonEps.size) return seasonEps[idx + 1]
        val seasons: List<String> = allEpisodes.keys.toList().sortedBy { it.toIntOrNull() ?: 0 }
        val sIdx = seasons.indexOf(epSeasonKey)
        if (sIdx >= 0 && sIdx + 1 < seasons.size) {
            return allEpisodes[seasons[sIdx + 1]]?.sortedBy { it.episodeNum ?: 0 }?.firstOrNull()
        }
        return null
    }

    private fun resolvedSeasonKey(ep: Episode): String =
        ep.season?.toString()
            ?: allEpisodes.entries.firstOrNull { (_, eps) -> eps.any { it.id == ep.id } }?.key
            ?: ""

    private fun episodeDisplayTitle(ep: Episode): String =
        ep.title?.takeIf { it.isNotBlank() } ?: "S${ep.season}E%02d".format(ep.episodeNum ?: 0)

    private fun onEpisodeLongPress(ep: Episode) {
        val favId = "ep_${seriesId}_${ep.season}_${ep.episodeNum}"
        val isFav = FavouritesManager.contains(this, favId)
        val options = if (isFav)
            arrayOf("▶ PLAY", "CC PLAY WITH SUBTITLES", "REMOVE FROM FAVOURITES", "CANCEL")
        else
            arrayOf("▶ PLAY", "CC PLAY WITH SUBTITLES", "★ ADD TO FAVOURITES", "CANCEL")

        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("${binding.tvTitle.text} — ${episodeDisplayTitle(ep)}")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "▶ PLAY"                  -> onPlayEpisode(ep)
                    "CC PLAY WITH SUBTITLES"  -> onPlayEpisodeWithSubtitle(ep)
                    "★ ADD TO FAVOURITES"     -> addEpisodeToFavourites(ep)
                    "REMOVE FROM FAVOURITES"  -> FavouritesManager.remove(this, favId)
                }
            }.show()
    }

    private fun addEpisodeToFavourites(ep: Episode) {
        val ext  = ep.containerExtension ?: "mp4"
        val url  = repository.buildSeriesEpisodeUrl(serverUrl, username, password, ep.id, ext)
        val next = resolveNextEpisode(ep)
        val nextUrl = next?.let {
            repository.buildSeriesEpisodeUrl(serverUrl, username, password, it.id, it.containerExtension ?: "mp4")
        } ?: ""
        val nextTitle = next?.let { "${binding.tvTitle.text} — ${episodeDisplayTitle(it)}" } ?: ""
        val epSeasonKey   = resolvedSeasonKey(ep)
        val nextSeasonKey = next?.let { resolvedSeasonKey(it) } ?: ""
        val favId = "ep_${seriesId}_${epSeasonKey}_${ep.episodeNum}"

        FavouritesManager.removeBySeriesId(this, seriesId)
        FavouritesManager.addOrUpdate(this, FavouriteItem(
            id               = favId,
            type             = FavType.EPISODE,
            title            = "${binding.tvTitle.text} — ${episodeDisplayTitle(ep)}",
            artUrl           = seriesCoverUrl,
            streamUrl        = url,
            streamId         = ep.id.toIntOrNull() ?: 0,
            seriesId         = seriesId,
            season           = epSeasonKey,
            episodeNum       = ep.episodeNum ?: 0,
            episodeId        = ep.id,
            nextEpisodeUrl   = nextUrl,
            nextEpisodeTitle = nextTitle,
            nextEpisodeSeason = nextSeasonKey,
            nextEpisodeNum   = next?.episodeNum ?: 0,
            nextEpisodeId    = next?.id ?: ""
        ))
        android.widget.Toast.makeText(this, "ADDED TO CONTINUE WATCHING", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun onPlayEpisodeWithSubtitle(ep: Episode) {
        val season  = ep.season ?: 1
        val epNum   = ep.episodeNum ?: 1
        SubtitlePicker.pickForEpisode(
            activity    = this,
            seriesTitle = binding.tvTitle.text.toString(),
            season      = season,
            episode     = epNum,
            current     = null
        ) { file ->
            onPlayEpisode(ep, subtitlePath = file?.absolutePath ?: "")
        }
    }

    private fun onPlayEpisode(ep: Episode, subtitlePath: String = "") {
        val ext   = ep.containerExtension ?: "mp4"
        val url   = repository.buildSeriesEpisodeUrl(serverUrl, username, password, ep.id, ext)
        val title = "${binding.tvTitle.text} — ${episodeDisplayTitle(ep)}"
        val next  = resolveNextEpisode(ep)
        val nextUrl   = next?.let { repository.buildSeriesEpisodeUrl(serverUrl, username, password, it.id, it.containerExtension ?: "mp4") } ?: ""
        val nextTitle = next?.let { "${binding.tvTitle.text} — ${episodeDisplayTitle(it)}" } ?: ""
        val epSeasonKey   = resolvedSeasonKey(ep)
        val nextSeasonKey = next?.let { resolvedSeasonKey(it) } ?: ""
        val favId = "ep_${seriesId}_${epSeasonKey}_${ep.episodeNum}"

        PlayerLauncher.launch(
            activity     = this,
            streamUrl    = url,
            title        = title,
            streamId     = ep.id.toIntOrNull() ?: 0,
            isLive       = false,
            favId        = favId,
            artUrl       = seriesCoverUrl,
            seriesId     = seriesId,
            season       = epSeasonKey,
            episodeNum   = ep.episodeNum ?: 0,
            episodeId    = ep.id,
            nextEpUrl    = nextUrl,
            nextEpTitle  = nextTitle,
            nextEpNum    = next?.episodeNum ?: 0,
            nextEpSeason = nextSeasonKey,
            nextEpId     = next?.id ?: "",
            subtitlePath = subtitlePath
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
