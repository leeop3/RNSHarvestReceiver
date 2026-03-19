package com.harvest.rns.ui.summary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.harvest.rns.databinding.FragmentHarvesterSummaryBinding
import com.harvest.rns.ui.main.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

class HarvesterSummaryFragment : Fragment() {

    private var _binding: FragmentHarvesterSummaryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SummaryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHarvesterSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SummaryAdapter()
        binding.recyclerView.adapter = adapter

        // Date picker spinner
        viewModel.availableDates.observe(viewLifecycleOwner) { dates ->
            val displayDates = dates.map { formatDateLabel(it) }
            val spinnerAdapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                displayDates
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            binding.dateSpinner.adapter = spinnerAdapter

            // Default to today
            val today = todayDate()
            val todayIdx = dates.indexOf(today)
            if (todayIdx >= 0) binding.dateSpinner.setSelection(todayIdx)

            binding.dateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val selectedDate = if (pos < dates.size) dates[pos] else today
                    loadSummaryForDate(selectedDate)
                    binding.selectedDateLabel.text = displayDates.getOrNull(pos) ?: selectedDate
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            if (dates.isNotEmpty()) {
                val initDate = if (dates.contains(today)) today else dates.first()
                loadSummaryForDate(initDate)
                binding.selectedDateLabel.text = formatDateLabel(initDate)
            } else {
                binding.emptyState.visibility = View.VISIBLE
            }
        }
    }

    private fun loadSummaryForDate(date: String) {
        viewModel.getSummaryForDate(date).observe(viewLifecycleOwner) { summaries ->
            adapter.submitList(summaries)
            binding.emptyState.visibility = if (summaries.isEmpty()) View.VISIBLE else View.GONE

            // Aggregate totals
            val totalRipe  = summaries.sumOf { it.totalRipeBunches }
            val totalEmpty = summaries.sumOf { it.totalEmptyBunches }
            val totalReports = summaries.sumOf { it.reportCount }

            binding.totalRipeText.text    = "Total Ripe: $totalRipe"
            binding.totalEmptyText.text   = "Total Empty: $totalEmpty"
            binding.totalReportsText.text = "Total Reports: $totalReports"
            binding.harvesterCountText.text = "${summaries.size} harvester(s) active"
        }
    }

    private fun formatDateLabel(date: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val d = sdf.parse(date)
            val out = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
            out.format(d ?: return date)
        } catch (_: Exception) { date }
    }

    private fun todayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
