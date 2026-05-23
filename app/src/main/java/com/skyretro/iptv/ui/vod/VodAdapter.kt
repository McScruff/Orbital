package com.skyretro.iptv.ui.vod

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.skyretro.iptv.data.model.VodStream
import com.skyretro.iptv.databinding.ItemVodMovieBinding
import com.skyretro.iptv.utils.PosterCache
import kotlinx.coroutines.*

class VodAdapter(
    private val context: Context,
    private val onClick: (VodStream) -> Unit
) : RecyclerView.Adapter<VodAdapter.VH>() {

    private var items: List<VodStream> = emptyList()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun submitList(list: List<VodStream>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemVodMovieBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope.cancel()
    }

    inner class VH(private val b: ItemVodMovieBinding) : RecyclerView.ViewHolder(b.root) {
        private var job: Job? = null

        fun bind(movie: VodStream) {
            b.tvTitle.text = movie.name
            val r = movie.rating?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }
            b.tvRating.text = if (r != null) "★ $r" else ""
            b.ivPoster.setImageDrawable(null)
            b.ivPoster.setBackgroundColor(Color.parseColor("#111827"))
            b.root.setOnClickListener { onClick(movie) }
            b.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    b.root.scaleX = 1.07f; b.root.scaleY = 1.07f; b.root.elevation = 8f
                    b.tvTitle.setTextColor(0xFF00CCFF.toInt())
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.setColor(0xFF0D1B35.toInt()); bg.setStroke(4, 0xFF00CCFF.toInt())
                    b.root.background = bg
                } else {
                    b.root.scaleX = 1.0f; b.root.scaleY = 1.0f; b.root.elevation = 0f
                    b.tvTitle.setTextColor(0xFFFFFFFF.toInt())
                    b.root.setBackgroundColor(0xFF0D1B35.toInt())
                }
            }

            job?.cancel()
            val iconUrl = movie.streamIcon?.takeIf { it.isNotEmpty() } ?: return
            job = scope.launch {
                val bmp = PosterCache.getBitmap(context, movie.streamId, iconUrl, sampleSize = 2)
                if (bmp != null && isActive) {
                    b.ivPoster.setImageBitmap(bmp)
                    b.ivPoster.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
    }
}
