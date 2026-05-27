package com.skyretro.iptv.ui.games

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.skyretro.iptv.databinding.ActivityTeletextBinding
import com.skyretro.iptv.databinding.ItemTeletextHeadlineBinding
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

    data class RssItem(val title: String, val description: String, val link: String)

    private lateinit var binding: ActivityTeletextBinding
    private val feeds = TickerManager.SPORT_FEEDS
    private var currentPage = 0
    private val headlines = LinkedHashMap<String, List<RssItem>>()
    private val http = OkHttpClient()
    private var clockTimer: Timer? = null

    private var isShowingArticle = false
    private lateinit var headlineAdapter: HeadlineAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityTeletextBinding.inflate(layoutInflater)
        setContentView(binding.root)

        headlineAdapter = HeadlineAdapter { item -> openArticle(item) }
        binding.rvHeadlines.layoutManager = LinearLayoutManager(this)
        binding.rvHeadlines.adapter = headlineAdapter

        binding.btnClose.setOnClickListener {
            if (isShowingArticle) closeArticle() else finish()
        }
        binding.btnPrev.setOnClickListener  { navigatePage(-1) }
        binding.btnNext.setOnClickListener  { navigatePage(+1) }

        binding.btnRed.setOnClickListener    { goToPage(1) }
        binding.btnGreen.setOnClickListener  { goToPage(2) }
        binding.btnYellow.setOnClickListener { goToPage(4) }
        binding.btnBlue.setOnClickListener   { goToPage(6) }

        listOf(binding.btnClose, binding.btnPrev, binding.btnNext,
               binding.btnRed, binding.btnGreen, binding.btnYellow, binding.btnBlue)
            .forEach { btn ->
                btn.setOnFocusChangeListener { _, hasFocus -> btn.alpha = if (hasFocus) 1.0f else 0.75f }
            }

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

    // ── Page navigation ───────────────────────────────────────────────────────

    private fun navigatePage(delta: Int) {
        if (isShowingArticle) { closeArticle(); return }
        val total = feeds.size + 1
        goToPage((currentPage + delta + total) % total)
    }

    private fun goToPage(idx: Int) {
        if (isShowingArticle) closeArticle()
        currentPage = idx.coerceIn(0, feeds.size)
        showPage(currentPage)
    }

    private fun showPage(idx: Int) {
        if (idx == 0) showIndexPage() else showSportPage(feeds[idx - 1], idx)
    }

    // ── Page rendering ────────────────────────────────────────────────────────

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
        binding.scrollIndex.visibility  = View.VISIBLE
        binding.rvHeadlines.visibility  = View.GONE
        binding.scrollArticle.visibility = View.GONE
    }

    private fun showSportPage(feed: TickerManager.SportFeed, pageIdx: Int) {
        binding.tvPageNum.text   = "P${300 + pageIdx}"
        binding.tvPageTitle.text = "${feed.emoji} ${feed.name.uppercase()} NEWS"

        val items = headlines[feed.id]
        when {
            items == null -> {
                binding.tvContent.text = "\n\n  LOADING ${feed.name.uppercase()} NEWS...\n\n  Please wait."
                binding.scrollIndex.visibility  = View.VISIBLE
                binding.rvHeadlines.visibility  = View.GONE
            }
            items.isEmpty() -> {
                binding.tvContent.text = "\n\n  NO ${feed.name.uppercase()} HEADLINES FOUND.\n\n  Check your internet connection."
                binding.scrollIndex.visibility  = View.VISIBLE
                binding.rvHeadlines.visibility  = View.GONE
            }
            else -> {
                headlineAdapter.setItems(items)
                binding.scrollIndex.visibility  = View.GONE
                binding.rvHeadlines.visibility  = View.VISIBLE
                binding.rvHeadlines.requestFocus()
            }
        }
        binding.scrollArticle.visibility = View.GONE
    }

    // ── Article view ──────────────────────────────────────────────────────────

    private fun openArticle(item: RssItem) {
        isShowingArticle = true
        binding.tvPageTitle.text = "LOADING ARTICLE..."
        binding.scrollIndex.visibility   = View.GONE
        binding.rvHeadlines.visibility   = View.GONE
        binding.scrollArticle.visibility = View.VISIBLE
        binding.tvArticle.text = "\n  ${item.title}\n\n  ${item.description}\n\n  Fetching full story..."
        binding.btnClose.text = "◄ BACK"

        lifecycleScope.launch {
            val fetched = if (item.link.isNotEmpty()) {
                withContext(Dispatchers.IO) { fetchArticleText(item.link) }
            } else null

            val body = fetched?.takeIf { it.length > item.description.length + 50 } ?: item.description
            binding.tvPageTitle.text = "► STORY"
            binding.tvArticle.text   = buildArticleText(item.title, body)
        }
    }

    private fun closeArticle() {
        isShowingArticle = false
        binding.btnClose.text = "✕ CLOSE"
        showPage(currentPage)
    }

    private fun buildArticleText(title: String, body: String): String {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("  $title")
        sb.appendLine()
        sb.appendLine("  ─────────────────────────────────")
        sb.appendLine()
        body.split("\n\n").forEach { para ->
            val text = para.trim()
            if (text.isNotBlank()) {
                sb.append("  ")
                sb.appendLine(text)
                sb.appendLine()
            }
        }
        sb.appendLine("  ─────────────────────────────────")
        sb.appendLine("  SOURCE: BBC SPORT")
        return sb.toString()
    }

    private fun fetchArticleText(url: String): String? = try {
        val html = http.newCall(
            Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        ).execute().use { it.body?.string() ?: "" }

        // Skip page header/nav by jumping to the article or main content block
        val bodyStart = listOf("<article", "<main").firstNotNullOfOrNull { tag ->
            html.indexOf(tag, ignoreCase = true).takeIf { it >= 0 }
        } ?: 0
        var articleHtml = html.substring(bodyStart)

        // Strip figure/figcaption blocks so image captions are never included
        val DOT_ALL = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        articleHtml = articleHtml.replace(Regex("<figure[^>]*>.*?</figure>", DOT_ALL), "")
        articleHtml = articleHtml.replace(Regex("<figcaption[^>]*>.*?</figcaption>", DOT_ALL), "")

        val tagRe  = Regex("<[^>]+>")
        val paraRe = Regex("<p[^>]*>(.*?)</p>", DOT_ALL)
        val decode = { s: String ->
            s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"").trim()
        }
        val creditWords = listOf("Getty", "PA Media", "AFP", "Reuters", "Alamy", "BBC Sport", "Image source", "Image caption")

        val paras = paraRe.findAll(articleHtml)
            .map { it.groupValues[1] }
            .filter { raw ->
                val linkCount = Regex("<a ", RegexOption.IGNORE_CASE).findAll(raw).count()
                val text = decode(raw.replace(tagRe, ""))
                val isCredit = text.length < 80 && creditWords.any { text.contains(it, ignoreCase = true) }
                text.length > 40 && linkCount < 3 && !isCredit
            }
            .map { raw -> decode(raw.replace(tagRe, "")) }
            .take(8)
            .toList()

        if (paras.size >= 2) paras.joinToString("\n\n") else null
    } catch (_: Exception) { null }

    // ── RSS fetching ──────────────────────────────────────────────────────────

    private fun fetchAllFeeds() {
        binding.tvLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = LinkedHashMap<String, List<RssItem>>()
            feeds.forEach { feed ->
                try {
                    val xml = withContext(Dispatchers.IO) {
                        http.newCall(
                            Request.Builder().url(feed.rssUrl)
                                .header("User-Agent", "Mozilla/5.0").build()
                        ).execute().use { it.body?.string() ?: "" }
                    }
                    val items = parseRssItems(xml)
                    if (items.isNotEmpty()) results[feed.id] = items
                } catch (_: Exception) {}
            }

            if (results.isNotEmpty()) {
                headlines.clear()
                headlines.putAll(results)
                TickerManager.sportHeadlines = LinkedHashMap(results.mapValues { (_, v) -> v.map { it.title } })
            }

            binding.tvLoading.visibility = View.GONE
            showPage(currentPage)
        }
    }

    private fun parseRssItems(xml: String): List<RssItem> {
        val items = mutableListOf<RssItem>()
        try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(xml.reader())
            var inItem = false
            var title = ""; var desc = ""; var link = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && items.size < 10) {
                when {
                    event == XmlPullParser.START_TAG && parser.name == "item" -> {
                        inItem = true; title = ""; desc = ""; link = ""
                    }
                    event == XmlPullParser.END_TAG && parser.name == "item" -> {
                        if (inItem && title.isNotEmpty())
                            items.add(RssItem(title, desc, link))
                        inItem = false
                    }
                    inItem && event == XmlPullParser.START_TAG -> when (parser.name) {
                        "title"       -> title = parser.nextText().trim()
                        "description" -> desc  = parser.nextText().trim()
                            .replace(Regex("<[^>]+>"), "").trim()
                        "link"        -> link  = parser.nextText().trim()
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {}
        return items
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // LEFT/RIGHT navigate pages when the headline list or article has focus
            val focused = currentFocus
            val inContent = focused != null &&
                (binding.rvHeadlines.hasFocus() || binding.scrollArticle.hasFocus())
            if (inContent) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT  -> { navigatePage(-1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { navigatePage(+1); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT  -> { navigatePage(-1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { navigatePage(+1); true }
        else -> super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isShowingArticle) closeArticle() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockTimer?.cancel()
    }

    // ── Headline adapter ──────────────────────────────────────────────────────

    inner class HeadlineAdapter(private val onClick: (RssItem) -> Unit) :
        RecyclerView.Adapter<HeadlineAdapter.VH>() {

        private var items = listOf<RssItem>()

        fun setItems(newItems: List<RssItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemTeletextHeadlineBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                b.root.setOnFocusChangeListener { _, hasFocus ->
                    b.root.setBackgroundColor(if (hasFocus) 0xFF001433.toInt() else 0xFF000000.toInt())
                }
                b.root.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemTeletextHeadlineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.b.tvNumber.text   = "${position + 1}."
            holder.b.tvHeadline.text = items[position].title
        }

        override fun getItemCount() = items.size
    }
}
