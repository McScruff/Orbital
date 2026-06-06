package com.orbital.iptv.ui.catchup

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.orbital.iptv.data.model.EpgListing
import com.orbital.iptv.data.model.getDecodedTitle
import com.orbital.iptv.databinding.ItemCatchupProgrammeBinding
import com.orbital.iptv.utils.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CatchupEpgAdapter(
    private val onClick: (EpgListing) -> Unit
) : RecyclerView.Adapter<CatchupEpgAdapter.VH>() {

    var items: List<EpgListing> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    inner class VH(val binding: ItemCatchupProgrammeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCatchupProgrammeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)

    override fun onBindViewHolder(holder: VH, position: Int) {
        val listing = items[position]
        val start = listing.startTimestamp?.toLongOrNull() ?: 0L
        val stop  = listing.stopTimestamp?.toLongOrNull()  ?: 0L

        holder.binding.tvTitle.text = listing.getDecodedTitle().ifBlank { "UNKNOWN" }.uppercase()
        holder.binding.tvTime.text = timeFmt.format(Date(start * 1000))

        val durationMin = if (stop > start) ((stop - start) / 60).toInt() else 0
        holder.binding.tvDuration.text = "${durationMin} MIN"

        val p = ThemeManager.palette()
        val base = if (position % 2 == 0) p.bgMid else p.bgPrimary
        holder.binding.root.setBackgroundColor(base)
        holder.binding.root.setOnFocusChangeListener { _, hasFocus ->
            holder.binding.root.setBackgroundColor(if (hasFocus) p.focus else base)
        }
        holder.binding.root.setOnClickListener { onClick(listing) }
    }
}
