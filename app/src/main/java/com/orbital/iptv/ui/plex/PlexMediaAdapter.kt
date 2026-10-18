package com.orbital.iptv.ui.plex

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.orbital.iptv.data.plex.PlexItem
import com.orbital.iptv.databinding.ItemEmbyMediaBinding

class PlexMediaAdapter(
    private val onItemClick: (PlexItem) -> Unit
) : RecyclerView.Adapter<PlexMediaAdapter.VH>() {

    private var items: List<PlexItem> = emptyList()
    private var artworkUrl: ((PlexItem) -> String?) = { null }
    private var sourceBadge: String? = null

    fun submitList(list: List<PlexItem>, sourceBadge: String? = null, artworkUrlBuilder: (PlexItem) -> String?) {
        items = list
        this.sourceBadge = sourceBadge
        artworkUrl = artworkUrlBuilder
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEmbyMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemEmbyMediaBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: PlexItem) {
            b.tvTitle.text = item.title
            val nebula = com.orbital.iptv.utils.ThemeManager.current == com.orbital.iptv.utils.ThemeManager.AppTheme.NEBULA
            val d = b.root.resources.displayMetrics.density
            val nbCard = com.orbital.iptv.utils.ThemeManager.palette().bgMid
            val nbAccent = com.orbital.iptv.utils.ThemeManager.palette().accent
            val ph = if (nebula) nbCard else 0xFF0D1B35.toInt()
            if (nebula) {
                b.root.background = android.graphics.drawable.GradientDrawable().apply { setColor(nbCard); cornerRadius = 14 * d }
                b.ivPoster.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 10 * d)
                    }
                }
                b.ivPoster.clipToOutline = true
            }
            val res = item.videoResolutionLabel()
            if (res != null) {
                b.tvResolution.text = res
                b.tvResolution.visibility = View.VISIBLE
            } else {
                b.tvResolution.visibility = View.GONE
            }
            val sub = buildList {
                sourceBadge?.let { add(it) }
                item.subtitle().takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString("  ·  ")
            b.tvSubtitle.text = sub
            b.tvSubtitle.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

            val dur    = item.duration ?: 0L
            val offset = item.viewOffset ?: 0L
            val pct    = if (dur > 0L) ((offset.toFloat() / dur) * 100).toInt() else 0
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
                    .placeholder(android.graphics.drawable.ColorDrawable(ph))
                    .error(android.graphics.drawable.ColorDrawable(ph))
                    .into(b.ivPoster)
            } else {
                Glide.with(b.root.context).clear(b.ivPoster)
                b.ivPoster.setBackgroundColor(ph)
            }

            b.root.setOnClickListener { onItemClick(item) }
            b.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    b.root.scaleX = 1.06f; b.root.scaleY = 1.06f; b.root.elevation = 8f
                    b.tvTitle.setTextColor(if (nebula) nbAccent else 0xFFE5A00D.toInt())
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.setColor(ph); bg.setStroke(3, if (nebula) nbAccent else 0xFFE5A00D.toInt())
                    if (nebula) bg.cornerRadius = 14 * d
                    b.root.background = bg
                } else {
                    b.root.scaleX = 1f; b.root.scaleY = 1f; b.root.elevation = 0f
                    b.tvTitle.setTextColor(0xFFFFFFFF.toInt())
                    if (nebula) b.root.background = android.graphics.drawable.GradientDrawable().apply { setColor(ph); cornerRadius = 14 * d }
                    else b.root.setBackgroundColor(ph)
                }
            }
        }
    }
}
