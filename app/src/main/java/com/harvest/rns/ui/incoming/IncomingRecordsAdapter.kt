package com.harvest.rns.ui.incoming

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.harvest.rns.data.model.HarvestRecord
import com.harvest.rns.databinding.ItemHarvestRecordBinding
import java.text.SimpleDateFormat
import java.util.*

class IncomingRecordsAdapter :
    ListAdapter<HarvestRecord, IncomingRecordsAdapter.ViewHolder>(DiffCallback()) {

    // Keep a full copy so filter() can restore it
    private var fullList: List<HarvestRecord> = emptyList()

    /**
     * Submit a new complete list, saving a copy for filtering.
     * Renamed to setRecords() to avoid JVM signature clash with
     * ListAdapter.submitList(MutableList?) which erases to the same type.
     */
    fun setRecords(list: List<HarvestRecord>) {
        fullList = list
        submitList(list)
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            submitList(fullList)
        } else {
            val filtered = fullList.filter { record ->
                record.harvesterId.lowercase().contains(q) ||
                record.blockId.lowercase().contains(q) ||
                record.externalId.lowercase().contains(q) ||
                record.timestamp.contains(q)
            }
            submitList(filtered)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHarvestRecordBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemHarvestRecordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val displayFmt = SimpleDateFormat("dd MMM yyyy\nHH:mm:ss", Locale.US)
        private val parseFmts = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        )

        fun bind(record: HarvestRecord) {
            binding.harvesterIdText.text  = record.harvesterId
            binding.blockIdText.text      = record.blockId
            binding.ripeBunchesText.text  = record.ripeBunches.toString()
            binding.emptyBunchesText.text = record.emptyBunches.toString()
            binding.locationText.text     = "%.5f, %.5f".format(record.latitude, record.longitude)
            binding.timestampText.text    = formatTimestamp(record.timestamp)
            binding.recordIdText.text     = "#${record.externalId}"

            // Colour-code ripe bunch count
            binding.ripeBunchesText.setTextColor(
                when {
                    record.ripeBunches >= 20 -> 0xFF2E7D32.toInt()
                    record.ripeBunches >= 10 -> 0xFFF57F17.toInt()
                    else                     -> 0xFFC62828.toInt()
                }
            )

            binding.photoIndicator.visibility =
                if (record.photoFile.isNotBlank()) android.view.View.VISIBLE
                else android.view.View.GONE
        }

        private fun formatTimestamp(ts: String): String {
            for (fmt in parseFmts) {
                try {
                    val date = fmt.parse(ts)
                    if (date != null) return displayFmt.format(date)
                } catch (_: Exception) {}
            }
            return ts
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<HarvestRecord>() {
        override fun areItemsTheSame(old: HarvestRecord, new: HarvestRecord) =
            old.localId == new.localId
        override fun areContentsTheSame(old: HarvestRecord, new: HarvestRecord) =
            old == new
    }
}
