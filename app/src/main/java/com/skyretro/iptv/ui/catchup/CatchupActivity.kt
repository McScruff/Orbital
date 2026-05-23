package com.skyretro.iptv.ui.catchup

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.skyretro.iptv.data.model.LiveCategory
import com.skyretro.iptv.data.model.LiveStream
import com.skyretro.iptv.data.model.mapCategoryToSky
import com.skyretro.iptv.data.repository.XtreamRepository
import com.skyretro.iptv.databinding.ActivityCatchupBinding
import com.skyretro.iptv.utils.PrefsManager
import com.skyretro.iptv.utils.ThemeManager
import kotlinx.coroutines.launch

class CatchupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCatchupBinding
    private lateinit var adapter: CatchupChannelAdapter
    private val repository = XtreamRepository()

    private var allCatchupStreams: List<LiveStream> = emptyList()
    private var categoryMap: Map<String, List<LiveStream>> = emptyMap()
    private var categories: List<LiveCategory> = emptyList()
    private var selectedCategoryId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityCatchupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)
        binding.layoutHeader?.setBackgroundColor(p.bgHeader)
        binding.viewAccent?.setBackgroundColor(p.accent)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setBackgroundColor(p.bgHeader)
        binding.btnBack.setOnFocusChangeListener { _, h ->
            binding.btnBack.setBackgroundColor(if (h) p.focus else p.bgHeader)
        }

        adapter = CatchupChannelAdapter(this) { channel -> onChannelSelected(channel) }
        binding.rvChannels.apply {
            adapter = this@CatchupActivity.adapter
            layoutManager = LinearLayoutManager(this@CatchupActivity)
        }

        val creds = PrefsManager.getCredentials(this) ?: run { finish(); return }
        loadCatchupChannels(creds.serverUrl, creds.username, creds.password)
    }

    private fun loadCatchupChannels(serverUrl: String, username: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = repository.getLiveStreams(serverUrl, username, password)
            binding.progressBar.visibility = View.GONE
            result.onSuccess { streams ->
                allCatchupStreams = streams.filter { (it.tvArchive ?: 0) == 1 }
                if (allCatchupStreams.isEmpty()) {
                    binding.tvError.text = "NO CATCHUP CHANNELS FOUND ON THIS SERVER"
                    binding.tvError.visibility = View.VISIBLE
                    return@onSuccess
                }
                // Group by categoryId
                categoryMap = allCatchupStreams.groupBy { it.categoryId ?: "" }

                // Fetch live categories to get names
                val catResult = repository.getLiveCategories(serverUrl, username, password)
                categories = catResult.getOrNull()
                    ?.filter { categoryMap.containsKey(it.categoryId) }
                    ?: emptyList()

                buildCategoryMenu()
                // Auto-select first category
                val firstId = categories.firstOrNull()?.categoryId
                    ?: categoryMap.keys.firstOrNull() ?: ""
                selectCategory(firstId)
            }.onFailure {
                binding.tvError.text = "FAILED TO LOAD: ${it.message?.uppercase()}"
                binding.tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun buildCategoryMenu() {
        val container = binding.catContainer
        container.removeAllViews()
        val rowH = (38 * resources.displayMetrics.density).toInt()
        val pad  = (12 * resources.displayMetrics.density).toInt()

        categories.forEachIndexed { i, cat ->
            container.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, 0, 0)
                text = cat.categoryName.uppercase()
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                isClickable = true; isFocusable = true
                val isSelected = cat.categoryId == selectedCategoryId
                applyRowStyle(this, i, isSelected)
                setOnFocusChangeListener { _, hasFocus ->
                    val p = ThemeManager.palette()
                    if (cat.categoryId != selectedCategoryId) {
                        setBackgroundColor(if (hasFocus) p.focus else if (i % 2 == 0) p.bgMid else p.bgPrimary)
                    }
                }
                setOnClickListener { selectCategory(cat.categoryId) }
            })
        }
    }

    private fun applyRowStyle(tv: TextView, index: Int, isSelected: Boolean) {
        val p = ThemeManager.palette()
        if (isSelected) {
            tv.setBackgroundColor(p.highlight)
            tv.setTextColor(0xFF000000.toInt())
        } else {
            tv.setBackgroundColor(if (index % 2 == 0) p.bgMid else p.bgPrimary)
            tv.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun selectCategory(categoryId: String) {
        selectedCategoryId = categoryId
        val channels = categoryMap[categoryId] ?: emptyList()
        adapter.items = channels
        binding.tvChannelCount.text = "${channels.size} CHANNELS"

        val catName = categories.find { it.categoryId == categoryId }?.categoryName?.uppercase()
            ?: categoryId.uppercase()
        binding.tvCurrentCategory.text = catName

        // Rebuild menu to update highlight
        buildCategoryMenu()
    }

    private fun onChannelSelected(channel: LiveStream) {
        val creds = PrefsManager.getCredentials(this) ?: return
        startActivity(Intent(this, CatchupEpgActivity::class.java).apply {
            putExtra(CatchupEpgActivity.EXTRA_STREAM_ID, channel.streamId)
            putExtra(CatchupEpgActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(CatchupEpgActivity.EXTRA_ARCHIVE_DAYS, channel.tvArchiveDuration ?: 3)
            putExtra(CatchupEpgActivity.EXTRA_SERVER_URL, creds.serverUrl)
            putExtra(CatchupEpgActivity.EXTRA_USERNAME, creds.username)
            putExtra(CatchupEpgActivity.EXTRA_PASSWORD, creds.password)
        })
    }
}
