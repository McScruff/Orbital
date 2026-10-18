package com.orbital.iptv.ui.nebula

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.orbital.iptv.R
import com.orbital.iptv.data.api.ApiClient
import com.orbital.iptv.data.model.EpgListing
import com.orbital.iptv.data.model.LiveCategory
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.data.model.getDecodedDescription
import com.orbital.iptv.data.model.getDecodedTitle
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.databinding.ActivityNebulaLiveBinding
import com.orbital.iptv.databinding.ItemNebulaCategoryBinding
import com.orbital.iptv.databinding.ItemNebulaChannelBinding
import com.orbital.iptv.ui.home.HomeViewModel
import com.orbital.iptv.ui.player.PcmOnlyRenderersFactory
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.utils.CategoryPrefs
import com.orbital.iptv.utils.ChannelQueue
import com.orbital.iptv.utils.EpgCache
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PinManager
import com.orbital.iptv.utils.PrefsManager
import com.orbital.iptv.utils.RecentChannelsManager
import com.orbital.iptv.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NEBULA theme's Live TV screen: categories | channels | live preview + "on now" detail.
 * Reuses HomeViewModel for the category/channel/EPG plumbing; only the presentation is new.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NebulaLiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNebulaLiveBinding
    private lateinit var viewModel: HomeViewModel
    private val repository = XtreamRepository()
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)

    private data class CatRow(val category: LiveCategory, val count: Int)
    private data class NowInfo(
        val title: String, val start: Long, val end: Long, val desc: String,
        val nextTitle: String?, val nextStart: Long
    ) {
        fun progress(nowSec: Long): Float =
            if (end > start) ((nowSec - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
    }

    private var visibleCategories: List<LiveCategory> = emptyList()
    private var catRows: List<CatRow> = emptyList()
    private var shownCatRows: List<CatRow> = emptyList()
    private var selectedCategoryId: String? = null

    private var allChannels: List<LiveStream> = emptyList()
    private var shownChannels: List<LiveStream> = emptyList()
    private var selectedChannel: LiveStream? = null

    private val nowInfo = HashMap<Int, NowInfo>()
    private val nowRequested = HashSet<Int>()
    private var favIds: Set<Int> = emptySet()

    private lateinit var catAdapter: CategoryAdapter
    private lateinit var chAdapter: ChannelAdapter

    private var player: ExoPlayer? = null
    private var initialFocusDone = false

    private val previewRunnable = Runnable { selectedChannel?.let { startPreview(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ThemeManager.load(this)
        binding = ActivityNebulaLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ApiClient.liveFormat = PrefsManager.getLiveFormat(this)
        viewModel = ViewModelProvider(this)[HomeViewModel::class.java]

        NebulaRail.bind(this, binding.rail, NebulaRail.Item.LIVE, onHome = { finish() }, onLive = {})

        catAdapter = CategoryAdapter()
        binding.nbCatList.layoutManager = LinearLayoutManager(this)
        binding.nbCatList.adapter = catAdapter
        binding.nbCatList.itemAnimator = null

        chAdapter = ChannelAdapter()
        binding.nbChList.layoutManager = LinearLayoutManager(this)
        binding.nbChList.adapter = chAdapter
        binding.nbChList.itemAnimator = null

        binding.nbCatSearch.addTextChangedListener(simpleWatcher { applyCategoryFilter() })
        binding.nbChSearch.addTextChangedListener(simpleWatcher { applyChannelFilter() })

        binding.nbPreview.setOnClickListener { selectedChannel?.let { playFullscreen(it) } }
        binding.nbBtnWatch.setOnClickListener { selectedChannel?.let { playFullscreen(it) } }
        binding.nbBtnFav.setOnClickListener { selectedChannel?.let { toggleFavourite(it) } }
        binding.nbBtnFullGuide.setOnClickListener {
            startActivity(Intent(this, com.orbital.iptv.ui.home.HomeActivity::class.java)
                .putExtra(com.orbital.iptv.ui.home.HomeActivity.EXTRA_CLASSIC, true))
        }

        observeViewModel()
        val creds = PrefsManager.getCredentials(this)
        if (creds == null) { finish(); return }
        viewModel.loadData(creds.serverUrl, creds.username, creds.password)
    }

    override fun onResume() {
        super.onResume()
        NebulaRail.refresh(this, binding.rail)
        favIds = FavouritesManager.getLiveChannels(this).map { it.streamId }.toSet()
        chAdapter.notifyDataSetChanged()
        selectedChannel?.let { scheduleFocusPreview(it) }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(previewRunnable)
        releasePlayer()
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            if (state.isLoading) {
                binding.nbStatus.text = "Loading channels..."
                binding.nbStatus.visibility = View.VISIBLE
            }
            state.error?.let {
                binding.nbStatus.text = it
                binding.nbStatus.visibility = View.VISIBLE
            }
            if (state.xtreamCategories.isNotEmpty()) buildCategories(state.xtreamCategories)
            selectedCategoryId = state.selectedXtreamCategory?.categoryId
            catAdapter.notifyDataSetChanged()
            if (state.selectedXtreamCategory != null && !state.isLoading) {
                allChannels = state.channels
                applyChannelFilter()
            }
        }
        viewModel.epgUpdate.observe(this) { streamId -> loadNowFromCache(streamId) }
    }

    private fun buildCategories(all: List<LiveCategory>) {
        val hidden = CategoryPrefs.getHiddenServerCatIds(this)
        val visible = all.filter { it.categoryId !in hidden }
        if (visible == visibleCategories && catRows.isNotEmpty()) return
        visibleCategories = visible
        val counts = viewModel.getAllStreams().groupingBy { it.categoryId }.eachCount()
        val favCount = FavouritesManager.getLiveChannels(this).size
        catRows = listOf(CatRow(HomeViewModel.FAV_CATEGORY, favCount)) +
            visible.map { CatRow(it, counts[it.categoryId] ?: 0) }
        applyCategoryFilter()
    }

    private fun applyCategoryFilter() {
        val q = binding.nbCatSearch.text.toString().trim()
        shownCatRows = if (q.isEmpty()) catRows
        else catRows.filter { it.category.categoryName.contains(q, ignoreCase = true) }
        catAdapter.notifyDataSetChanged()
    }

    private fun applyChannelFilter() {
        val q = binding.nbChSearch.text.toString().trim()
        shownChannels = if (q.isEmpty()) allChannels else allChannels.filter { it.name.contains(q, ignoreCase = true) }
        binding.nbChSearch.hint = "Search ${allChannels.size} channels"
        chAdapter.notifyDataSetChanged()
        binding.nbStatus.visibility = if (shownChannels.isEmpty()) View.VISIBLE else View.GONE
        if (shownChannels.isEmpty()) binding.nbStatus.text = if (allChannels.isEmpty()) "No channels" else "No matches"

        val keep = selectedChannel?.let { sel -> shownChannels.firstOrNull { it.streamId == sel.streamId } }
        if (keep == null) shownChannels.firstOrNull()?.let { selectChannel(it, preview = true) }
        else selectedChannel = keep

        if (!initialFocusDone && shownChannels.isNotEmpty()) {
            initialFocusDone = true
            binding.nbChList.post { binding.nbChList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    // ── EPG ───────────────────────────────────────────────────────────────────

    private fun computeNow(listings: List<EpgListing>): NowInfo? {
        val nowSec = System.currentTimeMillis() / 1000
        val sorted = listings.sortedBy { it.startTimestamp?.toLongOrNull() ?: 0L }
        val idx = sorted.indexOfFirst { l ->
            val s = l.startTimestamp?.toLongOrNull() ?: return@indexOfFirst false
            val e = l.stopTimestamp?.toLongOrNull() ?: return@indexOfFirst false
            nowSec in s..e
        }
        if (idx < 0) return null
        val cur = sorted[idx]
        val next = sorted.getOrNull(idx + 1)
        return NowInfo(
            title = cur.getDecodedTitle(),
            start = cur.startTimestamp?.toLongOrNull() ?: 0L,
            end = cur.stopTimestamp?.toLongOrNull() ?: 0L,
            desc = cur.getDecodedDescription(),
            nextTitle = next?.getDecodedTitle()?.takeIf { it.isNotBlank() },
            nextStart = next?.startTimestamp?.toLongOrNull() ?: 0L
        )
    }

    private fun loadNowFromCache(streamId: Int, fetchIfMissing: Boolean = false) {
        lifecycleScope.launch {
            var listings = EpgCache.get(this@NebulaLiveActivity, streamId)
            if (listings == null && fetchIfMissing) {
                val creds = PrefsManager.getCredentials(this@NebulaLiveActivity) ?: return@launch
                val res = withContext(Dispatchers.IO) {
                    repository.getShortEpg(creds.serverUrl, creds.username, creds.password, streamId)
                }
                listings = res.getOrNull()?.listings
                if (listings != null) EpgCache.put(this@NebulaLiveActivity, streamId, listings)
            }
            val info = listings?.let { computeNow(it) } ?: return@launch
            nowInfo[streamId] = info
            val pos = shownChannels.indexOfFirst { it.streamId == streamId }
            if (pos >= 0) chAdapter.notifyItemChanged(pos)
            if (selectedChannel?.streamId == streamId) updateDetail()
        }
    }

    // ── Selection / detail ────────────────────────────────────────────────────

    private fun selectChannel(stream: LiveStream, preview: Boolean) {
        val old = selectedChannel
        selectedChannel = stream
        if (old?.streamId != stream.streamId) {
            listOf(old, stream).forEach { s ->
                val pos = shownChannels.indexOfFirst { it.streamId == s?.streamId }
                if (pos >= 0) chAdapter.notifyItemChanged(pos)
            }
        }
        updateDetail()
        if (preview) scheduleFocusPreview(stream)
        loadNowFromCache(stream.streamId, fetchIfMissing = true)
    }

    private fun updateDetail() {
        val s = selectedChannel ?: return
        binding.nbDetName.text = s.name
        binding.nbDetNum.text = "Channel ${s.num ?: ""}".trim()
        Glide.with(this).load(s.streamIcon).fitCenter()
            .placeholder(R.drawable.ic_channel_placeholder).error(R.drawable.ic_channel_placeholder)
            .into(binding.nbDetLogo)
        binding.nbBtnFav.imageTintList = android.content.res.ColorStateList.valueOf(
            if (s.streamId in favIds) 0xFFFF5C97.toInt() else 0xFFB8B5D6.toInt()
        )
        val info = nowInfo[s.streamId]
        if (info == null) {
            binding.nbNowTime.text = ""
            binding.nbNowChip.visibility = View.GONE
            binding.nbNowTitle.text = s.epgNow ?: "No guide data"
            binding.nbNowProgress.visibility = View.INVISIBLE
            binding.nbNowDesc.text = ""
            binding.nbNext.text = ""
            return
        }
        binding.nbNowTime.text = "${timeFmt.format(Date(info.start * 1000))} – ${timeFmt.format(Date(info.end * 1000))}"
        binding.nbNowChip.visibility = View.VISIBLE
        binding.nbNowTitle.text = info.title
        binding.nbNowProgress.visibility = View.VISIBLE
        binding.nbNowProgress.progress = (info.progress(System.currentTimeMillis() / 1000) * 1000).toInt()
        binding.nbNowDesc.text = info.desc
        binding.nbNext.text = info.nextTitle?.let { "NEXT  ${timeFmt.format(Date(info.nextStart * 1000))}  $it" } ?: ""
    }

    // ── Preview player ────────────────────────────────────────────────────────

    private fun scheduleFocusPreview(stream: LiveStream) {
        handler.removeCallbacks(previewRunnable)
        binding.nbPreviewMsg.text = ""
        handler.postDelayed(previewRunnable, 600)
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(
                PcmOnlyRenderersFactory(this, false)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        p.setVideoTextureView(binding.nbPreviewTexture)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.nbPreviewMsg.text = if (state == Player.STATE_BUFFERING) "Loading..." else ""
            }
            override fun onPlayerError(error: PlaybackException) {
                binding.nbPreviewMsg.text = "Preview unavailable. Press OK to watch."
            }
        })
        player = p
        return p
    }

    private fun startPreview(stream: LiveStream) {
        val url = viewModel.buildStreamUrl(stream.streamId)
        if (url.isBlank()) return
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun playFullscreen(stream: LiveStream) {
        handler.removeCallbacks(previewRunnable)
        releasePlayer()
        ChannelQueue.entries = shownChannels.map { ChannelQueue.Entry(it.streamId, it.name, it.num ?: -1) }
        ChannelQueue.currentIndex = shownChannels.indexOfFirst { it.streamId == stream.streamId }.coerceAtLeast(0)
        val url = viewModel.buildStreamUrl(stream.streamId)
        RecentChannelsManager.record(this, stream.name, stream.streamId, url, stream.streamIcon)
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, stream.name)
            putExtra(PlayerActivity.EXTRA_STREAM_ID, stream.streamId)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, true)
            stream.num?.let { putExtra(PlayerActivity.EXTRA_CHANNEL_NUM, it) }
        })
    }

    private fun toggleFavourite(stream: LiveStream) {
        if (stream.streamId in favIds) {
            FavouritesManager.removeLive(this, stream.streamId)
            Toast.makeText(this, "REMOVED FROM FAVOURITES", Toast.LENGTH_SHORT).show()
        } else {
            FavouritesManager.addLiveChannel(
                this, stream.name, stream.streamId, viewModel.buildStreamUrl(stream.streamId), stream.streamIcon
            )
            Toast.makeText(this, "ADDED TO FAVOURITES", Toast.LENGTH_SHORT).show()
        }
        favIds = FavouritesManager.getLiveChannels(this).map { it.streamId }.toSet()
        val pos = shownChannels.indexOfFirst { it.streamId == stream.streamId }
        if (pos >= 0) chAdapter.notifyItemChanged(pos)
        if (selectedChannel?.streamId == stream.streamId) updateDetail()
        // Favourites count in the category list.
        catRows = catRows.map { if (it.category.categoryId == HomeViewModel.FAV_CATEGORY_ID) it.copy(count = favIds.size) else it }
        applyCategoryFilter()
    }

    private fun selectCategory(row: CatRow) {
        if (row.category.categoryId == HomeViewModel.FAV_CATEGORY_ID) {
            val ids = FavouritesManager.getLiveChannels(this).map { it.streamId }.toSet()
            val recent = RecentChannelsManager.getAll(this).map { it.streamId }
            viewModel.selectFavouriteChannels(ids, recent)
        } else if (PinManager.isCategoryLocked(this, row.category.categoryId)) {
            promptPin { viewModel.selectXtreamCategory(row.category) }
        } else {
            viewModel.selectXtreamCategory(row.category)
        }
        binding.nbChSearch.setText("")
    }

    private fun promptPin(onOk: () -> Unit) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            hint = "PIN"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle("ENTER PIN TO VIEW CATEGORY")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                if (et.text.toString() == PinManager.getPin(this)) onOk()
                else Toast.makeText(this, "INCORRECT PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    // ── Adapters ──────────────────────────────────────────────────────────────

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryAdapter.VH>() {
        inner class VH(val b: ItemNebulaCategoryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemNebulaCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = shownCatRows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val row = shownCatRows[position]
            val isFav = row.category.categoryId == HomeViewModel.FAV_CATEGORY_ID
            h.b.nbCatName.text = if (isFav) "★  Favourites" else row.category.categoryName
            h.b.nbCatCount.text = row.count.toString()
            h.b.nbCatRoot.isSelected = row.category.categoryId == selectedCategoryId
            h.b.nbCatName.setTextColor(if (h.b.nbCatRoot.isSelected) 0xFFFFFFFF.toInt() else 0xFFD8D6F0.toInt())
            h.b.nbCatRoot.setOnClickListener { selectCategory(row) }
        }
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.VH>() {
        inner class VH(val b: ItemNebulaChannelBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemNebulaChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = shownChannels.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = shownChannels[position]
            h.b.nbChNum.text = (s.num ?: (position + 1)).toString()
            h.b.nbChName.text = s.name
            val info = nowInfo[s.streamId]
            h.b.nbChNow.text = info?.title ?: s.epgNow ?: ""
            if (info != null) {
                h.b.nbChProgress.visibility = View.VISIBLE
                h.b.nbChProgress.progress = (info.progress(System.currentTimeMillis() / 1000) * 1000).toInt()
            } else {
                h.b.nbChProgress.visibility = View.INVISIBLE
                if (nowRequested.add(s.streamId)) loadNowFromCache(s.streamId)
            }
            h.b.nbChFav.visibility = if (s.streamId in favIds) View.VISIBLE else View.GONE
            Glide.with(h.b.nbChLogo).load(s.streamIcon).fitCenter()
                .placeholder(R.drawable.ic_channel_placeholder).error(R.drawable.ic_channel_placeholder)
                .into(h.b.nbChLogo)

            h.b.nbChRoot.isSelected = s.streamId == selectedChannel?.streamId
            h.b.nbChRoot.setOnClickListener { playFullscreen(s) }
            h.b.nbChRoot.setOnLongClickListener { toggleFavourite(s); true }
            h.b.nbChRoot.setOnFocusChangeListener { _, focused ->
                if (focused) selectChannel(s, preview = true)
            }
        }
    }
}
