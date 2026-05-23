package com.skyretro.iptv.ui.radio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.skyretro.iptv.R
import com.skyretro.iptv.utils.ThemeManager

class RadioAdapter(private val onClick: (RadioStation) -> Unit) :
    RecyclerView.Adapter<RadioAdapter.VH>() {

    var items: List<RadioStation> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_station_name)
        val tvUrl: TextView  = view.findViewById(R.id.tv_station_url)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_radio_station, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val station = items[position]
        holder.tvName.text = station.name.uppercase()
        holder.tvUrl.text  = station.url

        val p = ThemeManager.palette()
        val bg = if (position % 2 == 0) p.bgPrimary else p.bgMid
        holder.itemView.setBackgroundColor(bg)

        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            holder.itemView.setBackgroundColor(if (hasFocus) p.focus else bg)
        }
        holder.itemView.setOnClickListener { onClick(station) }
        holder.itemView.isFocusable = true
        holder.itemView.isClickable = true
    }
}
