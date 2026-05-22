package com.skyretro.iptv.ui.vod

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.skyretro.iptv.data.model.FavType
import com.skyretro.iptv.data.model.FavouriteItem
import com.skyretro.iptv.data.model.MovieInfo
import com.skyretro.iptv.data.repository.XtreamRepository
import com.skyretro.iptv.databinding.ActivityMovieDetailBinding
import com.skyretro.iptv.utils.FavouritesManager
import com.skyretro.iptv.utils.PlayerLauncher
import com.skyretro.iptv.utils.PosterCache
import kotlinx.coroutines.*

class MovieDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_ID      = "detail_stream_id"
        const val EXTRA_STREAM_NAME    = "detail_stream_name"
        const val EXTRA_STREAM_ICON    = "detail_stream_icon"
        const val EXTRA_CONTAINER_EXT  = "detail_container_ext"
        const val EXTRA_RATING         = "detail_rating"
        const val EXTRA_SERVER_URL     = "detail_server_url"
        const val EXTRA_USERNAME       = "detail_username"
        const val EXTRA_PASSWORD       = "detail_password"
    }

    private lateinit var binding: ActivityMovieDetailBinding
    private val repository = XtreamRepository()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityMovieDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val streamId    = intent.getIntExtra(EXTRA_STREAM_ID, -1)
        val streamName  = intent.getStringExtra(EXTRA_STREAM_NAME) ?: ""
        val streamIcon  = intent.getStringExtra(EXTRA_STREAM_ICON) ?: ""
        val containerExt = intent.getStringExtra(EXTRA_CONTAINER_EXT) ?: "mp4"
        val rating      = intent.getStringExtra(EXTRA_RATING) ?: ""
        val serverUrl   = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""
        val username    = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password    = intent.getStringExtra(EXTRA_PASSWORD) ?: ""

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) 0xFF2D6090.toInt() else 0xFF1A3560.toInt())
        }

        binding.btnPlay.post { binding.btnPlay.requestFocus() }
        binding.tvTitle.text = streamName
        binding.tvRatingBadge.text = if (rating.isNotBlank() && rating != "0") "★ $rating" else ""

        val favId  = "movie_$streamId"
        val vodUrl = repository.buildVodUrl(serverUrl, username, password, streamId, containerExt)

        binding.btnPlay.setOnClickListener {
            PlayerLauncher.launch(
                activity  = this,
                streamUrl = vodUrl,
                title     = streamName,
                streamId  = streamId,
                isLive    = false,
                favId     = favId,
                artUrl    = streamIcon
            )
        }
        binding.btnPlay.setOnFocusChangeListener { _, hasFocus ->
            binding.btnPlay.setBackgroundColor(if (hasFocus) 0xFFE6B800.toInt() else 0xFFFFCC00.toInt())
        }

        updateFavButton(favId, streamName, streamIcon, vodUrl, streamId)
        binding.btnFav.setOnClickListener {
            val isFav = FavouritesManager.contains(this, favId)
            if (isFav) {
                FavouritesManager.remove(this, favId)
            } else {
                FavouritesManager.addOrUpdate(this, FavouriteItem(
                    id       = favId,
                    type     = FavType.MOVIE,
                    title    = binding.tvTitle.text.toString(),
                    artUrl   = streamIcon,
                    streamUrl = vodUrl,
                    streamId = streamId
                ))
            }
            updateFavButton(favId, streamName, streamIcon, vodUrl, streamId)
        }
        binding.btnFav.setOnFocusChangeListener { _, hasFocus ->
            val isFav = FavouritesManager.contains(this, favId)
            binding.btnFav.setBackgroundColor(when {
                hasFocus && isFav  -> 0xFF445522.toInt()
                hasFocus && !isFav -> 0xFF2D6090.toInt()
                isFav              -> 0xFF2D4A20.toInt()
                else               -> 0xFF1A3560.toInt()
            })
        }

        // Load poster (use cached if available)
        if (streamIcon.isNotEmpty()) {
            scope.launch {
                val bmp = PosterCache.getBitmap(this@MovieDetailActivity, streamId, streamIcon)
                binding.posterLoading.visibility = View.GONE
                if (bmp != null) {
                    binding.ivPoster.setImageBitmap(bmp)
                }
            }
        } else {
            binding.posterLoading.visibility = View.GONE
        }

        // Fetch detailed info
        binding.infoLoading.visibility = View.VISIBLE
        scope.launch {
            val result = repository.getVodInfo(serverUrl, username, password, streamId)
            binding.infoLoading.visibility = View.GONE
            result.onSuccess { info ->
                info.info?.let { populateInfo(it, streamId) }
                // Upgrade poster to cover_big if available and not yet cached
                val bigCover = info.info?.coverBig ?: info.info?.movieImage
                if (!bigCover.isNullOrEmpty() && bigCover != streamIcon) {
                    val bmp = PosterCache.getBitmap(this@MovieDetailActivity, streamId, bigCover)
                    if (bmp != null) binding.ivPoster.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun updateFavButton(favId: String, name: String, icon: String, url: String, id: Int) {
        val isFav = FavouritesManager.contains(this, favId)
        binding.btnFav.text = if (isFav) "★  FAVOURITED — TAP TO REMOVE" else "☆  ADD TO FAVOURITES"
        binding.btnFav.setTextColor(if (isFav) 0xFFFFCC00.toInt() else 0xFFFFFFFF.toInt())
        binding.btnFav.setBackgroundColor(if (isFav) 0xFF2D4A20.toInt() else 0xFF1A3560.toInt())
    }

    private fun populateInfo(info: MovieInfo, streamId: Int) {
        // Meta line: year · genre · country · duration
        val meta = listOfNotNull(
            info.releaseDate?.take(4)?.takeIf { it.isNotBlank() },
            info.genre?.takeIf { it.isNotBlank() },
            info.country?.takeIf { it.isNotBlank() },
            info.duration?.takeIf { it.isNotBlank() }
        ).joinToString("  ·  ")
        if (meta.isNotBlank()) binding.tvMeta.text = meta

        val cast = (info.actors ?: info.cast)?.takeIf { it.isNotBlank() }
        val director = info.director?.takeIf { it.isNotBlank() }
        val plot = (info.plot ?: info.description)?.takeIf { it.isNotBlank() }

        if (director != null) {
            binding.tvDirectorLabel.visibility = View.VISIBLE
            binding.tvDirector.text = director
            binding.tvDirector.visibility = View.VISIBLE
        }
        if (cast != null) {
            binding.tvCastLabel.visibility = View.VISIBLE
            binding.tvCast.text = cast
            binding.tvCast.visibility = View.VISIBLE
        }
        if (plot != null) {
            binding.tvPlotLabel.visibility = View.VISIBLE
            binding.tvPlot.text = plot
            binding.tvPlot.visibility = View.VISIBLE
        }

        // Update title and rating if richer info is available
        val richName = info.name ?: info.oName
        if (!richName.isNullOrBlank()) binding.tvTitle.text = richName
        val richRating = info.rating?.takeIf { it.isNotBlank() && it != "0" }
        if (richRating != null) binding.tvRatingBadge.text = "★ $richRating"
    }

    // Fires after the window is fully interactive — no user input can arrive before this,
    // so btn_play is guaranteed to have focus before the user can press anything.
    private var windowFocused = false
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !windowFocused) {
            windowFocused = true
            binding.btnPlay.requestFocus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
