package com.orbital.iptv.ui.sports

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.orbital.iptv.databinding.ItemMatchBinding

data class MatchEvent(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeLogoUrl: String,
    val awayLogoUrl: String,
    val homeScore: String,
    val awayScore: String,
    val statusState: String,
    val statusDetail: String,
    val note: String = ""
)

class MatchAdapter(
    private val onToggle: (MatchEvent) -> Unit,
    private val onLongPress: (MatchEvent) -> Unit = {}
) : ListAdapter<MatchEvent, MatchAdapter.VH>(DIFF) {

    var selectedIds: Set<String> = emptySet()
        set(value) { field = value; notifyDataSetChanged() }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MatchEvent>() {
            override fun areItemsTheSame(a: MatchEvent, b: MatchEvent) = a.id == b.id
            override fun areContentsTheSame(a: MatchEvent, b: MatchEvent) = a == b
        }
    }

    inner class VH(private val b: ItemMatchBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: MatchEvent) {
            val sel = e.id in selectedIds

            // Selection indicators
            b.selectionBar.setBackgroundColor(if (sel) 0xFF00CCCC.toInt() else 0xFF1A2A3A.toInt())
            b.tvTick.text = if (sel) "✓" else ""
            b.tvTick.setTextColor(0xFF00CCCC.toInt())

            b.tvHomeTeam.text = e.homeTeam
            b.tvAwayTeam.text = e.awayTeam

            when (e.statusState) {
                "in" -> {
                    b.tvScore.text = "${e.homeScore}  –  ${e.awayScore}"
                    b.tvStatus.text = e.statusDetail
                    b.tvScore.setTextColor(0xFFFFCC00.toInt())
                    b.tvStatus.setTextColor(0xFFFF3333.toInt())
                    b.root.setBackgroundColor(0xFF0A1D10.toInt())
                }
                "post" -> {
                    b.tvScore.text = "${e.homeScore}  –  ${e.awayScore}"
                    b.tvStatus.text = if (e.note.isNotBlank()) "FT  (${e.note})" else "FT"
                    b.tvScore.setTextColor(0xFFFFFFFF.toInt())
                    b.tvStatus.setTextColor(0xFF666666.toInt())
                    b.root.setBackgroundColor(0xFF071225.toInt())
                }
                else -> {
                    b.tvScore.text = e.statusDetail
                    b.tvStatus.text = ""
                    b.tvScore.setTextColor(0xFF00CCCC.toInt())
                    b.root.setBackgroundColor(0xFF0D1A2E.toInt())
                }
            }

            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.root.alpha = if (hasFocus) 1f else 0.9f
                if (!sel) b.selectionBar.setBackgroundColor(
                    if (hasFocus) 0xFF334455.toInt() else 0xFF1A2A3A.toInt()
                )
            }
            b.root.setOnClickListener { onToggle(e) }
            b.root.setOnLongClickListener { onLongPress(e); true }

            if (e.homeLogoUrl.isNotEmpty()) {
                Glide.with(b.ivHomeLogo.context).load(e.homeLogoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL).into(b.ivHomeLogo)
            }
            if (e.awayLogoUrl.isNotEmpty()) {
                Glide.with(b.ivAwayLogo.context).load(e.awayLogoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL).into(b.ivAwayLogo)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemMatchBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
