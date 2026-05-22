package com.skyretro.iptv.ui.games

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.skyretro.iptv.databinding.ActivityGamesBinding
import com.skyretro.iptv.ui.sports.SportsActivity

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
    }
}
