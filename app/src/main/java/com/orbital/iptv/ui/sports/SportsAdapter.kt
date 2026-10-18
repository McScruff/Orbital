package com.orbital.iptv.ui.sports

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.orbital.iptv.databinding.ItemMatchBinding
import com.orbital.iptv.databinding.ItemSportRowBinding
import com.orbital.iptv.databinding.ItemStandingBinding
import com.orbital.iptv.utils.ThemeManager

/**
 * Every Sports Bar list: section headers, matches/bouts, league tables and general rows.
 * [pinnedIds] marks matches pinned to the scores ticker and Goal Flash.
 */
class SportsAdapter(
    private val onClick: (SportsRow) -> Unit,
    private val onLongPress: (SportsRow) -> Unit
) : ListAdapter<SportsRow, RecyclerView.ViewHolder>(DIFF) {

    var pinnedIds: Set<String> = emptySet()
        set(value) { field = value; notifyDataSetChanged() }

    private companion object {
        const val HEADER = 0
        const val MATCH = 1
        const val STANDING = 2
        const val ITEM = 3

        const val CARD = 0xFF171531.toInt()
        const val CARD_ALT = 0xFF11102A.toInt()
        const val CARD_LIVE = 0xFF12262E.toInt()
        const val CARD_DONE = 0xFF100F26.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val CYAN = 0xFF22D3EE.toInt()
        const val RED = 0xFFFF5C6C.toInt()
        const val MUTED = 0xFF8A87B0.toInt()
        const val DIM = 0xFF7A77A0.toInt()

        val DIFF = object : DiffUtil.ItemCallback<SportsRow>() {
            override fun areItemsTheSame(a: SportsRow, b: SportsRow) = key(a) == key(b)
            override fun areContentsTheSame(a: SportsRow, b: SportsRow) = a == b
            private fun key(r: SportsRow): String = when (r) {
                is SportsRow.Header -> "h:${r.title}"
                is SportsRow.Match -> "m:${r.event.id}"
                is SportsRow.Standing -> "s:${r.entry.groupName}:${r.entry.team}"
                is SportsRow.Item -> "i:${r.id}"
            }
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is SportsRow.Header -> HEADER
        is SportsRow.Match -> MATCH
        is SportsRow.Standing -> STANDING
        is SportsRow.Item -> ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            HEADER -> HeaderVH(parent)
            MATCH -> MatchVH(ItemMatchBinding.inflate(inflater, parent, false))
            STANDING -> StandingVH(ItemStandingBinding.inflate(inflater, parent, false))
            else -> ItemVH(ItemSportRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is HeaderVH -> holder.bind(row as SportsRow.Header)
            is MatchVH -> holder.bind(row as SportsRow.Match)
            is StandingVH -> holder.bind((row as SportsRow.Standing).entry)
            is ItemVH -> holder.bind(row as SportsRow.Item, position)
        }
    }

    /** Inset rounded card with a focus ring, shared by every focusable row type. */
    private fun styleCard(root: View, color: Int) {
        val d = root.resources.displayMetrics.density
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(
            (12 * d).toInt(), (3 * d).toInt(), (12 * d).toInt(), (3 * d).toInt()
        )
        root.clipToOutline = true
        root.background = ThemeManager.focusRowDrawable(d, color, root.isFocused)
        root.setOnFocusChangeListener { _, has -> root.background = ThemeManager.focusRowDrawable(d, color, has) }
    }

    private fun wire(root: View, row: SportsRow) {
        root.setOnClickListener { onClick(row) }
        root.setOnLongClickListener { onLongPress(row); true }
    }

    // ── Section header ────────────────────────────────────────────────────────

    private class HeaderVH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val d = resources.displayMetrics.density
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
            addView(TextView(context).apply {
                setTextColor(CYAN); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.04f
            })
            addView(TextView(context).apply {
                setTextColor(MUTED); textSize = 12f
                setPadding((12 * d).toInt(), 0, 0, 0)
            })
        }
    ) {
        fun bind(h: SportsRow.Header) {
            val box = itemView as LinearLayout
            (box.getChildAt(0) as TextView).text = h.title
            (box.getChildAt(1) as TextView).text = h.subtitle
        }
    }

    // ── Match / bout ──────────────────────────────────────────────────────────

    private inner class MatchVH(private val b: ItemMatchBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: SportsRow.Match) {
            val e = row.event
            val pinned = e.id in pinnedIds
            b.tvTick.visibility = if (e.isFight) View.GONE else View.VISIBLE
            b.tvTick.text = if (pinned) "✓" else ""
            b.tvTick.setTextColor(CYAN)
            b.tvHomeTeam.text = side(e.homeTeam, e.homeScore, e.isFight, e.homeWinner)
            b.tvAwayTeam.text = side(e.awayTeam, e.awayScore, e.isFight, e.awayWinner)
            b.tvHomeTeam.setTextColor(if (e.homeWinner) CYAN else WHITE)
            b.tvAwayTeam.setTextColor(if (e.awayWinner) CYAN else WHITE)

            val bg = when (e.statusState) {
                "in" -> {
                    b.tvScore.text = if (e.isFight) "LIVE" else "${e.homeScore}  –  ${e.awayScore}"
                    b.tvStatus.text = if (e.isFight) e.note else e.statusDetail
                    b.tvScore.setTextColor(if (e.isFight) RED else WHITE)
                    b.tvStatus.setTextColor(if (e.isFight) MUTED else RED)
                    CARD_LIVE
                }
                "post" -> {
                    b.tvScore.text = if (e.isFight) e.finalLabel else "${e.homeScore}  –  ${e.awayScore}"
                    b.tvStatus.text = when {
                        e.isFight -> e.note
                        e.note.isNotBlank() -> "${e.finalLabel}  (${e.note})"
                        else -> e.finalLabel
                    }
                    b.tvScore.setTextColor(WHITE)
                    b.tvStatus.setTextColor(DIM)
                    CARD_DONE
                }
                else -> {
                    b.tvScore.text = e.statusDetail
                    b.tvStatus.text = if (e.isFight) e.note else ""
                    b.tvScore.setTextColor(CYAN)
                    b.tvStatus.setTextColor(MUTED)
                    CARD
                }
            }
            b.selectionBar.setBackgroundColor(when {
                pinned -> CYAN
                e.statusState == "in" -> RED
                else -> 0xFF2A2750.toInt()
            })
            styleCard(b.root, bg)
            wire(b.root, row)
            logo(b.ivHomeLogo, e.homeLogoUrl)
            logo(b.ivAwayLogo, e.awayLogoUrl)
        }

        /** A fighter's record goes on a smaller second line; a winner gets a "W" marker. */
        private fun side(name: String, record: String, isFight: Boolean, winner: Boolean): CharSequence {
            if (!isFight) return name
            val s = SpannableStringBuilder(if (winner) "W  $name" else name)
            if (winner) s.setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (record.isNotBlank()) {
                val start = s.length
                s.append("\n").append(record)
                s.setSpan(RelativeSizeSpan(0.8f), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(ForegroundColorSpan(MUTED), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return s
        }
    }

    private fun logo(view: android.widget.ImageView, url: String) {
        if (url.isNotEmpty()) {
            Glide.with(view.context).load(url).diskCacheStrategy(DiskCacheStrategy.ALL).into(view)
        } else {
            Glide.with(view.context).clear(view)
            view.setImageDrawable(null)
        }
    }

    // ── League table ──────────────────────────────────────────────────────────

    private inner class StandingVH(private val b: ItemStandingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: StandingEntry) {
            val d = b.root.resources.displayMetrics.density
            (b.root.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(
                (12 * d).toInt(), (2 * d).toInt(), (12 * d).toInt(), (2 * d).toInt()
            )
            if (e.isGroupHeader) {
                // Not a real row: D-pad focus skips it.
                b.root.isFocusable = false
                b.root.isClickable = false
                b.root.setOnFocusChangeListener(null)
                b.tvPos.visibility = View.INVISIBLE
                b.ivLogo.visibility = View.INVISIBLE
                b.tvTeam.text = "  ${e.groupName}"
                b.tvTeam.setTextColor(CYAN)
                listOf(b.tvPlayed, b.tvWon, b.tvDrawn, b.tvLost, b.tvGd, b.tvPoints).forEach { it.text = "" }
                b.root.setBackgroundColor(0xFF0D0B1E.toInt())
                return
            }
            // Rows must be focusable so D-pad moves row by row and the list scrolls with it.
            b.root.isFocusable = true
            b.root.isClickable = true
            b.tvPos.visibility = View.VISIBLE
            b.ivLogo.visibility = View.VISIBLE
            b.tvTeam.setTextColor(WHITE)
            b.tvPos.text = e.pos.toString()
            b.tvTeam.text = e.team
            b.tvPlayed.text = e.played.toString()
            b.tvWon.text = e.won.toString()
            b.tvDrawn.text = e.drawn
            b.tvLost.text = e.lost.toString()
            b.tvGd.text = e.gd
            b.tvPoints.text = e.points
            styleCard(b.root, if (e.pos % 2 == 0) CARD_ALT else CARD)
            logo(b.ivLogo, e.logoUrl)
        }
    }

    // ── General row ───────────────────────────────────────────────────────────

    private inner class ItemVH(private val b: ItemSportRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: SportsRow.Item, position: Int) {
            b.tvLead.text = row.lead
            b.tvLead.visibility = if (row.lead.isEmpty()) View.GONE else View.VISIBLE
            b.ivImage.visibility = if (row.imageUrl.isEmpty()) View.GONE else View.VISIBLE
            logo(b.ivImage, row.imageUrl)
            b.tvTitle.text = row.title
            b.tvSubtitle.text = row.subtitle
            b.tvSubtitle.visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
            b.tvTrail.text = row.trail
            b.tvTrail.setTextColor(when {
                row.live -> RED
                row.done -> DIM
                else -> CYAN
            })
            val bg = when {
                row.live -> CARD_LIVE
                row.done -> CARD_DONE
                position % 2 == 0 -> CARD
                else -> CARD_ALT
            }
            styleCard(b.root, bg)
            wire(b.root, row)
        }
    }
}
