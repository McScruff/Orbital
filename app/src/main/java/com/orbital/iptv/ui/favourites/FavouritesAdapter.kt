package com.orbital.iptv.ui.favourites

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.orbital.iptv.data.model.FavouriteItem
import com.orbital.iptv.databinding.ItemFavouriteBinding
import com.orbital.iptv.utils.FavouritesManager
import com.orbital.iptv.utils.PosterCache
import com.orbital.iptv.utils.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FavouritesAdapter(
    private val scope: CoroutineScope,
    private val onClick: (FavouriteItem) -> Unit,
    private val onLongPress: (FavouriteItem) -> Unit
) : RecyclerView.Adapter<FavouritesAdapter.VH>() {

    private var items: List<FavouriteItem> = emptyList()

    fun submitList(list: List<FavouriteItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFavouriteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemFavouriteBinding) : RecyclerView.ViewHolder(b.root) {
        private var artJob: Job? = null

        fun bind(item: FavouriteItem, position: Int) {
            b.tvTitle.text = item.title
            b.tvSubtitle.text = when (item.type) {
                com.orbital.iptv.data.model.FavType.EPISODE -> "S${item.season}E%02d".format(item.episodeNum)
                com.orbital.iptv.data.model.FavType.LIVE    -> "LIVE TV"
                else                                          -> "MOVIE"
            }

            // Progress bar for resume items
            if (item.hasResume) {
                b.progressContainer.visibility = android.view.View.VISIBLE
                b.tvResumeLabel.visibility = android.view.View.VISIBLE
                val resumeStr = FavouritesManager.formatDuration(item.resumePositionMs)
                val durStr    = FavouritesManager.formatDuration(item.durationMs)
                b.tvResumeLabel.text = "CONTINUE FROM $resumeStr / $durStr"
                b.tvBadge.text = "RESUME ▶"
                // Set progress bar width proportionally
                b.progressContainer.post {
                    val params = b.progressFill.layoutParams
                    params.width = (b.progressContainer.width * item.progressFraction).toInt()
                    b.progressFill.layoutParams = params
                }
            } else {
                b.progressContainer.visibility = android.view.View.GONE
                b.tvResumeLabel.visibility = android.view.View.GONE
                b.tvBadge.text = "▶"
            }

            // Row colours
            applyColors(position, focused = false)

            b.root.setOnClickListener { onClick(item) }
            b.root.setOnLongClickListener { onLongPress(item); true }
            b.root.setOnFocusChangeListener { _, hasFocus -> applyColors(position, hasFocus) }

            // Load art
            artJob?.cancel()
            if (item.artUrl.isNotEmpty()) {
                artJob = scope.launch {
                    val bmp = PosterCache.getBitmap(b.root.context, item.streamId, item.artUrl, sampleSize = 4)
                    if (bmp != null) b.ivArt.setImageBitmap(bmp)
                }
            } else {
                b.ivArt.setImageDrawable(null)
            }
        }

        private fun applyColors(position: Int, focused: Boolean) {
            val p = ThemeManager.palette()
            val density = b.root.resources.displayMetrics.density
            val bg = when {
                focused            -> p.focus
                position % 2 == 0 -> p.rowEven
                else               -> p.rowOdd
            }
            b.root.background = ThemeManager.roundedBg(bg, density)
            b.tvTitle.setTextColor(if (focused) p.highlight else 0xFFFFFFFF.toInt())
        }
    }
}
