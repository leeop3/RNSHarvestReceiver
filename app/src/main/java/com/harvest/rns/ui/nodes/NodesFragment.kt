package com.harvest.rns.ui.nodes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.harvest.rns.R
import com.harvest.rns.data.model.ConnectionStatus
import com.harvest.rns.data.model.DiscoveredNode
import com.harvest.rns.databinding.FragmentNodesBinding
import com.harvest.rns.network.RNSReceiverService
import com.harvest.rns.ui.main.MainViewModel
import kotlinx.coroutines.launch

class NodesFragment : Fragment() {

    private var _binding: FragmentNodesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: NodesAdapter
    private val logLines = mutableListOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentNodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = NodesAdapter(
            viewModel = viewModel,
            onTap     = { node -> showNodeProfile(node) },
            onLongClick = { node -> copyToClipboard(node.destinationHash, "RNS Address") }
        )
        binding.recyclerView.adapter = adapter

        // ── Own address ───────────────────────────────────────────────────────
        viewModel.ownAddress.observe(viewLifecycleOwner) { addr ->
            binding.ownAddressText.text = addr.chunked(4).joinToString(" ")
        }
        binding.copyAddressBtn.setOnClickListener {
            copyToClipboard(viewModel.ownAddress.value ?: "", "Receiver Address")
        }

        // ── Discovered nodes ──────────────────────────────────────────────────
        binding.clearBtn.setOnClickListener { viewModel.clearDiscoveredNodes() }

        viewModel.discoveredNodeList.observe(viewLifecycleOwner) { nodes ->
            adapter.submitList(nodes)
            binding.emptyState.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
            binding.nodeCountText.text    = "${nodes.size} node(s) discovered"
        }

        viewModel.nickUpdate.observe(viewLifecycleOwner) {
            adapter.notifyDataSetChanged()
        }

        // ── Connection hint ───────────────────────────────────────────────────
        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.connectionHintText.visibility =
                if (status is ConnectionStatus.Connected) View.GONE else View.VISIBLE
        }

        // ── Raw frame log ─────────────────────────────────────────────────────
        binding.clearLogBtn.setOnClickListener {
            logLines.clear(); binding.rawLogText.text = "(cleared)"
        }
        viewLifecycleOwner.lifecycleScope.launch {
            RNSReceiverService.rawFrameLog.collect { line ->
                logLines.add(line)
                if (logLines.size > 30) logLines.removeAt(0)
                binding.rawLogText.text = logLines.joinToString("\n")
                binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    // ── Node profile popup ────────────────────────────────────────────────────

    private fun showNodeProfile(node: DiscoveredNode) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_node_profile, null)

        val hashText   = dialogView.findViewById<TextView>(R.id.profileHashText)
        val aspectText = dialogView.findViewById<TextView>(R.id.profileAspectText)
        val hopsText   = dialogView.findViewById<TextView>(R.id.profileHopsText)
        val countText  = dialogView.findViewById<TextView>(R.id.profileCountText)
        val nickEdit   = dialogView.findViewById<EditText>(R.id.profileNickEdit)

        hashText.text   = node.destinationHash
        aspectText.text = node.aspect ?: "Unknown"
        hopsText.text   = if (node.hops == 0) "Direct connection" else "${node.hops} hop(s)"
        countText.text  = "${node.announceCount} announce(s)"

        val currentNick = viewModel.getNickname(node.destinationHash) ?: node.displayName ?: ""
        nickEdit.setText(currentNick)

        AlertDialog.Builder(ctx, R.style.RadioDialogTheme)
            .setTitle("Node Profile")
            .setView(dialogView)
            .setPositiveButton("Save Nickname") { _, _ ->
                val nick = nickEdit.text.toString()
                viewModel.setNickname(node.destinationHash, nick)
                val msg = if (nick.isBlank()) "Nickname cleared" else "Nickname set: $nick"
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Copy Address") { _, _ ->
                copyToClipboard(node.destinationHash, "RNS Address")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(text: String, label: String) {
        if (text.isEmpty()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
