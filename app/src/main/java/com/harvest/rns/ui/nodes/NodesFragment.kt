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
import androidx.lifecycle.lifecycleScope
import com.harvest.rns.databinding.FragmentNodesBinding
import com.harvest.rns.data.model.ConnectionStatus
import com.harvest.rns.network.RNSReceiverService
import com.harvest.rns.ui.main.MainViewModel
import kotlinx.coroutines.launch

class NodesFragment : Fragment() {

    private var _binding: FragmentNodesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: NodesAdapter

    // Rolling log of raw frames for debugging
    private val logLines = mutableListOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = NodesAdapter { node ->
            copyToClipboard(node.destinationHash, "RNS Address")
        }
        binding.recyclerView.adapter = adapter

        // ── Own address ──────────────────────────────────────────────────
        viewModel.ownAddress.observe(viewLifecycleOwner) { addr ->
            // Format as groups of 4 for readability: 1a2b3c4d5e6f7a8b9c0d
            val formatted = addr.chunked(4).joinToString(" ")
            binding.ownAddressText.text = formatted
        }

        binding.copyAddressBtn.setOnClickListener {
            val addr = viewModel.ownAddress.value ?: return@setOnClickListener
            copyToClipboard(addr, "Receiver Address")
        }

        // ── Discovered nodes ─────────────────────────────────────────────
        binding.clearBtn.setOnClickListener { viewModel.clearDiscoveredNodes() }

        viewModel.discoveredNodeList.observe(viewLifecycleOwner) { nodes ->
            adapter.submitList(nodes)
            binding.emptyState.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
            binding.nodeCountText.text    = "${nodes.size} node(s) discovered"
        }

        // ── Connection hint ───────────────────────────────────────────────
        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.connectionHintText.visibility =
                if (status is ConnectionStatus.Connected) View.GONE else View.VISIBLE
        }

        // ── Raw frame log ─────────────────────────────────────────────────
        binding.clearLogBtn.setOnClickListener {
            logLines.clear()
            binding.rawLogText.text = "(cleared)"
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RNSReceiverService.rawFrameLog.collect { line ->
                logLines.add(line)
                if (logLines.size > 30) logLines.removeAt(0)
                binding.rawLogText.text = logLines.joinToString("\n")
                // Auto-scroll to bottom
                binding.logScrollView.post {
                    binding.logScrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

