package com.orbital.iptv.ui.torbox

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.orbital.iptv.R
import com.orbital.iptv.data.torbox.TorboxFile
import com.orbital.iptv.data.torbox.TorboxRepository
import com.orbital.iptv.data.torbox.TorboxTorrent
import com.orbital.iptv.data.torbox.isPlayable
import com.orbital.iptv.databinding.ActivityTorboxBrowserBinding
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.utils.ThemeManager
import com.orbital.iptv.utils.TorboxPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class TorboxBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTorboxBrowserBinding
    private lateinit var adapter: TorrentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ThemeManager.load(this)
        binding = ActivityTorboxBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)

        adapter = TorrentAdapter { torrent -> onTorrentClicked(torrent) }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, f ->
            binding.btnBack.setBackgroundColor(if (f) p.focus else 0xFF1A3560.toInt())
        }
        binding.btnRefresh.setOnClickListener { loadTorrents() }
        binding.btnRefresh.setOnFocusChangeListener { _, f ->
            binding.btnRefresh.setBackgroundColor(if (f) p.focus else 0xFF1A3560.toInt())
        }

        loadTorrents()
    }

    private fun loadTorrents() {
        val apiKey = TorboxPrefsManager.getApiKey(this)
        if (apiKey == null) {
            Toast.makeText(this, "NO TORBOX API KEY SET", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { TorboxRepository.listTorrents(apiKey) }
            binding.progress.visibility = View.GONE
            result.fold(
                onSuccess = { torrents ->
                    adapter.submitList(torrents)
                    val empty = torrents.isEmpty()
                    binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
                },
                onFailure = {
                    Toast.makeText(this@TorboxBrowserActivity, "TORBOX ERROR: ${it.message?.take(100)}", Toast.LENGTH_LONG).show()
                    binding.tvEmpty.visibility = View.VISIBLE
                }
            )
        }
    }

    private fun onTorrentClicked(torrent: TorboxTorrent) {
        val playableFiles = torrent.files
        if (playableFiles.isEmpty()) {
            Toast.makeText(this, "NO FILES IN THIS DOWNLOAD", Toast.LENGTH_SHORT).show()
            return
        }
        if (playableFiles.size == 1) {
            playFile(torrent, playableFiles[0])
            return
        }
        val labels = playableFiles.map { f ->
            val sizeLabel = formatSize(f.sizeBytes)
            if (f.isPlayable()) "${f.shortName}  ($sizeLabel)" else "${f.shortName}  ($sizeLabel) — unsupported"
        }.toTypedArray()

        AlertDialog.Builder(this, ThemeManager.dialogStyle())
            .setTitle(torrent.name.uppercase())
            .setItems(labels) { _, which -> playFile(torrent, playableFiles[which]) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun playFile(torrent: TorboxTorrent, file: TorboxFile) {
        if (!file.isPlayable()) {
            Toast.makeText(this, "UNSUPPORTED FILE FORMAT: ${file.shortName.substringAfterLast('.', "")}", Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = TorboxPrefsManager.getApiKey(this) ?: return
        Toast.makeText(this, "FETCHING LINK…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                TorboxRepository.requestDownloadLink(apiKey, torrent.id, file.id)
            }
            result.fold(
                onSuccess = { url ->
                    startActivity(Intent(this@TorboxBrowserActivity, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_STREAM_URL, url)
                        putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, file.shortName)
                        putExtra(PlayerActivity.EXTRA_IS_LIVE, false)
                    })
                },
                onFailure = {
                    Toast.makeText(this@TorboxBrowserActivity, "COULD NOT GET LINK: ${it.message?.take(100)}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb > 1024) "${(mb / 1024.0 * 10).roundToInt() / 10.0} GB" else "${mb.roundToInt()} MB"
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

private class TorrentAdapter(
    private val onClick: (TorboxTorrent) -> Unit
) : ListAdapter<TorboxTorrent, TorrentAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TorboxTorrent>() {
            override fun areItemsTheSame(a: TorboxTorrent, b: TorboxTorrent) = a.id == b.id
            override fun areContentsTheSame(a: TorboxTorrent, b: TorboxTorrent) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val tvMeta: TextView = view.findViewById(R.id.tv_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_torbox_torrent, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val torrent = getItem(position)
        holder.tvName.text = torrent.name
        val sizeMb = torrent.sizeBytes / 1024.0 / 1024.0
        val sizeLabel = if (sizeMb > 1024) "${(sizeMb / 1024.0 * 10).roundToInt() / 10.0} GB" else "${sizeMb.roundToInt()} MB"
        val status = if (torrent.downloadFinished) "READY" else "IN PROGRESS"
        holder.tvMeta.text = "$status  •  ${torrent.files.size} file(s)  •  $sizeLabel"

        val p = ThemeManager.palette()
        val rowBg = if (position % 2 == 0) 0xFF0D1B35.toInt() else 0xFF0A1628.toInt()
        holder.itemView.setBackgroundColor(rowBg)
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            holder.itemView.setBackgroundColor(if (hasFocus) p.rowSelected else rowBg)
        }
        holder.itemView.setOnClickListener { onClick(torrent) }
    }
}
