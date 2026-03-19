package com.harvest.rns.network

import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.harvest.rns.R
import com.harvest.rns.data.db.HarvestDatabase
import com.harvest.rns.data.model.DiscoveredNode
import com.harvest.rns.data.model.RadioConfig
import com.harvest.rns.data.repository.HarvestRepository
import com.harvest.rns.network.bluetooth.BluetoothRNodeManager
import com.harvest.rns.network.lxmf.LxmfMessageParser
import com.harvest.rns.network.rns.RnsAnnounceParser
import com.harvest.rns.network.rns.RnsFrameDecoder
import com.harvest.rns.ui.main.MainActivity
import com.harvest.rns.utils.CsvParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Foreground Service that:
 * 1. Maintains the Bluetooth connection to the RNode device
 * 2. Continuously processes incoming RNS/LXMF frames
 * 3. Parses CSV harvest data and stores to Room DB
 * 4. Listens for RNS ANNOUNCE packets to discover network nodes
 * 5. Applies radio configuration to the RNode via KISS commands
 */
class RNSReceiverService : Service() {

    companion object {
        private const val TAG = "RNSReceiverService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "rns_receiver_channel"

        const val ACTION_CONNECT      = "com.harvest.rns.action.CONNECT"
        const val ACTION_DISCONNECT   = "com.harvest.rns.action.DISCONNECT"
        const val ACTION_APPLY_RADIO  = "com.harvest.rns.action.APPLY_RADIO"
        const val EXTRA_DEVICE        = "extra_bluetooth_device"
        const val EXTRA_RADIO_CONFIG  = "extra_radio_config"

        // ── Singleton state accessible from ViewModels ────────────────────────
        private val _messageCount    = MutableStateFlow(0)
        private val _duplicateCount  = MutableStateFlow(0)
        private val _lastMessageTime = MutableStateFlow<Long>(0)
        private val _serviceStatus   = MutableStateFlow("Stopped")
        private val _discoveredNodes = MutableStateFlow<Map<String, DiscoveredNode>>(emptyMap())
        private val _radioConfig     = MutableStateFlow(RadioConfig())

        val messageCount:    StateFlow<Int>                     = _messageCount
        val duplicateCount:  StateFlow<Int>                     = _duplicateCount
        val lastMessageTime: StateFlow<Long>                    = _lastMessageTime
        val serviceStatus:   StateFlow<String>                  = _serviceStatus
        val discoveredNodes: StateFlow<Map<String, DiscoveredNode>> = _discoveredNodes
        val radioConfig:     StateFlow<RadioConfig>             = _radioConfig
    }

    // ─── Binder ───────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): RNSReceiverService = this@RNSReceiverService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ─── Service Members ──────────────────────────────────────────────────────

    private lateinit var btManager: BluetoothRNodeManager
    private lateinit var repository: HarvestRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var frameProcessorJob: Job? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        btManager  = BluetoothRNodeManager(applicationContext)
        repository = HarvestRepository(HarvestDatabase.getInstance(applicationContext).harvestDao())

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for RNode connection..."))

        observeBluetoothState()
        startFrameProcessor()

