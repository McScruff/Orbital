package com.skyretro.iptv.ui.games

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.skyretro.iptv.databinding.ActivityGamesBinding
import com.skyretro.iptv.ui.sports.SportsActivity
import com.skyretro.iptv.utils.TickerManager

class GamesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGamesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityGamesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) 0xFF2D6090.toInt() else 0xFF1A3560.toInt())
        }

        binding.tileSports.setOnClickListener {
            startActivity(Intent(this, SportsActivity::class.java))
        }
        binding.tileSports.setOnFocusChangeListener { _, hasFocus ->
            binding.tileSports.setBackgroundColor(if (hasFocus) 0xFF2D6090.toInt() else 0xFF1A3560.toInt())
        }

        binding.tileBeehive.setOnClickListener {
            startActivity(Intent(this, BeehiveBedlamActivity::class.java))
        }
        binding.tileBeehive.setOnFocusChangeListener { _, hasFocus ->
            binding.tileBeehive.setBackgroundColor(if (hasFocus) 0xFF2D6090.toInt() else 0xFF1A3560.toInt())
        }

        updateNewsTickerTile()
        binding.tileNewsTicker.setOnClickListener {
            TickerManager.newsTickerEnabled = !TickerManager.newsTickerEnabled
            updateNewsTickerTile()
        }
        binding.tileNewsTicker.setOnFocusChangeListener { _, hasFocus ->
            binding.tileNewsTicker.setBackgroundColor(if (hasFocus) 0xFF2D6090.toInt() else 0xFF1A3560.toInt())
        }
    }

    private fun updateNewsTickerTile() {
        val on = TickerManager.newsTickerEnabled
        binding.btnNewsTickerToggle.text = if (on) "ON" else "OFF"
        binding.btnNewsTickerToggle.setBackgroundColor(
            if (on) 0xFF00AA44.toInt() else 0xFFFFCC00.toInt()
        )
        binding.btnNewsTickerToggle.setTextColor(
            if (on) 0xFFFFFFFF.toInt() else 0xFF000080.toInt()
        )
        binding.tvNewsTickerStatus.text = if (on)
            "Active — headlines scrolling during playback"
        else
            "Show latest sports headlines while watching"
    }
}
