package com.skyretro.iptv.ui.games

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.skyretro.iptv.databinding.ActivityBeehiveBedlamBinding

class BeehiveBedlamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBeehiveBedlamBinding
    private lateinit var prefs: SharedPreferences
    private var bestScore = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityBeehiveBedlamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("beehive_prefs", MODE_PRIVATE)
        bestScore = prefs.getInt("best_score", 0)
        binding.tvBest.text = "BEST: $bestScore"

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNewGame.setOnClickListener { newGame() }

        binding.beehiveView.onScoreChanged = { score, level ->
            binding.tvScore.text = score.toString()
            binding.tvLevel.text = level.toString()
            if (score > bestScore) {
                bestScore = score
                prefs.edit().putInt("best_score", bestScore).apply()
                binding.tvBest.text = "BEST: $bestScore"
            }
        }

        binding.beehiveView.onMessage = { msg ->
            binding.tvMessage.text = msg
        }

        binding.beehiveView.requestFocus()
    }

    private fun newGame() {
        binding.beehiveView.reset()
        binding.tvScore.text = "0"
        binding.tvLevel.text = "1"
    }
}
