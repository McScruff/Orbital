package com.orbital.iptv.ui.nebula

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.orbital.iptv.R
import com.orbital.iptv.databinding.ActivityNebulaMoreBinding
import com.orbital.iptv.recording.RecordingsActivity
import com.orbital.iptv.ui.catchup.CatchupActivity
import com.orbital.iptv.ui.games.BubbleShooterActivity
import com.orbital.iptv.ui.games.TeletextActivity
import com.orbital.iptv.ui.home.HomeActivity
import com.orbital.iptv.ui.radio.RadioActivity
import com.orbital.iptv.utils.ThemeManager
import com.orbital.iptv.utils.TickerManager

/** NEBULA "More" hub: Radio, Catch-up, Recordings, Teletext, Bubble Shooter and the news ticker options. */
class NebulaMoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNebulaMoreBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ThemeManager.load(this)
        if (ThemeManager.current != ThemeManager.AppTheme.NEBULA) {
            startActivity(Intent(this, HomeActivity::class.java)); finish(); return
        }
        binding = ActivityNebulaMoreBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NebulaRail.bind(
            this, binding.rail, NebulaRail.Item.MORE,
            onHome = { finish() },
            onLive = { startActivity(Intent(this, NebulaLiveActivity::class.java)) }
        )
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        NebulaRail.refresh(this, binding.rail)
        build()
    }

    private fun build() {
        val list = binding.nbMoreList
        list.removeAllViews()
        section(list, "WATCH & LISTEN")
        tile(list, R.drawable.ic_nb_radio, "Radio", "Live radio stations") { startActivity(Intent(this, RadioActivity::class.java)) }
        tile(list, R.drawable.ic_nb_history, "Catch-up", "Replay programmes from the last few days") { startActivity(Intent(this, CatchupActivity::class.java)) }
        tile(list, R.drawable.ic_nb_record, "Recordings", "Scheduled and completed recordings") { startActivity(Intent(this, RecordingsActivity::class.java)) }

        section(list, "INTERACTIVE")
        tile(list, R.drawable.ic_nb_teletext, "Teletext", "Headlines, scores and pages") { startActivity(Intent(this, TeletextActivity::class.java)) }
        tile(list, R.drawable.ic_nb_game, "Bubble Shooter", "A quick game") { startActivity(Intent(this, BubbleShooterActivity::class.java)) }

        section(list, "NEWS TICKER")
        val on = TickerManager.newsTickerEnabled
        tile(list, R.drawable.ic_nb_news, "News ticker", if (on) "On" else "Off") {
            TickerManager.newsTickerEnabled = !TickerManager.newsTickerEnabled
            TickerManager.sportHeadlines.clear()
            build()
        }
        tile(list, R.drawable.ic_nb_news, "Ticker sports", "Choose which sports feed the ticker") { showTickerSportPicker() }
    }

    private fun section(list: LinearLayout, title: String) {
        val d = resources.displayMetrics.density
        list.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF7C6CFF.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setPadding((4 * d).toInt(), (18 * d).toInt(), 0, (6 * d).toInt())
        })
    }

    private fun tile(list: LinearLayout, icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (60 * d).toInt())
                .also { it.setMargins(0, (3 * d).toInt(), 0, (3 * d).toInt()) }
            setBackgroundResource(R.drawable.bg_nb_row)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF22D3EE.toInt())
            layoutParams = LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()).also { it.marginEnd = (16 * d).toInt() }
        })
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(this).apply {
            this.text = title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        text.addView(TextView(this).apply {
            this.text = subtitle
            setTextColor(0xFF8A87B0.toInt())
            textSize = 12f
        })
        row.addView(text)
        list.addView(row)
    }

    private fun showTickerSportPicker() {
        val feeds = TickerManager.SPORT_FEEDS
        val selectedIds = TickerManager.getSelectedSportIds(this).toMutableSet()
        val checked = feeds.map { it.id in selectedIds }.toBooleanArray()
        val labels = feeds.map { "${it.emoji} ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle("SELECT SPORTS FOR NEWS TICKER")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selectedIds.add(feeds[which].id) else selectedIds.remove(feeds[which].id)
            }
            .setPositiveButton("DONE") { _, _ ->
                TickerManager.setSelectedSportIds(this, selectedIds)
                if (selectedIds.isNotEmpty()) TickerManager.newsTickerEnabled = true
                TickerManager.sportHeadlines.clear()
                build()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
