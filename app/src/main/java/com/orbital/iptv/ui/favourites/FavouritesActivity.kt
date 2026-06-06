package com.orbital.iptv.ui.favourites

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.orbital.iptv.R
import com.orbital.iptv.data.model.FavType
import com.orbital.iptv.data.model.FavouriteItem
import com.orbital.iptv.databinding.ActivityFavouritesBinding
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PlayerLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.orbital.iptv.utils.ThemeManager

class FavouritesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFavouritesBinding
    private lateinit var continueAdapter: FavouritesAdapter
    private lateinit var favsAdapter: FavouritesAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityFavouritesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.layoutHeader?.setBackgroundColor(p.bgHeader)
        binding.viewAccent?.setBackgroundColor(p.accent)
        binding.headerContinue?.setBackgroundColor(p.bgMid)
        binding.headerFavourites?.setBackgroundColor(p.bgMid)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) 0xFF2D6090.toInt() else 0xFF1E3D72.toInt())
        }

        continueAdapter = FavouritesAdapter(scope,
            onClick = ::onItemClick,
            onLongPress = ::onItemLongPress
        )
        favsAdapter = FavouritesAdapter(scope,
            onClick = ::onItemClick,
            onLongPress = ::onItemLongPress
        )

        binding.rvContinue.apply {
            adapter = continueAdapter
            layoutManager = LinearLayoutManager(this@FavouritesActivity)
            itemAnimator = null
        }
        binding.rvFavourites.apply {
            adapter = favsAdapter
            layoutManager = LinearLayoutManager(this@FavouritesActivity)
            itemAnimator = null
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val all = FavouritesManager.getAll(this)
        // Episodes always go to Continue Watching (queued next episode or in-progress)
        // Movies only appear there if they have an active resume position
        val resumeItems = all.filter { it.type == FavType.EPISODE || it.hasResume }
        val savedItems  = all.filter { it.type == FavType.MOVIE && !it.hasResume }

        if (resumeItems.isNotEmpty()) {
            binding.headerContinue.visibility = View.VISIBLE
            binding.rvContinue.visibility = View.VISIBLE
            binding.dividerSections.visibility = if (savedItems.isNotEmpty()) View.VISIBLE else View.GONE
            continueAdapter.submitList(resumeItems)
        } else {
            binding.headerContinue.visibility = View.GONE
            binding.rvContinue.visibility = View.GONE
            binding.dividerSections.visibility = View.GONE
        }

        if (savedItems.isNotEmpty()) {
            binding.headerFavourites.visibility = View.VISIBLE
            binding.rvFavourites.visibility = View.VISIBLE
            favsAdapter.submitList(savedItems)
        } else {
            binding.headerFavourites.visibility = View.GONE
            binding.rvFavourites.visibility = View.GONE
        }

        if (all.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
        } else {
            binding.tvEmpty.visibility = View.GONE
        }

        val total = all.size
        binding.tvCount.text = "$total ${if (total == 1) "ITEM" else "ITEMS"}"
    }

    private fun onItemClick(item: FavouriteItem) {
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

    private fun onItemLongPress(item: FavouriteItem) {
        if (item.type == FavType.MOVIE) {
            showMovieLongPressMenu(item)
        } else {
            showEpisodeLongPressMenu(item)
        }
    }

    private fun showMovieLongPressMenu(item: FavouriteItem) {
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle(item.title.uppercase())
            .setItems(arrayOf("REMOVE FROM FAVOURITES", "CANCEL")) { _, which ->
                if (which == 0) {
                    FavouritesManager.remove(this, item.id)
                    refresh()
                }
            }.show()
    }

    private fun showEpisodeLongPressMenu(item: FavouriteItem) {
        val options = mutableListOf("REMOVE EPISODE", "REMOVE SHOW FROM FAVOURITES", "CANCEL")
        if (item.hasNextEpisode) options.add(1, "MARK AS PLAYED — ADD NEXT EPISODE")

        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle(item.title.uppercase())
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "REMOVE EPISODE" -> {
                        FavouritesManager.remove(this, item.id)
                        refresh()
                    }
                    "REMOVE SHOW FROM FAVOURITES" -> {
                        if (item.seriesId >= 0) FavouritesManager.removeBySeriesId(this, item.seriesId)
                        else FavouritesManager.remove(this, item.id)
                        refresh()
                    }
                    "MARK AS PLAYED — ADD NEXT EPISODE" -> {
                        if (item.seriesId >= 0) FavouritesManager.removeBySeriesId(this, item.seriesId)
                        else FavouritesManager.remove(this, item.id)
                        val nextId = "ep_${item.seriesId}_${item.nextEpisodeSeason}_${item.nextEpisodeNum}"
                        FavouritesManager.addOrUpdate(this, FavouriteItem(
                            id              = nextId,
                            type            = FavType.EPISODE,
                            title           = item.nextEpisodeTitle,
                            artUrl          = item.artUrl,
                            streamUrl       = item.nextEpisodeUrl,
                            streamId        = 0,
                            seriesId        = item.seriesId,
                            season          = item.nextEpisodeSeason,
                            episodeNum      = item.nextEpisodeNum,
                            episodeId       = item.nextEpisodeId
                        ))
                        refresh()
                    }
                }
            }.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
