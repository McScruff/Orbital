package com.skyretro.iptv.ui.catchup

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.skyretro.iptv.data.model.LiveStream
import com.skyretro.iptv.databinding.ItemCatchupChannelBinding
import com.skyretro.iptv.utils.ThemeManager

class CatchupChannelAdapter(
    private val context: Context,
    private val onClick: (LiveStream) -> Unit
) : RecyclerView.Adapter<CatchupChannelAdapter.VH>() {

    var items: List<LiveStream> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    inner class VH(val binding: ItemCatchupChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCatchupChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.binding.tvChannelName.text = ch.name
        val days = ch.tvArchiveDuration ?: 0
        holder.binding.tvArchiveBadge.text = if (days > 0) "${days}D" else "LIVE"

        if (!ch.streamIcon.isNullOrEmpty()) {
            Glide.with(context).load(ch.streamIcon).into(holder.binding.ivChannelIcon)
        } else {
            holder.binding.ivChannelIcon.setImageDrawable(null)
        }

        val p = ThemeManager.palette()
        val base = if (position % 2 == 0) p.bgMid else p.bgPrimary
        holder.binding.root.setBackgroundColor(base)
        holder.binding.root.setOnFocusChangeListener { _, hasFocus ->
            holder.binding.root.setBackgroundColor(if (hasFocus) p.focus else base)
        }
        holder.binding.root.setOnClickListener { onClick(ch) }
    }
}
