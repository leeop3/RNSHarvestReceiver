package com.harvest.rns.ui.nodes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.harvest.rns.data.model.DiscoveredNode
import com.harvest.rns.databinding.ItemDiscoveredNodeBinding
import com.harvest.rns.utils.DateUtils

class NodesAdapter(private val onLongClick: (DiscoveredNode) -> Unit) :
    ListAdapter<DiscoveredNode, NodesAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDiscoveredNodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onLongClick)
    }

    class ViewHolder(private val b: ItemDiscoveredNodeBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(node: DiscoveredNode, onLongClick: (DiscoveredNode) -> Unit) {
            b.nodeNameText.text    = node.label
            b.nodeHashText.text    = node.destinationHash
            b.nodeAspectText.text  = node.aspect ?: "Unknown aspect"
            b.nodeHopsText.text    = if (node.hops == 0) "Direct" else "${node.hops} hop(s)"
            b.nodeLastSeenText.text = "Last: ${DateUtils.millisToTimeString(node.lastSeen)}"
            b.nodeCountText.text   = "×${node.announceCount}"

            // Colour by hop count
            val hopColor = when (node.hops) {
                0    -> 0xFF2E7D32.toInt()  // direct = bright green
                1    -> 0xFF558B2F.toInt()  // 1 hop
                2    -> 0xFFF9A825.toInt()  // 2 hops = amber
                else -> 0xFF757575.toInt()  // far = grey
            }
            b.nodeHopsText.setTextColor(hopColor)
            b.hopIndicator.setBackgroundColor(hopColor)

            b.root.setOnLongClickListener { onLongClick(node); true }

            // Show LXMF badge
            b.lxmfBadge.visibility =
                if (node.aspect?.contains("lxmf") == true)
                    android.view.View.VISIBLE
                else android.view.View.GONE
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DiscoveredNode>() {
        override fun areItemsTheSame(a: DiscoveredNode, b: DiscoveredNode) =
            a.destinationHash == b.destinationHash
        override fun areContentsTheSame(a: DiscoveredNode, b: DiscoveredNode) =
            a == b
    }
}
