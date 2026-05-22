package com.skyretro.iptv.ui.series

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.skyretro.iptv.data.model.Episode
import com.skyretro.iptv.databinding.ItemEpisodeBinding

class EpisodeAdapter(
    private val onPlay: (Episode) -> Unit,
    private val onLongPress: (Episode) -> Unit = {}
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    private var items: List<Episode> = emptyList()
    private var selectedPosition = -1

    fun submitList(list: List<Episode>) {
        items = list
        selectedPosition = -1
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemEpisodeBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(ep: Episode, position: Int) {
            val epNum = ep.episodeNum ?: (position + 1)
            b.tvEpNum.text = "E%02d".format(epNum)
            b.tvEpTitle.text = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
            b.tvEpDuration.text = ep.info?.duration?.takeIf { it.isNotBlank() } ?: ""

            applyColors(position, focused = false, selected = position == selectedPosition)

            b.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val old = selectedPosition
                selectedPosition = pos
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(pos)
                onPlay(ep)
            }
            b.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongPress(items[pos])
                true
            }

            b.root.setOnFocusChangeListener { _, hasFocus ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnFocusChangeListener
                applyColors(pos, focused = hasFocus, selected = pos == selectedPosition)
            }
        }

        private fun applyColors(position: Int, focused: Boolean, selected: Boolean) {
            val bg = when {
                selected -> 0xFFFFCC00.toInt()
                focused  -> 0xFF2D6090.toInt()
                position % 2 == 0 -> 0xFF0D1B35.toInt()
                else -> 0xFF1A3A6A.toInt()
            }
            b.root.setBackgroundColor(bg)
            b.tvEpNum.setTextColor(if (selected) 0xFF000080.toInt() else 0xFF00CCFF.toInt())
            b.tvEpTitle.setTextColor(if (selected) 0xFF000080.toInt() else if (focused) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt())
            b.tvEpDuration.setTextColor(if (selected) 0xFF000080.toInt() else 0xFF888888.toInt())
            b.btnPlayEp.setBackgroundColor(if (selected) 0xFF000080.toInt() else 0xFFFFCC00.toInt())
            b.btnPlayEp.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF0D1B35.toInt())
        }
    }
}