        _serviceStatus.value = "Running — not connected"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DEVICE)
                }
                device?.let { btManager.connect(it) }
            }
            ACTION_DISCONNECT -> btManager.disconnect()

            ACTION_APPLY_RADIO -> {
                val freq   = intent.getLongExtra("freq", _radioConfig.value.frequencyHz)
                val bw     = intent.getIntExtra("bw",   _radioConfig.value.bandwidthHz)
                val sf     = intent.getIntExtra("sf",   _radioConfig.value.spreadingFactor)
                val cr     = intent.getIntExtra("cr",   _radioConfig.value.codingRate)
                val txpwr  = intent.getIntExtra("txpwr",_radioConfig.value.txPower)
                val config = RadioConfig(freq, bw, sf, cr, txpwr)
                applyRadioConfig(config)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        btManager.dispose()
        frameProcessorJob?.cancel()
        serviceScope.cancel()
        _serviceStatus.value = "Stopped"
    }

    // ─── Frame Processing Pipeline ────────────────────────────────────────────

    private fun startFrameProcessor() {
        frameProcessorJob = serviceScope.launch {
            btManager.incomingFrames.collect { frameBytes ->
                processFrame(frameBytes)
            }
        }
    }

    private suspend fun processFrame(frameBytes: ByteArray) {
        try {
            val rnsPacket = RnsFrameDecoder.decode(frameBytes)

            if (rnsPacket == null) {
                processRawData(frameBytes)
                return
            }

            Log.v(TAG, "RNS packet: type=${rnsPacket.packetType} hops=${rnsPacket.hops} data=${rnsPacket.data.size}b")

            when (rnsPacket.packetType) {
                RnsFrameDecoder.PACKET_TYPE_ANNOUNCE -> processAnnounce(rnsPacket)
                RnsFrameDecoder.PACKET_TYPE_DATA     -> processDataPacket(rnsPacket)
                else -> { /* LINK_REQUEST, PROOF — ignore */ }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}", e)
        }
    }

    // ─── ANNOUNCE handling ────────────────────────────────────────────────────

    private fun processAnnounce(rnsPacket: RnsFrameDecoder.RnsPacket) {
        val hashHex = rnsPacket.destinationHash.joinToString("") { "%02x".format(it) }

        val node = RnsAnnounceParser.parse(
            destinationHash = hashHex,
            announceData    = rnsPacket.data,
            hops            = rnsPacket.hops
        ) ?: return

        // Merge into discovered nodes map (update lastSeen / count if already known)
        val current = _discoveredNodes.value.toMutableMap()
        val existing = current[hashHex]
        current[hashHex] = if (existing != null) {
            existing.copy(
                lastSeen      = System.currentTimeMillis(),
                hops          = rnsPacket.hops,
                announceCount = existing.announceCount + 1,
                displayName   = node.displayName ?: existing.displayName,
                aspect        = node.aspect ?: existing.aspect
            )
        } else {
            node
        }
        _discoveredNodes.value = current

        Log.i(TAG, "Node discovered: ${node.label} @ $hashHex (${rnsPacket.hops} hops)")
    }

    // ─── DATA packet handling ─────────────────────────────────────────────────

    private suspend fun processDataPacket(rnsPacket: RnsFrameDecoder.RnsPacket) {
        val lxmfMsg = LxmfMessageParser.parse(rnsPacket.data)

        if (lxmfMsg == null) {
            processRawData(rnsPacket.data)
            return
        }

        Log.i(TAG, "LXMF from ${lxmfMsg.sourceHashHex}: '${lxmfMsg.title}'")
        if (lxmfMsg.content.isNotBlank()) {
            processCsvContent(lxmfMsg.content)
        }
    }

    private suspend fun processRawData(data: ByteArray) {
        val text = String(data, Charsets.UTF_8).trim()
        if (text.isNotBlank() && !text.all { it.code < 32 && it != '\n' && it != '\r' }) {
            processCsvContent(text)
        }
    }

    private suspend fun processCsvContent(csvText: String) {
        val records = CsvParser.parsePayload(csvText)
        if (records.isEmpty()) return

        var newCount = 0
        for (record in records) {
            when (repository.insertRecord(record)) {
                is HarvestRepository.InsertResult.Success -> {
                    newCount++
                    _messageCount.value++
                    _lastMessageTime.value = System.currentTimeMillis()
                }
                is HarvestRepository.InsertResult.Duplicate -> _duplicateCount.value++
                is HarvestRepository.InsertResult.Error     -> {}
            }
        }

        if (newCount > 0) {
            updateNotification("Received $newCount new report(s) — Total: ${_messageCount.value}")
        }
    }

    // ─── Radio Configuration ──────────────────────────────────────────────────

    fun applyRadioConfig(config: RadioConfig) {
        serviceScope.launch {
            try {
                Log.i(TAG, "Applying radio config: ${config.frequencyMHz()} BW=${config.bandwidthKHz()} SF=${config.spreadingFactor}")
                val frames = config.buildAllFrames()
                for (frame in frames) {
                    btManager.sendRawFrame(frame)
                    delay(50) // small gap between config commands
                }
                _radioConfig.value = config
                Log.i(TAG, "Radio config applied")
                updateNotification("Radio configured: ${config.frequencyMHz()} SF${config.spreadingFactor}")
            } catch (e: Exception) {
                Log.e(TAG, "Radio config failed: ${e.message}")
            }
        }
    }

    // ─── Bluetooth State Observer ─────────────────────────────────────────────

    private fun observeBluetoothState() {
        serviceScope.launch {
            btManager.state.collect { state ->
                val status = when (state) {
                    is BluetoothRNodeManager.BtState.Idle        -> "Not connected"
                    is BluetoothRNodeManager.BtState.Connecting  -> "Connecting to ${state.device.name}..."
                    is BluetoothRNodeManager.BtState.Connected   -> "Connected: ${state.device.name}"
                    is BluetoothRNodeManager.BtState.Reconnecting -> "Reconnecting..."
                    is BluetoothRNodeManager.BtState.Error       -> "Error: ${state.message}"
                }
                _serviceStatus.value = status
                updateNotification(status)
            }
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "RNS Harvest Receiver", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service receiving harvest data via RNS/LXMF"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RNS Harvest Receiver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─── Public API (via binder) ──────────────────────────────────────────────

    fun getBluetoothState() = btManager.state
    fun connectToDevice(device: BluetoothDevice) = btManager.connect(device)
    fun disconnectDevice() = btManager.disconnect()
    fun clearDiscoveredNodes() { _discoveredNodes.value = emptyMap() }
}
