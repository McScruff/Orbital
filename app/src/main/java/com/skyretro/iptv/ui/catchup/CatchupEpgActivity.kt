package com.skyretro.iptv.ui.catchup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.skyretro.iptv.data.model.EpgListing
import com.skyretro.iptv.data.model.getDecodedTitle
import com.skyretro.iptv.data.repository.XtreamRepository
import com.skyretro.iptv.databinding.ActivityCatchupEpgBinding
import com.skyretro.iptv.utils.ThemeManager
import com.skyretro.iptv.ui.player.PlayerActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CatchupEpgActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_ID     = "catchup_stream_id"
        const val EXTRA_CHANNEL_NAME  = "catchup_channel_name"
        const val EXTRA_ARCHIVE_DAYS  = "catchup_archive_days"
        const val EXTRA_SERVER_URL    = "catchup_server_url"
        const val EXTRA_USERNAME      = "catchup_username"
        const val EXTRA_PASSWORD      = "catchup_password"
    }

    private lateinit var binding: ActivityCatchupEpgBinding
    private lateinit var adapter: CatchupEpgAdapter
    private val repository = XtreamRepository()

    private var streamId   = 0
    private var archiveDays = 3
    private var serverUrl  = ""
    private var username   = ""
    private var password   = ""

    private var allListings: List<EpgListing> = emptyList()
    private var selectedDayOffset = 0  // 0 = today, 1 = yesterday, etc.
    private val dayTabs = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityCatchupEpgBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamId    = intent.getIntExtra(EXTRA_STREAM_ID, 0)
        val name    = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        archiveDays = intent.getIntExtra(EXTRA_ARCHIVE_DAYS, 3).coerceAtLeast(1)
        serverUrl   = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""
        username    = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        password    = intent.getStringExtra(EXTRA_PASSWORD) ?: ""

        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.layoutHeader?.setBackgroundColor(p.bgHeader)
        binding.viewAccent?.setBackgroundColor(p.accent)

        binding.tvChannelName.text = name.uppercase()
        binding.btnBack.setBackgroundColor(p.bgHeader)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, h ->
            binding.btnBack.setBackgroundColor(if (h) p.focus else p.bgHeader)
        }

        adapter = CatchupEpgAdapter { listing -> onProgrammeSelected(listing) }
        binding.rvProgrammes.apply {
            adapter = this@CatchupEpgActivity.adapter
            layoutManager = LinearLayoutManager(this@CatchupEpgActivity)
        }

        buildDayTabs()
        loadEpg()
    }

    private fun buildDayTabs() {
        binding.dayTabContainer.removeAllViews()
        dayTabs.clear()
        val dp = resources.displayMetrics.density
        val today = Calendar.getInstance()
        val dayFmt = SimpleDateFormat("EEE d MMM", Locale.UK)

        for (offset in 0 until archiveDays) {
            val cal = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -offset) }
            val label = when (offset) {
                0 -> "TODAY"
                1 -> "YESTERDAY"
                else -> dayFmt.format(cal.time).uppercase()
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 11f
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                isClickable = true; isFocusable = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                ).also { it.marginEnd = (4 * dp).toInt() }
                setOnClickListener { selectDay(offset) }
                setOnFocusChangeListener { _, h ->
                    val p = ThemeManager.palette()
                    if (offset != selectedDayOffset) setBackgroundColor(if (h) p.focus else p.bgPrimary)
                }
            }
            dayTabs.add(tv)
            binding.dayTabContainer.addView(tv)
        }
        highlightDayTab(0)
    }

    private fun highlightDayTab(offset: Int) {
        val p = ThemeManager.palette()
        dayTabs.forEachIndexed { i, tv ->
            tv.setBackgroundColor(if (i == offset) p.tabSelected else p.bgPrimary)
        }
    }

    private fun selectDay(offset: Int) {
        selectedDayOffset = offset
        highlightDayTab(offset)
        showListingsForDay(offset)
    }

    private fun showListingsForDay(offset: Int) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -offset)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis / 1000
        val endOfDay   = startOfDay + 86400
        val nowSec     = System.currentTimeMillis() / 1000

        val epg = allListings.filter { l ->
            val s = l.startTimestamp?.toLongOrNull() ?: return@filter false
            s >= startOfDay && s < endOfDay && s <= nowSec
        }.sortedBy { it.startTimestamp?.toLongOrNull() ?: 0L }

        if (epg.isNotEmpty()) {
            adapter.items = epg
            binding.tvEmpty.visibility = View.GONE
        } else {
            // Server didn't return EPG for this day — generate hourly slots instead
            val slots = buildTimeSlots(startOfDay, minOf(endOfDay, nowSec))
            if (slots.isNotEmpty()) {
                adapter.items = slots
                binding.tvEmpty.visibility = View.GONE
            } else {
                adapter.items = emptyList()
                binding.tvEmpty.text = "NO CATCHUP AVAILABLE FOR THIS DAY"
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun buildTimeSlots(fromSec: Long, toSec: Long): List<EpgListing> {
        if (toSec <= fromSec) return emptyList()
        val fmt = SimpleDateFormat("HH:mm", Locale.UK)
        val slots = mutableListOf<EpgListing>()
        var s = fromSec
        while (s < toSec) {
            val e = minOf(s + 3600L, toSec)
            val label = "${fmt.format(Date(s * 1000))} – ${fmt.format(Date(e * 1000))}"
            slots.add(EpgListing(
                id = null, epgId = null,
                title = android.util.Base64.encodeToString(label.toByteArray(), android.util.Base64.DEFAULT),
                description = null, start = null, end = null,
                startTimestamp = s.toString(), stopTimestamp = e.toString()
            ))
            s += 3600L
        }
        return slots
    }

    private fun loadEpg() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val result = repository.getCatchupEpg(serverUrl, username, password, streamId)
            binding.progressBar.visibility = View.GONE
            result.onSuccess { response ->
                allListings = response.listings ?: emptyList()
                showListingsForDay(selectedDayOffset)
            }.onFailure {
                binding.tvEmpty.text = "FAILED TO LOAD EPG — CHECK CONNECTION"
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun onProgrammeSelected(listing: EpgListing) {
        val start = listing.startTimestamp?.toLongOrNull() ?: return
        val stop  = listing.stopTimestamp?.toLongOrNull()  ?: return
        val durationMin = ((stop - start) / 60).toInt().coerceAtLeast(1)
        val catchupUrl = repository.buildCatchupUrl(serverUrl, username, password, streamId, start, durationMin)
        val title = listing.getDecodedTitle().ifBlank { "CATCHUP" }

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_URL, catchupUrl)
        intent.putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, title)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_ID, streamId)
        intent.putExtra(PlayerActivity.EXTRA_IS_LIVE, false as Boolean)
        startActivity(intent)
    }
}
