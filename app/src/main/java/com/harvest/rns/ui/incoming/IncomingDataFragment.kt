package com.harvest.rns.ui.incoming

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.harvest.rns.databinding.FragmentIncomingDataBinding
import com.harvest.rns.ui.main.MainViewModel

class IncomingDataFragment : Fragment() {

    private var _binding: FragmentIncomingDataBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: IncomingRecordsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentIncomingDataBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = IncomingRecordsAdapter()
        binding.recyclerView.adapter = adapter

        // Search/filter
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        viewModel.allRecords.observe(viewLifecycleOwner) { records ->
            adapter.submitList(records)
            binding.emptyState.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            binding.recordCountText.text = "${records.size} record(s)"
        }

        viewModel.recordCount.observe(viewLifecycleOwner) { count ->
            binding.recordCountText.text = "$count record(s) received"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
