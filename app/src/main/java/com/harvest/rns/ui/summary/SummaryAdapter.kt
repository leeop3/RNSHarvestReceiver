package com.harvest.rns.ui.summary

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.harvest.rns.data.model.HarvesterSummary
import com.harvest.rns.databinding.ItemHarvesterSummaryBinding
import com.harvest.rns.ui.main.MainViewModel

class SummaryAdapter(private val viewModel: MainViewModel) :
    ListAdapter<HarvesterSummary, SummaryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemHarvesterSummaryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), position + 1, viewModel)

    class ViewHolder(private val b: ItemHarvesterSummaryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(s: HarvesterSummary, rank: Int, viewModel: MainViewModel) {
            val nick = viewModel.getNickname(s.harvesterId)
            b.harvesterIdText.text  = nick ?: s.harvesterId
            b.ripeBunchesText.text  = s.totalRipeBunches.toString()
            b.emptyBunchesText.text = s.totalEmptyBunches.toString()
            b.totalBunchesText.text = s.totalBunches.toString()
            b.reportCountText.text  = "${s.reportCount} report(s)"
            b.rankLabel.text        = "#$rank"

            val eff = if (s.totalBunches > 0)
                (s.totalRipeBunches.toFloat() / s.totalBunches * 100).toInt() else 0
            b.efficiencyBar.progress = eff
            b.efficiencyText.text    = "$eff% ripe"
            b.efficiencyText.setTextColor(when {
                eff >= 80 -> 0xFF2E7D32.toInt()
                eff >= 60 -> 0xFFF9A825.toInt()
                else      -> 0xFFC62828.toInt()
            })
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<HarvesterSummary>() {
        override fun areItemsTheSame(a: HarvesterSummary, b: HarvesterSummary) =
            a.harvesterId == b.harvesterId && a.reportDate == b.reportDate
        override fun areContentsTheSame(a: HarvesterSummary, b: HarvesterSummary) = a == b
    }
}
