package com.skyretro.iptv.ui.games

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.skyretro.iptv.databinding.ActivityTeletextBinding
import com.skyretro.iptv.utils.TickerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class TeletextActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeletextBinding

    private val feeds = TickerManager.SPORT_FEEDS
    private var currentPage = 0 // 0 = index, 1..N = sport pages
    private val headlines = LinkedHashMap<String, List<String>>()

    private val http = OkHttpClient()
    private var clockTimer: Timer? = null

    // Pre-populate from cached TickerManager data so there's instant content if available
    init {
        TickerManager.sportHeadlines.forEach { (k, v) -> headlines[k] = v }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityTeletextBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnPrev.setOnClickListener  { navigatePage(-1) }
        binding.btnNext.setOnClickListener  { navigatePage(+1) }

        // Classic teletext colour buttons
        binding.btnRed.setOnClickListener    { goToPage(1) } // Football
        binding.btnGreen.setOnClickListener  { goToPage(2) } // Cricket
        binding.btnYellow.setOnClickListener { goToPage(4) } // Golf
        binding.btnBlue.setOnClickListener   { goToPage(6) } // Formula 1

        listOf(binding.btnClose, binding.btnPrev, binding.btnNext,
               binding.btnRed, binding.btnGreen, binding.btnYellow, binding.btnBlue)
            .forEach { btn -> btn.setOnFocusChangeListener { _, hasFocus -> btn.alpha = if (hasFocus) 1.0f else 0.75f } }

        startClock()
        showPage(0)
        fetchAllFeeds()
    }

    private fun startClock() {
        val fmt = SimpleDateFormat("HH:mm  EEE dd MMM", Locale.UK)
        binding.tvDatetime.text = fmt.format(Date())
        clockTimer = Timer()
        clockTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread { binding.tvDatetime.text = fmt.format(Date()) }
            }
        }, 60_000L, 60_000L)
    }

    private fun navigatePage(delta: Int) {
        val total = feeds.size + 1 // index + sport pages
        goToPage((currentPage + delta + total) % total)
    }

    private fun goToPage(idx: Int) {
        currentPage = idx.coerceIn(0, feeds.size)
        showPage(currentPage)
    }

    private fun showPage(idx: Int) {
        if (idx == 0) showIndexPage() else showSportPage(feeds[idx - 1], idx)
    }

    private fun showIndexPage() {
        binding.tvPageNum.text   = "P300"
        binding.tvPageTitle.text = "SPORTS NEWS INDEX"

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("  SPORTS TELETEXT  —  BBC SPORT")
        sb.appendLine()
        sb.appendLine("  ─────────────────────────────")
        sb.appendLine()
        feeds.forEachIndexed { i, feed ->
            val pageNum = 301 + i
            val loaded = if (headlines.containsKey(feed.id)) "" else " ..."
            val dotCount = maxOf(1, 26 - feed.name.length)
            sb.appendLine("  ${feed.emoji}  ${feed.name}${".".repeat(dotCount)} P$pageNum$loaded")
        }
        sb.appendLine()
        sb.appendLine("  ─────────────────────────────")
        sb.appendLine()
        sb.appendLine("  USE  ◄ PREV / NEXT ►  TO NAVIGATE")
        sb.appendLine("  OR TAP A COLOUR BUTTON BELOW")

        binding.tvContent.text = sb.toString()
    }

    private fun showSportPage(feed: TickerManager.SportFeed, pageIdx: Int) {
        binding.tvPageNum.text   = "P${300 + pageIdx}"
        binding.tvPageTitle.text = "${feed.emoji} ${feed.name.uppercase()} NEWS"

        val items = headlines[feed.id]
        binding.tvContent.text = when {
            items == null   -> "\n\n  LOADING ${feed.name.uppercase()} NEWS...\n\n  Please wait."
            items.isEmpty() -> "\n\n  NO ${feed.name.uppercase()} HEADLINES FOUND.\n\n  Check your internet connection."
            else -> buildSportPageText(feed, items)
        }
    }

    private fun buildSportPageText(feed: TickerManager.SportFeed, items: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("  ${feed.emoji} ${feed.name.uppercase()} LATEST HEADLINES")
        sb.appendLine("  ─────────────────────────────────")
        sb.appendLine()
        items.forEachIndexed { idx, headline ->
            sb.appendLine("  ${idx + 1}.  $headline")
            sb.appendLine()
        }
        sb.appendLine("  ─────────────────────────────────")
        sb.appendLine("  SOURCE: BBC SPORT RSS")
        return sb.toString()
    }

    private fun fetchAllFeeds() {
        binding.tvLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            val results = LinkedHashMap<String, List<String>>()
            feeds.forEach { feed ->
                try {
                    val xml = withContext(Dispatchers.IO) {
                        http.newCall(
                            Request.Builder()
                                .url(feed.rssUrl)
                                .header("User-Agent", "Mozilla/5.0")
                                .build()
                        ).execute().use { it.body?.string() ?: "" }
                    }
                    val titles = parseRssTitles(xml)
                    if (titles.isNotEmpty()) results[feed.id] = titles
                } catch (_: Exception) {}
            }

            if (results.isNotEmpty()) {
                headlines.clear()
                headlines.putAll(results)
                TickerManager.sportHeadlines = LinkedHashMap(results)
            }

            binding.tvLoading.visibility = View.GONE
            showPage(currentPage)
        }
    }

    private fun parseRssTitles(xml: String): List<String> {
        val titles = mutableListOf<String>()
        try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(xml.reader())
            var inItem = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && titles.size < 10) {
                when {
                    event == XmlPullParser.START_TAG && parser.name == "item" -> inItem = true
                    event == XmlPullParser.END_TAG   && parser.name == "item" -> inItem = false
                    inItem && event == XmlPullParser.START_TAG && parser.name == "title" ->
                        titles.add(parser.nextText().trim())
                }
                event = parser.next()
            }
        } catch (_: Exception) {}
        return titles
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT  -> { navigatePage(-1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { navigatePage(+1); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        clockTimer?.cancel()
    }
}
