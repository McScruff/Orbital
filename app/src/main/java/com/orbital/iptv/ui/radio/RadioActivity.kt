package com.orbital.iptv.ui.radio

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.orbital.iptv.R
import com.orbital.iptv.databinding.ActivityRadioBinding
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.utils.ThemeManager

class RadioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRadioBinding
    private lateinit var adapter: RadioAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityRadioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.layoutHeader?.setBackgroundColor(p.bgHeader)
        binding.viewAccent?.setBackgroundColor(p.accent)
        binding.tvStationCount?.setTextColor(p.highlight)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setBackgroundColor(p.bgHeader)
        binding.btnBack.setOnFocusChangeListener { _, h ->
            binding.btnBack.setBackgroundColor(if (h) p.focus else p.bgHeader)
        }

        adapter = RadioAdapter { station -> onStationSelected(station) }
        binding.rvStations.apply {
            adapter = this@RadioActivity.adapter
            layoutManager = LinearLayoutManager(this@RadioActivity)
            itemAnimator = null
        }

        adapter.items = RadioStations.load(this)
        binding.tvStationCount.text = "${adapter.items.size} STATIONS"
    }

    private fun onStationSelected(station: RadioStation) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, station.url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, station.name)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, true as Boolean)
        })
    }
}
