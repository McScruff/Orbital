package com.orbital.iptv.ui.games

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.orbital.iptv.databinding.ActivityBubbleShooterBinding

class BubbleShooterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBubbleShooterBinding
    private lateinit var prefs: SharedPreferences
    private var bestScore = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityBubbleShooterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE)
        bestScore = prefs.getInt("best_score", 0)
        binding.tvBest.text = "BEST: $bestScore"

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNewGame.setOnClickListener { newGame() }

        binding.bubbleView.onScoreChanged = { score, level ->
            binding.tvScore.text = score.toString()
            binding.tvLevel.text = level.toString()
            if (score > bestScore) {
                bestScore = score
                prefs.edit().putInt("best_score", bestScore).apply()
                binding.tvBest.text = "BEST: $bestScore"
            }
        }

        binding.bubbleView.onMessage = { msg ->
            binding.tvMessage.text = msg
        }

        binding.bubbleView.requestFocus()
    }

    private fun newGame() {
        binding.bubbleView.reset()
        binding.tvScore.text = "0"
        binding.tvLevel.text = "1"
    }
}
