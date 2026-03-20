package com.harvest.rns.ui.incoming

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.harvest.rns.data.model.HarvestRecord
import com.harvest.rns.databinding.ItemHarvestRecordBinding
import com.harvest.rns.ui.main.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

class IncomingRecordsAdapter(private val viewModel: MainViewModel) :
    ListAdapter<HarvestRecord, IncomingRecordsAdapter.ViewHolder>(DiffCallback()) {

    private var fullList: List<HarvestRecord> = emptyList()

    fun setRecords(list: List<HarvestRecord>) {
        fullList = list
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemHarvestRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), viewModel)

    class ViewHolder(private val b: ItemHarvestRecordBinding) : RecyclerView.ViewHolder(b.root) {
        private val timeFmt   = SimpleDateFormat("HH:mm", Locale.US)
        private val parseFmts = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        )

        fun bind(record: HarvestRecord, viewModel: MainViewModel) {
            // Split raw CSV directly — field positions are fixed regardless of schema:
            // [0]=id [1]=harvester_id [2]=block_id [3]=ripe [4]=empty
            // [5]=lat [6]=lon [7]=timestamp [8]=photo
            val f = record.rawCsv.split(",")

            val harvesterId  = f.getOrElse(1) { record.harvesterId }.trim()
            val blockId      = f.getOrElse(2) { record.blockId }.trim()
            val ripe         = f.getOrElse(3) { "0" }.trim().toIntOrNull() ?: 0
            val empty        = f.getOrElse(4) { "0" }.trim().toIntOrNull() ?: 0
            val timestamp    = f.getOrElse(7) { record.timestamp }.trim()

            // Use nickname if set, otherwise raw harvester_id from CSV
            val nick = viewModel.getNickname(harvesterId)
            b.harvesterIdText.text  = nick ?: harvesterId
            b.blockIdText.text      = blockId
            b.totalBunchesText.text = (ripe + empty).toString()
            b.timestampText.text    = formatTime(timestamp)
        }

        private fun formatTime(ts: String): String {
            for (fmt in parseFmts) {
                try {
                    val d = fmt.parse(ts)
                    if (d != null) return timeFmt.format(d)
                } catch (_: Exception) {}
            }
            // Fallback: last 5 chars of timestamp
            return ts.trim().takeLast(8).take(5)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<HarvestRecord>() {
        override fun areItemsTheSame(a: HarvestRecord, b: HarvestRecord) = a.localId == b.localId
        override fun areContentsTheSame(a: HarvestRecord, b: HarvestRecord) = a == b
    }
}
