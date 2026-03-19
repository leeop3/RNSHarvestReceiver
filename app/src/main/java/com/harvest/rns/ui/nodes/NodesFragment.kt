package com.harvest.rns.ui.nodes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.harvest.rns.databinding.FragmentNodesBinding
import com.harvest.rns.ui.main.MainViewModel

class NodesFragment : Fragment() {

    private var _binding: FragmentNodesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: NodesAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = NodesAdapter { node ->
            // Long-tap copies address to clipboard
            val clip = ClipData.newPlainText("RNS Address", node.destinationHash)
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Address copied: ${node.destinationHash}", Toast.LENGTH_SHORT).show()
        }
        binding.recyclerView.adapter = adapter

        binding.clearBtn.setOnClickListener {
            viewModel.clearDiscoveredNodes()
        }

        viewModel.discoveredNodeList.observe(viewLifecycleOwner) { nodes ->
            adapter.submitList(nodes)
            binding.emptyState.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
            binding.nodeCountText.text    = "${nodes.size} node(s) discovered"
        }

        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.connectionHintText.visibility =
                if (status is com.harvest.rns.data.model.ConnectionStatus.Connected)
                    View.GONE else View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
