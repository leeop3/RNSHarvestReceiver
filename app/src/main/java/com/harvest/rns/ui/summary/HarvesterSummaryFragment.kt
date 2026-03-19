package com.harvest.rns.ui.summary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.harvest.rns.databinding.FragmentHarvesterSummaryBinding
import com.harvest.rns.ui.main.MainViewModel
import com.harvest.rns.utils.DateUtils

class HarvesterSummaryFragment : Fragment() {

    private var _binding: FragmentHarvesterSummaryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SummaryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentHarvesterSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SummaryAdapter(viewModel)
        binding.recyclerView.adapter = adapter

        // ── Date navigator (shared with Incoming tab) ─────────────────────────
        binding.prevDateBtn.setOnClickListener { viewModel.goToPreviousDate() }
        binding.nextDateBtn.setOnClickListener {
            if (viewModel.canGoForward()) viewModel.goToNextDate()
        }

        viewModel.selectedDate.observe(viewLifecycleOwner) { date ->
            binding.dateLabel.text = DateUtils.formatDateLabel(date)
            binding.nextDateBtn.alpha = if (viewModel.canGoForward()) 1f else 0.3f
            loadForDate(date)
        }

        viewModel.nickUpdate.observe(viewLifecycleOwner) {
            viewModel.selectedDate.value?.let { loadForDate(it) }
        }
    }

    private fun loadForDate(date: String) {
        viewModel.getSummaryForDate(date).observe(viewLifecycleOwner) { summaries ->
            adapter.submitList(summaries)
            binding.emptyState.visibility = if (summaries.isEmpty()) View.VISIBLE else View.GONE

            val totalRipe  = summaries.sumOf { it.totalRipeBunches }
            val totalEmpty = summaries.sumOf { it.totalEmptyBunches }
            val reports    = summaries.sumOf { it.reportCount }

            binding.totalRipeText.text    = "Ripe: $totalRipe"
            binding.totalEmptyText.text   = "Empty: $totalEmpty"
            binding.totalReportsText.text = "Reports: $reports"
            binding.harvesterCountText.text = "${summaries.size} harvester(s)"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
