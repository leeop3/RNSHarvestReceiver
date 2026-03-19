package com.harvest.rns.ui.main

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.harvest.rns.R
import com.harvest.rns.network.RNSReceiverService

/**
 * A standalone activity that lists paired Bluetooth devices and allows
 * the user to select one to connect as RNode.
 *
 * Can be launched independently of the picker dialog in MainActivity
 * (useful for future deep-link or widget integration).
 */
@SuppressLint("MissingPermission")
class BluetoothDevicePickerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_DEVICE = "result_device"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DeviceAdapter
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple layout built programmatically to avoid an extra layout file
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        val toolbar = androidx.appcompat.widget.Toolbar(this).apply {
            title = "Select RNode Device"
            setBackgroundColor(getColor(R.color.color_primary))
            setTitleTextColor(getColor(R.color.white))
        }
        root.addView(toolbar, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_action_bar_default_height_material)
        ))
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        emptyText = TextView(this).apply {
            text = "No paired Bluetooth devices found.\nPair your RNode in Android Settings first."
            setTextColor(getColor(R.color.text_secondary))
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(32, 64, 32, 0)
            visibility = View.GONE
        }
        root.addView(emptyText)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BluetoothDevicePickerActivity)
        }
        root.addView(recyclerView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)

        adapter = DeviceAdapter { device ->
            onDeviceSelected(device)
        }
        recyclerView.adapter = adapter

        loadPairedDevices()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadPairedDevices() {
        val btManager = getSystemService(BluetoothManager::class.java)
        val btAdapter: BluetoothAdapter? = btManager?.adapter

        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is not enabled", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val paired = btAdapter.bondedDevices?.toList() ?: emptyList()
        adapter.submitList(paired)

        if (paired.isEmpty()) {
            emptyText.visibility = View.VISIBLE
        }
    }

    @SuppressLint("MissingPermission")
    private fun onDeviceSelected(device: BluetoothDevice) {
        // Start the service with connect action
        val serviceIntent = Intent(this, RNSReceiverService::class.java).apply {
            action = RNSReceiverService.ACTION_CONNECT
            putExtra(RNSReceiverService.EXTRA_DEVICE, device)
        }
        startService(serviceIntent)

        Toast.makeText(this, "Connecting to ${device.name}…", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ─── Inner adapter ────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    inner class DeviceAdapter(private val onSelect: (BluetoothDevice) -> Unit) :
        ListAdapter<BluetoothDevice, DeviceAdapter.VH>(DeviceDiff()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val device = getItem(position)
            holder.text1.text = device.name ?: "Unknown Device"
            holder.text2.text = device.address
            holder.text1.setTextColor(getColor(R.color.accent_green))
            holder.text2.setTextColor(getColor(R.color.text_secondary))
            holder.itemView.setBackgroundColor(getColor(R.color.bg_card))
            holder.itemView.setOnClickListener { onSelect(device) }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val text1: TextView = view.findViewById(android.R.id.text1)
            val text2: TextView = view.findViewById(android.R.id.text2)
        }
    }

    class DeviceDiff : DiffUtil.ItemCallback<BluetoothDevice>() {
        @SuppressLint("MissingPermission")
        override fun areItemsTheSame(a: BluetoothDevice, b: BluetoothDevice) = a.address == b.address
        override fun areContentsTheSame(a: BluetoothDevice, b: BluetoothDevice) = a.address == b.address
    }
}
