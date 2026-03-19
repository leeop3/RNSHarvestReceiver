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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // Service binding
    private var rnsService: RNSReceiverService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RNSReceiverService.LocalBinder
            rnsService = binder?.getService()
            isServiceBound = true
            rnsService?.let { viewModel.bindService(it) }
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.unbindService()
            rnsService = null
            isServiceBound = false
        }
    }

    // Bluetooth permissions launcher
    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            showDevicePicker()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

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
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    // ─── Menu ─────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_connect    -> { requestBtPermissionsAndConnect(); true }
            R.id.action_disconnect -> { disconnectRNode(); true }
            R.id.action_clear_data -> { confirmClearData(); true }
            else                   -> super.onOptionsItemSelected(item)
        }
    }

    // ─── ViewPager / Tabs ─────────────────────────────────────────────────────

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "📡 Incoming"
                1 -> "📊 Summary"
                2 -> "🔍 Nodes"
                3 -> "📻 Radio"
                else -> ""
            }
        }.attach()
    }

    // ─── Status Bar ───────────────────────────────────────────────────────────

    private fun setupStatusBar() {
        viewModel.serviceStatus.observe(this) { status ->
            binding.statusText.text = status
        }

        viewModel.connectionStatus.observe(this) { status ->
            val (text, color) = when (status) {
                is ConnectionStatus.Connected ->
                    "Connected: ${status.deviceName}" to getColor(R.color.status_connected)
                is ConnectionStatus.Connecting ->
                    "Connecting..." to getColor(R.color.status_connecting)
                is ConnectionStatus.Disconnected ->
                    "Not connected" to getColor(R.color.status_disconnected)
                is ConnectionStatus.Error ->
                    "Error: ${status.message}" to getColor(R.color.status_error)
            }
            binding.connectionIndicator.setBackgroundColor(color)
            binding.statusText.text = text
        }

        viewModel.messageCount.observe(this) { count ->
            binding.messageCountText.text = "Received: $count"
        }

        viewModel.duplicateCount.observe(this) { count ->
            if (count > 0) binding.duplicateCountText.text = "Dupes ignored: $count"
        }
    }

    // ─── Service Management ───────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, RNSReceiverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        CoroutineScope(Dispatchers.Main).launch {
            rnsService?.getBluetoothState()?.collect { btState ->
                val connStatus = when (btState) {
                    is BluetoothRNodeManager.BtState.Connected ->
                        ConnectionStatus.Connected(btState.device.name ?: "RNode", btState.device.address)
                    is BluetoothRNodeManager.BtState.Connecting ->
                        ConnectionStatus.Connecting(btState.device.name ?: "RNode")
                    is BluetoothRNodeManager.BtState.Reconnecting ->
                        ConnectionStatus.Connecting("RNode (reconnecting)")
                    is BluetoothRNodeManager.BtState.Error ->
                        ConnectionStatus.Error(btState.message)
                    else -> ConnectionStatus.Disconnected
                }
                viewModel.updateConnectionStatus(connStatus)
            }
        }
    }

    // ─── Bluetooth Device Selection ───────────────────────────────────────────

    private fun requestBtPermissionsAndConnect() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) showDevicePicker()
        else btPermissionLauncher.launch(perms)
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        val btManager = getSystemService(BluetoothManager::class.java)
        val btAdapter = btManager?.adapter

        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is not enabled", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices: Set<BluetoothDevice> = btAdapter.bondedDevices ?: emptySet()

        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found.\nPair your RNode in Settings first.", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames  = pairedDevices.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()
        val deviceList   = pairedDevices.toList()

        AlertDialog.Builder(this)
            .setTitle("Select RNode Device")
            .setItems(deviceNames) { _, which ->
                val device = deviceList[which]
                connectToDevice(device)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val intent = Intent(this, RNSReceiverService::class.java).apply {
            action = RNSReceiverService.ACTION_CONNECT
            putExtra(RNSReceiverService.EXTRA_DEVICE, device)
        }
        startService(intent)
        Toast.makeText(this, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectRNode() {
        val intent = Intent(this, RNSReceiverService::class.java).apply {
            action = RNSReceiverService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Data")
            .setMessage("This will permanently delete all received harvest records. Continue?")
            .setPositiveButton("Clear") { _, _ ->
                viewModel.clearAllData()
                Toast.makeText(this, "All data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
