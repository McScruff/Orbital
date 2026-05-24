package com.skyretro.iptv.ui.emby

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.skyretro.iptv.data.emby.EmbyItem
import com.skyretro.iptv.databinding.ItemEmbyMediaBinding

class EmbyMediaAdapter(
    private val onItemClick: (EmbyItem) -> Unit
) : RecyclerView.Adapter<EmbyMediaAdapter.VH>() {

    private var items: List<EmbyItem> = emptyList()
    private var artworkUrl: ((EmbyItem) -> String?) = { null }

    fun submitList(list: List<EmbyItem>, artworkUrlBuilder: (EmbyItem) -> String?) {
        items = list
        artworkUrl = artworkUrlBuilder
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEmbyMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemEmbyMediaBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: EmbyItem) {
            b.tvTitle.text = item.name
            val sub = item.subtitle()
            b.tvSubtitle.text = sub
            b.tvSubtitle.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

            val pct = item.userData?.playedPercentage?.toInt() ?: 0
            if (pct in 1..99) {
                b.progressWatched.visibility = View.VISIBLE
                b.progressWatched.progress = pct
            } else {
                b.progressWatched.visibility = View.GONE
            }

            val url = artworkUrl(item)
            if (url != null) {
                Glide.with(b.root.context)
                    .load(url)
                    .centerCrop()
                    .placeholder(android.graphics.drawable.ColorDrawable(0xFF0D1B35.toInt()))
                    .error(android.graphics.drawable.ColorDrawable(0xFF0D1B35.toInt()))
                    .into(b.ivPoster)
            } else {
                Glide.with(b.root.context).clear(b.ivPoster)
                b.ivPoster.setBackgroundColor(0xFF0D1B35.toInt())
            }

            b.root.setOnClickListener { onItemClick(item) }
            b.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    b.root.scaleX = 1.06f; b.root.scaleY = 1.06f; b.root.elevation = 8f
                    b.tvTitle.setTextColor(0xFF00CCFF.toInt())
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.setColor(0xFF0D1B35.toInt()); bg.setStroke(3, 0xFF00CCFF.toInt())
                    b.root.background = bg
                } else {
                    b.root.scaleX = 1f; b.root.scaleY = 1f; b.root.elevation = 0f
                    b.tvTitle.setTextColor(0xFFFFFFFF.toInt())
                    b.root.setBackgroundColor(0xFF0D1B35.toInt())
                }
            }
        }
    }
}
