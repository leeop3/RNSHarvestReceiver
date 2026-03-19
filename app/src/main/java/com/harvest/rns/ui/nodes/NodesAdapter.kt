package com.harvest.rns.ui.nodes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.harvest.rns.data.model.DiscoveredNode
import com.harvest.rns.databinding.ItemDiscoveredNodeBinding
import com.harvest.rns.ui.main.MainViewModel
import com.harvest.rns.utils.DateUtils

class NodesAdapter(
    private val viewModel: MainViewModel,
    private val onTap: (DiscoveredNode) -> Unit,
    private val onLongClick: (DiscoveredNode) -> Unit
) : ListAdapter<DiscoveredNode, NodesAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemDiscoveredNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val node = getItem(position)
        holder.bind(node, viewModel)
        holder.itemView.setOnClickListener { onTap(node) }
        holder.itemView.setOnLongClickListener { onLongClick(node); true }
    }

    class ViewHolder(private val b: ItemDiscoveredNodeBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(node: DiscoveredNode, viewModel: MainViewModel) {
            // Prefer nickname from prefs over announce display name
            val nick = viewModel.getNickname(node.destinationHash)
            b.nodeNameText.text    = nick ?: node.label
            b.nodeHashText.text    = node.destinationHash
            b.nodeAspectText.text  = node.aspect ?: "Unknown aspect"
            b.nodeHopsText.text    = if (node.hops == 0) "Direct" else "${node.hops} hop(s)"
            b.nodeLastSeenText.text = DateUtils.millisToTimeString(node.lastSeen)
            b.nodeCountText.text   = "×${node.announceCount}"

            val hopColor = when (node.hops) {
                0    -> 0xFF2E7D32.toInt()
                1    -> 0xFF558B2F.toInt()
                2    -> 0xFFF9A825.toInt()
                else -> 0xFF757575.toInt()
            }
            b.nodeHopsText.setTextColor(hopColor)
            b.hopIndicator.setBackgroundColor(hopColor)

            b.lxmfBadge.visibility =
                if (node.aspect?.contains("lxmf") == true) android.view.View.VISIBLE
                else android.view.View.GONE

            // Show nickname badge if set
            b.nickBadge.visibility =
                if (viewModel.getNickname(node.destinationHash) != null) android.view.View.VISIBLE
                else android.view.View.GONE
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DiscoveredNode>() {
        override fun areItemsTheSame(a: DiscoveredNode, b: DiscoveredNode) =
            a.destinationHash == b.destinationHash
        override fun areContentsTheSame(a: DiscoveredNode, b: DiscoveredNode) = a == b
    }
}
