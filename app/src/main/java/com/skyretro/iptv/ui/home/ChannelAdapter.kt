package com.skyretro.iptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.skyretro.iptv.R
import com.skyretro.iptv.data.model.LiveStream

class ChannelAdapter(
    private val onChannelClick: (LiveStream) -> Unit
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

        // Highlight selected / alternating rows
        when {
            position == selectedPosition -> {
                holder.itemView.setBackgroundColor(0xFFFFCC00.toInt())
                holder.channelNumber.setTextColor(0xFF000080.toInt())
                holder.channelName.setTextColor(0xFF000080.toInt())
            }
            position % 2 == 0 -> {
                holder.itemView.setBackgroundColor(0xFF1A3A6A.toInt())
                holder.channelNumber.setTextColor(0xFFFFFFFF.toInt())
                holder.channelName.setTextColor(0xFFFFFFFF.toInt())
            }
            else -> {
                holder.itemView.setBackgroundColor(0xFF142D55.toInt())
                holder.channelNumber.setTextColor(0xFFCCCCCC.toInt())
                holder.channelName.setTextColor(0xFFCCCCCC.toInt())
            }
        }

        // TV D-pad focus highlight
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos == selectedPosition) return@setOnFocusChangeListener
            holder.itemView.setBackgroundColor(
                when {
                    hasFocus     -> 0xFF2D6090.toInt()
                    pos % 2 == 0 -> 0xFF1A3A6A.toInt()
                    else         -> 0xFF142D55.toInt()
                }
            )
            val textColor = if (hasFocus) 0xFFFFFFFF.toInt() else if (pos % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
            holder.channelNumber.setTextColor(textColor)
            holder.channelName.setTextColor(textColor)
        }
    }
}
