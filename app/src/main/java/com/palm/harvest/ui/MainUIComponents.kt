package com.palm.harvest.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.palm.harvest.R
import com.palm.harvest.data.*
import com.palm.harvest.network.RNSReceiverService
import java.text.SimpleDateFormat
import java.util.*

class MainPagerAdapter(activity: FragmentActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3
    override fun createFragment(position: Int) = when(position) {
        0 -> IncomingFragment()
        1 -> SummaryFragment()
        else -> NodesFragment()
    }
}

class IncomingAdapter : ListAdapter<HarvestRecord, IncomingAdapter.VH>(Diff()) {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val t: TextView? = v.findViewById(R.id.txtHarvester)
        val b: TextView? = v.findViewById(R.id.txtBlock)
        val s: TextView? = v.findViewById(R.id.txtStats)
        val img: ImageView? = v.findViewById(R.id.imgThumb)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false))
    override fun onBindViewHolder(h: VH, p: Int) {
        val i = getItem(p)
        h.t?.text = "Harvester: ${i.harvesterId}"
        h.b?.text = "Block: ${i.blockId}"
        h.s?.text = "Total: ${i.ripeBunches + i.emptyBunches} | Time: ${i.timestamp}"
        
        // Handle incoming Base64 image
        if (i.photoFile.length > 100) {
            try {
                val bytes = Base64.decode(i.photoFile, Base64.DEFAULT)
                h.img?.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } catch (e: Exception) { h.img?.setImageResource(android.R.drawable.ic_menu_gallery) }
        } else {
            h.img?.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }
    class Diff : DiffUtil.ItemCallback<HarvestRecord>() {
        override fun areItemsTheSame(o: HarvestRecord, n: HarvestRecord) = o.localId == n.localId
        override fun areContentsTheSame(o: HarvestRecord, n: HarvestRecord) = o == n
    }
}

class SummaryAdapter : ListAdapter<BlockSummary, SummaryAdapter.VH>(Diff()) {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val t: TextView? = v.findViewById(R.id.txtHarvester)
        val b: TextView? = v.findViewById(R.id.txtBlock)
        val s: TextView? = v.findViewById(R.id.txtStats)
        val img: ImageView? = v.findViewById(R.id.imgThumb)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false))
    override fun onBindViewHolder(h: VH, p: Int) {
        val i = getItem(p)
        h.t?.text = "Block: ${i.blockId}"
        h.b?.text = "Total Harvest: ${i.totalBunches}"
        h.s?.text = "Ripe: ${i.totalRipe} | Empty: ${i.totalEmpty}"
        h.img?.visibility = View.GONE // No image needed in summary
    }
    class Diff : DiffUtil.ItemCallback<BlockSummary>() {
        override fun areItemsTheSame(o: BlockSummary, n: BlockSummary) = o.blockId == n.blockId
        override fun areContentsTheSame(o: BlockSummary, n: BlockSummary) = o == n
    }
}

class NodeAdapter(private val onLocalClick: (String) -> Unit) : RecyclerView.Adapter<NodeAdapter.VH>() {
    private var localAddr: String = "Waiting for RNS..."
    private var nodes: List<DiscoveredNode> = emptyList()

    fun setLocalAddress(addr: String?) { 
        if (addr != null && addr != this.localAddr) { this.localAddr = addr; notifyItemChanged(0) }
    }
    fun setDiscoveredNodes(newList: List<DiscoveredNode>?) { 
        if (newList != null) { this.nodes = newList; notifyDataSetChanged() }
    }

    override fun getItemCount() = nodes.size + 1
    override fun getItemViewType(p: Int) = if (p == 0) 0 else 1

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_harvest, p, false))
    override fun onBindViewHolder(h: VH, p: Int) {
        h.img?.visibility = View.GONE // Hide image box for nodes
        if (p == 0) {
            h.t?.text = "THIS RECEIVER (Tap for QR)"
            h.b?.text = "Address: $localAddr"
            h.s?.text = "Config Harvesters to this address"
            h.itemView.setOnClickListener { if (!localAddr.contains("Waiting")) onLocalClick(localAddr) }
        } else {
            val i = nodes[p - 1]; val df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            h.t?.text = i.label
            h.b?.text = "Address: ${i.shortAddress} | Anns: ${i.announceCount}"
            h.s?.text = "Last Heard: ${df.format(Date(i.lastSeen))}"
            h.itemView.setOnClickListener(null)
        }
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val t: TextView? = v.findViewById(R.id.txtHarvester)
        val b: TextView? = v.findViewById(R.id.txtBlock)
        val s: TextView? = v.findViewById(R.id.txtStats)
        val img: ImageView? = v.findViewById(R.id.imgThumb)
    }
}

class IncomingFragment : Fragment(R.layout.fragment_incoming) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewIncoming)
        val adp = IncomingAdapter()
        rv?.layoutManager = LinearLayoutManager(requireContext())
        rv?.adapter = adp
        AppDatabase.getDatabase(requireContext().applicationContext).harvestDao().getAllReports().observe(viewLifecycleOwner) { data ->
            data?.let { rv?.post { adp.submitList(it) } }
        }
    }
}

class SummaryFragment : Fragment(R.layout.fragment_summary) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewSummary)
        val adp = SummaryAdapter()
        rv?.layoutManager = LinearLayoutManager(requireContext())
        rv?.adapter = adp
        
        val db = AppDatabase.getDatabase(requireContext().applicationContext)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        
        // Add Grand Total Header
        val headerView = TextView(requireContext()).apply {
            textSize = 22f
            setTextColor(Color.parseColor("#1B5E20"))
            setPadding(40, 40, 40, 20)
            text = "Grand Total Today: 0 Bunches"
            setTypeface(null, Typeface.BOLD)
        }
        
        val parentLayout = view as? FrameLayout
        parentLayout?.addView(headerView, 0)
        (rv?.layoutParams as? FrameLayout.LayoutParams)?.topMargin = 140

        db.harvestDao().getGrandTotalForDate(today).observe(viewLifecycleOwner) { total ->
            headerView.text = "Grand Total Today: ${total ?: 0} Bunches"
        }

        db.harvestDao().getBlockSummariesForDate(today).observe(viewLifecycleOwner) { data ->
            data?.let { rv?.post { adp.submitList(it) } }
        }
    }
}

class NodesFragment : Fragment(R.layout.fragment_nodes) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewNodes)
        val adp = NodeAdapter { addr -> showQr(addr) }
        rv?.layoutManager = LinearLayoutManager(requireContext())
        rv?.adapter = adp
        
        RNSReceiverService.localAddress.observe(viewLifecycleOwner) { addr -> rv?.post { adp.setLocalAddress(addr) } }
        AppDatabase.getDatabase(requireContext().applicationContext).harvestDao().getAllNodes().observe(viewLifecycleOwner) { data ->
            rv?.post { adp.setDiscoveredNodes(data) }
        }
    }

    private fun showQr(text: String) {
        try {
            val bm = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val img = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            for (x in 0 until 512) for (y in 0 until 512) img.setPixel(x, y, if (bm.get(x, y)) Color.BLACK else Color.WHITE)
            val iv = ImageView(context).apply { setPadding(40, 40, 40, 40); setImageBitmap(img) }
            AlertDialog.Builder(requireContext()).setTitle("Receiver Address").setView(iv).setPositiveButton("Close", null).show()
        } catch (e: Exception) { Toast.makeText(context, "QR Error", Toast.LENGTH_SHORT).show() }
    }
}