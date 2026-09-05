package com.orbital.iptv.ui.sports

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.orbital.iptv.databinding.ItemStandingBinding
import com.orbital.iptv.utils.ThemeManager

data class StandingEntry(
    val pos: Int,
    val team: String,
    val logoUrl: String,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val gd: String,
    val points: Int,
    val isGroupHeader: Boolean = false,
    val groupName: String = ""
)

class StandingAdapter : ListAdapter<StandingEntry, StandingAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StandingEntry>() {
            override fun areItemsTheSame(a: StandingEntry, b: StandingEntry) =
                a.isGroupHeader == b.isGroupHeader &&
                if (a.isGroupHeader) a.groupName == b.groupName else a.team == b.team
            override fun areContentsTheSame(a: StandingEntry, b: StandingEntry) = a == b
        }
    }

    inner class VH(private val b: ItemStandingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: StandingEntry) {
            if (e.isGroupHeader) {
                // Not a real row — D-pad focus (and therefore the RecyclerView's built-in
                // scroll-into-view behavior, see the normal-row branch below) should skip it.
                b.root.isFocusable = false
                b.root.isClickable = false
                b.root.setOnFocusChangeListener(null)
                b.tvPos.visibility  = View.INVISIBLE
                b.ivLogo.visibility = View.INVISIBLE
                b.tvTeam.text = "  ${e.groupName}"
                b.tvTeam.setTextColor(0xFF00FFFF.toInt())
                b.tvPlayed.text = ""; b.tvWon.text  = ""
                b.tvDrawn.text  = ""; b.tvLost.text = ""
                b.tvGd.text     = ""; b.tvPoints.text = ""
                b.root.setBackgroundColor(0xFF0D1F3C.toInt())
                return
            }

            b.root.isFocusable = true
            b.root.isClickable = true
            b.tvPos.visibility  = View.VISIBLE
            b.ivLogo.visibility = View.VISIBLE
            b.tvTeam.setTextColor(0xFFFFFFFF.toInt())

            b.tvPos.text    = e.pos.toString()
            b.tvTeam.text   = e.team
            b.tvPlayed.text = e.played.toString()
            b.tvWon.text    = e.won.toString()
            b.tvDrawn.text  = e.drawn.toString()
            b.tvLost.text   = e.lost.toString()
            b.tvGd.text     = e.gd
            b.tvPoints.text = e.points.toString()

            // Plain alternation for every row — pos 1-4 used to get their own solid (non-alternating)
            // colours to flag European qualification spots, which broke the zebra-stripe pattern
            // right where the eye expects it to start.
            val rootBg = if (e.pos % 2 == 0) 0xFF071225.toInt() else 0xFF0D1A2E.toInt()
            // Rows need to actually be focusable for D-pad navigation to move focus row-by-row —
            // that's what makes the RecyclerView auto-scroll to keep the focused row in view.
            // Without it, the whole list is one single focus stop and can't be scrolled past
            // whatever's already visible.
            val density = b.root.resources.displayMetrics.density
            b.root.background = ThemeManager.focusRowDrawable(density, rootBg, b.root.isFocused)
            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.root.background = ThemeManager.focusRowDrawable(density, rootBg, hasFocus)
            }

            if (e.logoUrl.isNotEmpty()) {
                Glide.with(b.ivLogo.context).load(e.logoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL).into(b.ivLogo)
            } else {
                b.ivLogo.setImageDrawable(null)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemStandingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
