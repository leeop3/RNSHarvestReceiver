package com.harvest.rns.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayoutMediator
import com.harvest.rns.R
import com.harvest.rns.data.model.ConnectionStatus
import com.harvest.rns.databinding.ActivityMainBinding
import com.harvest.rns.network.RNSReceiverService
import com.harvest.rns.network.bluetooth.BluetoothRNodeManager
import com.harvest.rns.ui.settings.RadioSettingsDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var rnsService: RNSReceiverService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rnsService = (service as? RNSReceiverService.LocalBinder)?.getService()
            isServiceBound = true
            rnsService?.let { viewModel.bindService(it) }
            observeServiceState()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.unbindService(); rnsService = null; isServiceBound = false
        }
    }

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) showDevicePicker()
        else Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setupViewPager()
        setupStatusBar()
        startAndBindService()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) { unbindService(serviceConnection); isServiceBound = false }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_connect        -> { requestBtPermsAndConnect(); true }
        R.id.action_disconnect     -> { disconnectRNode(); true }
        R.id.action_radio_settings -> {
            RadioSettingsDialog().show(supportFragmentManager, RadioSettingsDialog.TAG); true
        }
        R.id.action_clear_data     -> { confirmClearData(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = MainPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = when (pos) {
                0 -> "📡 Incoming"
                1 -> "📊 Summary"
                2 -> "🔍 Nodes"
                else -> ""
            }
        }.attach()
    }

    private fun setupStatusBar() {
        viewModel.serviceStatus.observe(this) { binding.statusText.text = it }
        viewModel.connectionStatus.observe(this) { status ->
            val (text, color) = when (status) {
                is ConnectionStatus.Connected    -> "● ${status.deviceName}" to getColor(R.color.status_connected)
                is ConnectionStatus.Connecting   -> "◌ Connecting…"         to getColor(R.color.status_connecting)
                is ConnectionStatus.Disconnected -> "○ Not connected"        to getColor(R.color.status_disconnected)
                is ConnectionStatus.Error        -> "✕ ${status.message}"   to getColor(R.color.status_error)
            }
            binding.connectionIndicator.setBackgroundColor(color)
            binding.statusText.text = text
        }
        viewModel.messageCount.observe(this)   { binding.messageCountText.text = "Rcvd: $it" }
        viewModel.duplicateCount.observe(this) { if (it > 0) binding.duplicateCountText.text = "Dup: $it" }
    }

    private fun startAndBindService() {
        val intent = Intent(this, RNSReceiverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        CoroutineScope(Dispatchers.Main).launch {
            rnsService?.getBluetoothState()?.collect { state ->
                viewModel.updateConnectionStatus(when (state) {
                    is BluetoothRNodeManager.BtState.Connected    -> ConnectionStatus.Connected(state.device.name ?: "RNode", state.device.address)
                    is BluetoothRNodeManager.BtState.Connecting   -> ConnectionStatus.Connecting(state.device.name ?: "RNode")
                    is BluetoothRNodeManager.BtState.Reconnecting -> ConnectionStatus.Connecting("RNode (reconnecting)")
                    is BluetoothRNodeManager.BtState.Error        -> ConnectionStatus.Error(state.message)
                    else -> ConnectionStatus.Disconnected
                })
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestBtPermsAndConnect() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)

        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            showDevicePicker()
        else
            btPermissionLauncher.launch(perms)
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is not enabled", Toast.LENGTH_SHORT).show(); return
        }
        val paired = adapter.bondedDevices?.toList() ?: emptyList()
        if (paired.isEmpty()) {
            Toast.makeText(this, "No paired devices found. Pair your RNode in Settings.", Toast.LENGTH_LONG).show(); return
        }
        AlertDialog.Builder(this)
            .setTitle("Select RNode Device")
            .setItems(paired.map { "${it.name} (${it.address})" }.toTypedArray()) { _, i ->
                connectToDevice(paired[i])
            }
            .setNegativeButton("Cancel", null).show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        startService(Intent(this, RNSReceiverService::class.java).apply {
            action = RNSReceiverService.ACTION_CONNECT
            putExtra(RNSReceiverService.EXTRA_DEVICE, device)
        })
        Toast.makeText(this, "Connecting to ${device.name}…", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectRNode() {
        startService(Intent(this, RNSReceiverService::class.java).apply {
            action = RNSReceiverService.ACTION_DISCONNECT
        })
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Data")
            .setMessage("Delete all received harvest records?")
            .setPositiveButton("Clear") { _, _ ->
                viewModel.clearAllData()
                Toast.makeText(this, "All data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
