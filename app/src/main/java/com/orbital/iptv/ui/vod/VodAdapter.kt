package com.orbital.iptv.ui.vod

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.orbital.iptv.data.model.VodStream
import com.orbital.iptv.databinding.ItemVodMovieBinding
import com.orbital.iptv.utils.PosterCache
import com.orbital.iptv.utils.ThemeManager
import kotlinx.coroutines.*

class VodAdapter(
    private val context: Context,
    private val onClick: (VodStream) -> Unit
) : RecyclerView.Adapter<VodAdapter.VH>() {

    private var items: List<VodStream> = emptyList()
    private var resumeLabels: Map<Int, String> = emptyMap()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun submitList(list: List<VodStream>, resumeLabels: Map<Int, String> = emptyMap()) {
        items = list
        this.resumeLabels = resumeLabels
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
            val nebula = ThemeManager.current == ThemeManager.AppTheme.NEBULA
            b.tvTitle.text = movie.name
            val resumeLabel = resumeLabels[movie.streamId]
            if (resumeLabel != null) {
                b.tvRating.text = resumeLabel
                b.tvRating.setTextColor(if (nebula) 0xFF22D3EE.toInt() else 0xFF00CCFF.toInt())
            } else {
                val r = movie.rating?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }
                b.tvRating.text = if (r != null) "★ $r" else ""
                b.tvRating.setTextColor(0xFFE5A00D.toInt())
            }
            b.ivPoster.setImageDrawable(null)
            val p = ThemeManager.palette()
            val d = b.root.resources.displayMetrics.density
            b.ivPoster.setBackgroundColor(p.bgMid)
            if (nebula) {
                b.ivPoster.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 10 * d)
                    }
                }
                b.ivPoster.clipToOutline = true
                b.tvTitle.textSize = 11f
                b.root.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(p.bgMid); cornerRadius = 14 * d
                }
            } else {
                b.root.setBackgroundColor(p.bgMid)
            }
            b.root.setOnClickListener { onClick(movie) }
            b.root.setOnFocusChangeListener { _, hasFocus ->
                val pp = ThemeManager.palette()
                if (hasFocus) {
                    b.root.scaleX = 1.07f; b.root.scaleY = 1.07f; b.root.elevation = 8f
                    b.tvTitle.setTextColor(pp.accent)
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.setColor(pp.bgMid); bg.setStroke(4, pp.accent)
                    if (nebula) bg.cornerRadius = 14 * d
                    b.root.background = bg
                } else {
                    b.root.scaleX = 1.0f; b.root.scaleY = 1.0f; b.root.elevation = 0f
                    b.tvTitle.setTextColor(0xFFFFFFFF.toInt())
                    if (nebula) b.root.background = android.graphics.drawable.GradientDrawable().apply { setColor(pp.bgMid); cornerRadius = 14 * d }
                    else b.root.setBackgroundColor(pp.bgMid)
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
