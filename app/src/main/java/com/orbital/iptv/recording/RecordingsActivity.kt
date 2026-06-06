package com.orbital.iptv.recording

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import com.orbital.iptv.databinding.ActivityRecordingsBinding
import com.orbital.iptv.ui.player.PlayerActivity
import com.orbital.iptv.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private val dao by lazy { RecordingDatabase.get(this).dao() }
    private lateinit var adapter: RecordingAdapter
    private var showingScheduled = true
    private var allRecordings = listOf<RecordingEntity>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.load(this)
        val p = ThemeManager.palette()
        binding.root.setBackgroundColor(p.bgPrimary)

        adapter = RecordingAdapter(
            onPlay   = { playRecording(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, f ->
            binding.btnBack.setBackgroundColor(if (f) p.focus else 0xFF1A3560.toInt())
        }

        binding.btnScheduled.setOnClickListener { setTab(true) }
        binding.btnScheduled.setOnFocusChangeListener { _, f ->
            binding.btnScheduled.setBackgroundColor(when {
                f            -> p.focus
                showingScheduled -> 0xFF1A4090.toInt()
                else             -> 0xFF0D1B35.toInt()
            })
        }

        binding.btnCompleted.setOnClickListener { setTab(false) }
        binding.btnCompleted.setOnFocusChangeListener { _, f ->
            binding.btnCompleted.setBackgroundColor(when {
                f                -> p.focus
                !showingScheduled -> 0xFF1A4090.toInt()
                else              -> 0xFF0D1B35.toInt()
            })
        }

        setTab(true)
        observeRecordings()
    }

    private fun setTab(scheduled: Boolean) {
        showingScheduled = scheduled
        binding.btnScheduled.setBackgroundColor(if (scheduled) 0xFF1A4090.toInt() else 0xFF0D1B35.toInt())
        binding.btnCompleted.setBackgroundColor(if (scheduled) 0xFF0D1B35.toInt() else 0xFF1A4090.toInt())
        refreshAdapter()
    }

    private fun refreshAdapter() {
        val filtered = if (showingScheduled)
            allRecordings.filter { it.status == RecordingStatus.SCHEDULED || it.status == RecordingStatus.RECORDING }
        else
            allRecordings.filter { it.status in listOf(RecordingStatus.COMPLETED, RecordingStatus.FAILED, RecordingStatus.SKIPPED) }
        adapter.submitList(filtered)
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = if (showingScheduled) "NO SCHEDULED RECORDINGS" else "NO COMPLETED RECORDINGS"
        binding.recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun observeRecordings() {
        lifecycleScope.launch {
            dao.allFlow().collectLatest { all ->
                allRecordings = all
                refreshAdapter()
            }
        }
    }

    private fun playRecording(rec: RecordingEntity) {
        if (rec.filePath.isBlank() || !File(rec.filePath).exists()) {
            Toast.makeText(this, "RECORDING FILE NOT FOUND", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL,   "file://${rec.filePath}")
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, "${rec.channelName} — ${rec.epgTitle}")
            putExtra(PlayerActivity.EXTRA_STREAM_ID,    -1)
            putExtra(PlayerActivity.EXTRA_IS_LIVE,      false)
        })
    }

    private fun confirmDelete(rec: RecordingEntity) {
        AlertDialog.Builder(this, R.style.Theme_Orbital_Dialog)
            .setTitle("DELETE RECORDING")
            .setMessage("Delete '${rec.epgTitle}'?\nThis will also remove the recorded file.")
            .setPositiveButton("DELETE") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    dao.delete(rec)
                    if (rec.filePath.isNotBlank()) File(rec.filePath).delete()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class RecordingAdapter(
    private val onPlay: (RecordingEntity) -> Unit,
    private val onDelete: (RecordingEntity) -> Unit
) : ListAdapter<RecordingEntity, RecordingAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RecordingEntity>() {
            override fun areItemsTheSame(a: RecordingEntity, b: RecordingEntity) = a.id == b.id
            override fun areContentsTheSame(a: RecordingEntity, b: RecordingEntity) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvChannel: TextView = view.findViewById(R.id.tv_channel)
        val tvStatus:  TextView = view.findViewById(R.id.tv_status)
        val tvTitle:   TextView = view.findViewById(R.id.tv_title)
        val tvTime:    TextView = view.findViewById(R.id.tv_time)
        val btnPlay:   Button   = view.findViewById(R.id.btn_play)
        val btnDelete: Button   = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = getItem(position)
        val sdf    = SimpleDateFormat("EEE dd MMM  HH:mm", Locale.UK)
        val endSdf = SimpleDateFormat("HH:mm", Locale.UK)

        holder.tvChannel.text = rec.channelName.uppercase()
        holder.tvTitle.text   = rec.epgTitle.ifBlank { "Recording" }

        val startDate = Date(rec.scheduledStart)
        holder.tvTime.text = when {
            rec.scheduledEnd > 0L -> "${sdf.format(startDate)} — ${endSdf.format(Date(rec.scheduledEnd))}"
            else                  -> "${sdf.format(startDate)} (unlimited)"
        }

        holder.tvStatus.text = when (rec.status) {
            RecordingStatus.SCHEDULED  -> "SCHEDULED"
            RecordingStatus.RECORDING  -> "● REC"
            RecordingStatus.COMPLETED  -> "COMPLETED"
            RecordingStatus.FAILED     -> "FAILED"
            RecordingStatus.SKIPPED    -> "SKIPPED"
        }
        holder.tvStatus.setBackgroundColor(when (rec.status) {
            RecordingStatus.RECORDING  -> 0xFFCC0000.toInt()
            RecordingStatus.COMPLETED  -> 0xFF1A6030.toInt()
            RecordingStatus.FAILED     -> 0xFF602020.toInt()
            RecordingStatus.SKIPPED    -> 0xFF404040.toInt()
            else                       -> 0xFF1A3560.toInt()
        })

        val canPlay = rec.status == RecordingStatus.COMPLETED &&
            rec.filePath.isNotBlank() && java.io.File(rec.filePath).exists()
        holder.btnPlay.visibility = if (canPlay) View.VISIBLE else View.GONE

        // ── Focus highlight on the row (buttons blocked by descendantFocusability) ──
        val p     = ThemeManager.palette()
        val rowBg = if (position % 2 == 0) 0xFF0D1B35.toInt() else 0xFF0A1628.toInt()
        holder.itemView.setBackgroundColor(rowBg)
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            holder.itemView.setBackgroundColor(if (hasFocus) p.rowSelected else rowBg)
        }

        // ── Actions — D-pad OK on the row or touch on any button ─────────────
        val onClick = View.OnClickListener { showActions(holder.itemView.context, rec, canPlay) }
        holder.itemView.setOnClickListener(onClick)
        holder.btnPlay.setOnClickListener(onClick)
        holder.btnDelete.setOnClickListener(onClick)
    }

    private fun showActions(context: android.content.Context, rec: RecordingEntity, canPlay: Boolean) {
        val options = buildList {
            if (canPlay) add("▶  PLAY RECORDING")
            add(if (rec.status == RecordingStatus.SCHEDULED) "✕  CANCEL SCHEDULE" else "✕  DELETE RECORDING")
        }
        AlertDialog.Builder(context, R.style.Theme_Orbital_Dialog)
            .setTitle(rec.epgTitle.ifBlank { rec.channelName }.uppercase())
            .setItems(options.toTypedArray()) { _, which ->
                if (options[which].startsWith("▶")) onPlay(rec) else onDelete(rec)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
