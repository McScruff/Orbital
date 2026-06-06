package com.orbital.iptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.orbital.iptv.R
import com.orbital.iptv.data.model.LiveStream
import com.orbital.iptv.utils.ThemeManager

class ChannelAdapter(
    private val onChannelClick: (LiveStream) -> Unit,
    private val onChannelLongClick: ((LiveStream) -> Unit)? = null
) : ListAdapter<LiveStream, ChannelAdapter.ChannelViewHolder>(DIFF_CALLBACK) {

    private var selectedPosition = -1

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LiveStream>() {
            override fun areItemsTheSame(old: LiveStream, new: LiveStream) = old.streamId == new.streamId
            override fun areContentsTheSame(old: LiveStream, new: LiveStream) = old == new
        }
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val channelNumber: TextView = itemView.findViewById(R.id.tv_channel_number)
        val channelName: TextView = itemView.findViewById(R.id.tv_channel_name)
        val channelIcon: ImageView = itemView.findViewById(R.id.iv_channel_icon)
        val epgNow: TextView? = itemView.findViewById(R.id.tv_epg_now)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val old = selectedPosition
                selectedPosition = pos
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(pos)
                onChannelClick(getItem(pos))
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                onChannelLongClick?.invoke(getItem(pos))
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val stream = getItem(position)
        holder.channelNumber.text = (position + 1).toString().padStart(3, '0')
        holder.channelName.text = stream.name.uppercase()
        holder.epgNow?.text = stream.epgNow ?: "PROGRAMME INFORMATION UNAVAILABLE"

        // Load channel icon
        if (!stream.streamIcon.isNullOrEmpty()) {
            Glide.with(holder.channelIcon.context)
                .load(stream.streamIcon)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(holder.channelIcon)
        } else {
            holder.channelIcon.setImageResource(R.drawable.ic_channel_placeholder)
        }

        val p = ThemeManager.palette()
        val density = holder.itemView.resources.displayMetrics.density

        fun applyRowBg(pos: Int, focused: Boolean) {
            val color = when {
                pos == selectedPosition -> p.rowSelected
                focused                 -> p.focus
                pos % 2 == 0            -> p.rowEven
                else                    -> p.rowOdd
            }
            holder.itemView.background = ThemeManager.roundedBg(color, density)
            if (p.cardElevation > 0f) holder.itemView.elevation = p.cardElevation * density
        }

        // Highlight selected / alternating rows
        applyRowBg(position, false)
        val textColor = when {
            position == selectedPosition -> 0xFF001040.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        holder.channelNumber.setTextColor(textColor)
        holder.channelName.setTextColor(textColor)

        // TV D-pad focus highlight
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos == selectedPosition) return@setOnFocusChangeListener
            applyRowBg(pos, hasFocus)
            val tc = if (hasFocus || pos % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
            holder.channelNumber.setTextColor(tc)
            holder.channelName.setTextColor(tc)
        }
    }
}
