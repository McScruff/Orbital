package com.orbital.iptv.ui.games

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.orbital.iptv.databinding.ActivityGamesBinding
import com.orbital.iptv.ui.emby.EmbyBrowserActivity
import com.orbital.iptv.ui.emby.EmbyLoginActivity
import com.orbital.iptv.ui.plex.PlexBrowserActivity
import com.orbital.iptv.ui.plex.PlexLoginActivity
import com.orbital.iptv.ui.sports.SportsActivity
import com.orbital.iptv.utils.EmbyPrefsManager
import com.orbital.iptv.utils.PlexPrefsManager
import com.orbital.iptv.utils.TickerManager
import com.orbital.iptv.utils.ThemeManager

class GamesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGamesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityGamesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.load(this)
        val p = ThemeManager.palette()
        val density = resources.displayMetrics.density
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.layoutHeader?.setBackgroundColor(p.bgHeader)
        binding.viewAccent?.setBackgroundColor(p.accent)

        fun tileBg(focused: Boolean, focusColor: Int = p.focus) =
            ThemeManager.roundedBg(if (focused) focusColor else p.bgMid, density)

        binding.btnBack.background = tileBg(false)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, h -> binding.btnBack.background = tileBg(h) }

        binding.tileSports.background = tileBg(false)
        binding.tileSports.setOnClickListener { startActivity(Intent(this, SportsActivity::class.java)) }
        binding.tileSports.setOnFocusChangeListener { _, h -> binding.tileSports.background = tileBg(h) }

        binding.tileTeletext.background = tileBg(false)
        binding.tileTeletext.setOnClickListener { startActivity(Intent(this, TeletextActivity::class.java)) }
        binding.tileTeletext.setOnFocusChangeListener { _, h -> binding.tileTeletext.background = tileBg(h) }

        binding.tileEmby.background = tileBg(false)
        binding.tileEmby.setOnClickListener {
            val dest = if (EmbyPrefsManager.getSession(this) != null) EmbyBrowserActivity::class.java
                       else EmbyLoginActivity::class.java
            startActivity(Intent(this, dest))
        }
        binding.tileEmby.setOnFocusChangeListener { _, h -> binding.tileEmby.background = tileBg(h) }

        binding.tilePlex.background = tileBg(false)
        binding.tilePlex.setOnClickListener {
            val dest = if (PlexPrefsManager.getSession(this) != null) PlexBrowserActivity::class.java
                       else PlexLoginActivity::class.java
            startActivity(Intent(this, dest))
        }
        binding.tilePlex.setOnFocusChangeListener { _, h ->
            binding.tilePlex.background = tileBg(h, 0xFF8B6914.toInt())
        }

        binding.tileBubble.background = tileBg(false)
        binding.tileBubble.setOnClickListener { startActivity(Intent(this, BubbleShooterActivity::class.java)) }
        binding.tileBubble.setOnFocusChangeListener { _, h -> binding.tileBubble.background = tileBg(h) }

        updateNewsTickerTile()
        binding.tileNewsTicker.background = tileBg(false)
        binding.tileNewsTicker.setOnClickListener { showSportPicker() }
        binding.btnNewsTickerToggle.setOnClickListener {
            TickerManager.newsTickerEnabled = !TickerManager.newsTickerEnabled
            updateNewsTickerTile()
        }
        binding.tileNewsTicker.setOnFocusChangeListener { _, h ->
            binding.tileNewsTicker.background = tileBg(h)
        }
    }

    private fun showSportPicker() {
        val feeds = TickerManager.SPORT_FEEDS
        val selectedIds = TickerManager.getSelectedSportIds(this).toMutableSet()
        val checked = feeds.map { it.id in selectedIds }.toBooleanArray()
        val labels = feeds.map { "${it.emoji} ${it.name}" }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this, com.orbital.iptv.R.style.Theme_Orbital_Dialog)
            .setTitle("SELECT SPORTS FOR NEWS TICKER")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selectedIds.add(feeds[which].id)
                else selectedIds.remove(feeds[which].id)
            }
            .setPositiveButton("DONE") { _, _ ->
                TickerManager.setSelectedSportIds(this, selectedIds)
                if (selectedIds.isNotEmpty()) TickerManager.newsTickerEnabled = true
                TickerManager.sportHeadlines.clear()
                updateNewsTickerTile()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun updateNewsTickerTile() {
        val on = TickerManager.newsTickerEnabled
        val count = TickerManager.getSelectedSportIds(this).size
        val selectedFeeds = TickerManager.getSelectedSports(this)

        binding.btnNewsTickerToggle.text = if (on) "ON" else "OFF"
        binding.btnNewsTickerToggle.setBackgroundColor(
            if (on) 0xFF00AA44.toInt() else 0xFFFFCC00.toInt()
        )
        binding.btnNewsTickerToggle.setTextColor(
            if (on) 0xFFFFFFFF.toInt() else 0xFF000080.toInt()
        )

        binding.tvNewsTickerStatus.text = when {
            count == 0  -> "No sports selected — tap to choose"
            !on         -> "${selectedFeeds.joinToString(", ") { it.emoji + " " + it.name }} — ticker OFF"
            else        -> "${selectedFeeds.joinToString(", ") { it.emoji + " " + it.name }} — scrolling during playback"
        }
    }
}
