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
import com.harvest.rns.data.repository.HarvestRepository
import com.harvest.rns.network.bluetooth.BluetoothRNodeManager
import com.harvest.rns.network.lxmf.LxmfMessageParser
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
 * 4. Broadcasts status updates to the UI via LiveData/StateFlow
 */
class RNSReceiverService : Service() {

    companion object {
        private const val TAG = "RNSReceiverService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "rns_receiver_channel"

        const val ACTION_CONNECT    = "com.harvest.rns.action.CONNECT"
        const val ACTION_DISCONNECT = "com.harvest.rns.action.DISCONNECT"
        const val EXTRA_DEVICE      = "extra_bluetooth_device"

        // Singleton state accessible from ViewModels
        private val _messageCount     = MutableStateFlow(0)
        private val _duplicateCount   = MutableStateFlow(0)
        private val _lastMessageTime  = MutableStateFlow<Long>(0)
        private val _serviceStatus    = MutableStateFlow("Stopped")

        val messageCount:    StateFlow<Int>    = _messageCount
        val duplicateCount:  StateFlow<Int>    = _duplicateCount
        val lastMessageTime: StateFlow<Long>   = _lastMessageTime
        val serviceStatus:   StateFlow<String> = _serviceStatus
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
                device?.let {
                    btManager.connect(it)
                    Log.i(TAG, "Connect requested for ${it.address}")
                }
            }
            ACTION_DISCONNECT -> {
                btManager.disconnect()
                Log.i(TAG, "Disconnect requested")
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
        Log.i(TAG, "Service destroyed")
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
            // Layer 1: Decode RNS packet
            val rnsPacket = RnsFrameDecoder.decode(frameBytes)

            if (rnsPacket == null) {
                Log.d(TAG, "Could not decode RNS frame (${frameBytes.size}b) — trying raw LXMF")
                processRawData(frameBytes)
                return
            }

            Log.v(TAG, "RNS packet: type=${rnsPacket.packetType} ctx=${rnsPacket.context} data=${rnsPacket.data.size}b")

            // Only process DATA packets
            if (rnsPacket.packetType != RnsFrameDecoder.PACKET_TYPE_DATA) {
                Log.v(TAG, "Skipping non-data packet type=${rnsPacket.packetType}")
                return
            }

            // Layer 2: Parse LXMF message from packet data
            val lxmfMsg = LxmfMessageParser.parse(rnsPacket.data)

            if (lxmfMsg == null) {
                Log.d(TAG, "Not an LXMF message — trying as raw CSV")
                processRawData(rnsPacket.data)
                return
            }

            Log.i(TAG, "LXMF message from ${lxmfMsg.sourceHashHex}: '${lxmfMsg.title}'")

            // Layer 3: Parse CSV content
            if (lxmfMsg.content.isNotBlank()) {
                processCsvContent(lxmfMsg.content)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}", e)
        }
    }

    /**
     * Fallback: treat raw bytes as UTF-8 CSV (for simple senders that omit RNS/LXMF wrapping).
     */
    private suspend fun processRawData(data: ByteArray) {
        val text = String(data, Charsets.UTF_8).trim()
        if (text.isNotBlank() && !text.all { it.code < 32 && it != '\n' && it != '\r' }) {
            processCsvContent(text)
        }
    }

    private suspend fun processCsvContent(csvText: String) {
        val records = CsvParser.parsePayload(csvText)

        if (records.isEmpty()) {
            Log.d(TAG, "No parseable records in content:\n$csvText")
            return
        }

        Log.i(TAG, "Parsed ${records.size} record(s) from message")
        var newCount = 0

        for (record in records) {
            when (val result = repository.insertRecord(record)) {
                is HarvestRepository.InsertResult.Success -> {
                    newCount++
                    _messageCount.value++
                    _lastMessageTime.value = System.currentTimeMillis()
                    Log.i(TAG, "Stored new record: ${record.externalId} from ${record.harvesterId}")
                }
                is HarvestRepository.InsertResult.Duplicate -> {
                    _duplicateCount.value++
                    Log.d(TAG, "Duplicate ignored: ${record.externalId}")
                }
                is HarvestRepository.InsertResult.Error -> {
                    Log.e(TAG, "Store error: ${result.reason}")
                }
            }
        }

        if (newCount > 0) {
            updateNotification("Received $newCount new report(s) — Total: ${_messageCount.value}")
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
                CHANNEL_ID,
                "RNS Harvest Receiver",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service receiving harvest data via RNS/LXMF"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RNS Harvest Receiver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_antenna)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─── Public Accessors for Binding ─────────────────────────────────────────

    fun getBluetoothState() = btManager.state

    fun connectToDevice(device: BluetoothDevice) = btManager.connect(device)

    fun disconnectDevice() = btManager.disconnect()
}
