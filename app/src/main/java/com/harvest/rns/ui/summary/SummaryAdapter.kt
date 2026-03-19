package com.harvest.rns.ui.summary

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.harvest.rns.data.model.HarvesterSummary
import com.harvest.rns.databinding.ItemHarvesterSummaryBinding

class SummaryAdapter :
    ListAdapter<HarvesterSummary, SummaryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHarvesterSummaryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemHarvesterSummaryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(summary: HarvesterSummary) {
            binding.harvesterIdText.text   = summary.harvesterId
            binding.ripeBunchesText.text   = summary.totalRipeBunches.toString()
            binding.emptyBunchesText.text  = summary.totalEmptyBunches.toString()
            binding.totalBunchesText.text  = summary.totalBunches.toString()
            binding.reportCountText.text   = "${summary.reportCount} report(s)"
            binding.rankLabel.text         = "#${adapterPosition + 1}"

            // Efficiency ratio: ripe / total
            val efficiency = if (summary.totalBunches > 0) {
                (summary.totalRipeBunches.toFloat() / summary.totalBunches * 100).toInt()
            } else 0

            binding.efficiencyBar.progress = efficiency
            binding.efficiencyText.text    = "$efficiency% ripe"

            // Color code efficiency
            val color = when {
                efficiency >= 80 -> 0xFF2E7D32.toInt()  // green
                efficiency >= 60 -> 0xFFF9A825.toInt()  // amber
                else             -> 0xFFC62828.toInt()  // red
            }
            binding.efficiencyText.setTextColor(color)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<HarvesterSummary>() {
        override fun areItemsTheSame(old: HarvesterSummary, new: HarvesterSummary) =
            old.harvesterId == new.harvesterId && old.reportDate == new.reportDate
        override fun areContentsTheSame(old: HarvesterSummary, new: HarvesterSummary) =
            old == new
    }
}
