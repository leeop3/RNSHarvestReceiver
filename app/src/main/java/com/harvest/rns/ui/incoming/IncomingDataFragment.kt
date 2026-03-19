package com.harvest.rns.ui.incoming

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.harvest.rns.databinding.FragmentIncomingDataBinding
import com.harvest.rns.ui.main.MainViewModel
import com.harvest.rns.utils.DateUtils
import java.text.SimpleDateFormat
import java.util.*

class IncomingDataFragment : Fragment() {

    private var _binding: FragmentIncomingDataBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: IncomingRecordsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentIncomingDataBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = IncomingRecordsAdapter(viewModel)
        binding.recyclerView.adapter = adapter

        // ── Date navigator ────────────────────────────────────────────────────
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
            // Refresh when a nickname changes
            viewModel.selectedDate.value?.let { loadForDate(it) }
        }
    }

    private fun loadForDate(date: String) {
        viewModel.getRecordsByDate(date).observe(viewLifecycleOwner) { records ->
            adapter.setRecords(records)
            binding.emptyState.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            binding.recordCountText.text  = "${records.size} record(s)"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
