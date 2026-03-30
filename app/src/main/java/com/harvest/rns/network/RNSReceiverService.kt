package com.harvest.rns.network

import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.harvest.rns.data.db.HarvestDatabase
import com.harvest.rns.data.model.ConnectionStatus
import com.harvest.rns.data.model.DiscoveredNode
import com.harvest.rns.data.model.RadioConfig
import com.harvest.rns.data.repository.HarvestRepository
import com.harvest.rns.network.bluetooth.BtTcpBridge
import com.harvest.rns.ui.main.MainActivity
import com.harvest.rns.utils.CsvParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.UUID

class RNSReceiverService : Service() {

    companion object {
        private const val TAG             = "RNSReceiverService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "rns_receiver_channel"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val ACTION_CONNECT     = "com.harvest.rns.action.CONNECT"
        const val ACTION_DISCONNECT  = "com.harvest.rns.action.DISCONNECT"
        const val ACTION_APPLY_RADIO = "com.harvest.rns.action.APPLY_RADIO"
        const val EXTRA_DEVICE       = "extra_bluetooth_device"

        private val _messageCount    = MutableStateFlow(0)
        private val _duplicateCount  = MutableStateFlow(0)
        private val _lastMessageTime = MutableStateFlow<Long>(0)
        private val _serviceStatus   = MutableStateFlow("Stopped")
        private val _discoveredNodes = MutableStateFlow<Map<String, DiscoveredNode>>(emptyMap())
        private val _radioConfig     = MutableStateFlow(RadioConfig())
        private val _ownAddress      = MutableStateFlow("Starting…")
        private val _rawFrameLog     = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 100)

        val messageCount:    StateFlow<Int>                         = _messageCount
        val duplicateCount:  StateFlow<Int>                         = _duplicateCount
        val lastMessageTime: StateFlow<Long>                        = _lastMessageTime
        val serviceStatus:   StateFlow<String>                      = _serviceStatus
        val discoveredNodes: StateFlow<Map<String, DiscoveredNode>> = _discoveredNodes
        val radioConfig:     StateFlow<RadioConfig>                 = _radioConfig
        val ownAddress:      StateFlow<String>                      = _ownAddress
        val rawFrameLog:     SharedFlow<String>                     = _rawFrameLog
    }

    inner class LocalBinder : Binder() {
        fun getService(): RNSReceiverService = this@RNSReceiverService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private lateinit var repository: HarvestRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bridge       = BtTcpBridge()
    private var btSocket: BluetoothSocket? = null
    private var pyModule: PyObject? = null

    override fun onCreate() {
        super.onCreate()
        repository = HarvestRepository(
            HarvestDatabase.getInstance(applicationContext).harvestDao()
        )
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initialising…"))
        initialisePython()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT    -> intent.getParcelableExtra<BluetoothDevice>(EXTRA_DEVICE)
                                     ?.let { connectBluetooth(it) }
            ACTION_DISCONNECT -> disconnectBluetooth()
            ACTION_APPLY_RADIO -> applyRadioConfig()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge.stop()
        try { btSocket?.close() } catch (_: Exception) {}
        serviceScope.cancel()
    }

    // ── Python init ───────────────────────────────────────────────────────────

    private fun initialisePython() {
        serviceScope.launch {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }
                pyModule = Python.getInstance().getModule("rns_harvest")

                val addr = pyModule!!
                    .callAttr("start_rns", applicationContext.filesDir.absolutePath, PythonCallback())
                    .toString()

                _ownAddress.value = addr
                _serviceStatus.value = "Ready — connect RNode"
                updateNotification("Ready — connect RNode via Bluetooth")
                Log.i(TAG, "RNS ready. Address: $addr")

            } catch (e: Exception) {
                Log.e(TAG, "Python init error: ${e.message}", e)
                _serviceStatus.value = "Init error: ${e.message}"
            }
        }
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────────

    private fun connectBluetooth(device: BluetoothDevice) {
        serviceScope.launch {
            _serviceStatus.value = "Connecting to ${device.name ?: device.address}…"
            try {
                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                sock.connect()
                btSocket = sock
                Log.i(TAG, "BT connected: ${device.name}")

                bridge.start(sock.inputStream, sock.outputStream)
                delay(600) // let TCP server bind

                val cfg = _radioConfig.value
                val result = pyModule?.callAttr(
                    "inject_rnode",
                    cfg.frequencyHz.toLong(),
                    cfg.bandwidthHz.toLong(),
                    cfg.txPower,
                    cfg.spreadingFactor,
                    cfg.codingRate
                )?.toString() ?: "no module"

                if (result == "ONLINE") {
                    _serviceStatus.value = "Connected — ${device.name ?: device.address}"
                    updateNotification("Connected: ${device.name ?: device.address}")
                } else {
                    _serviceStatus.value = "RNode error: $result"
                    Log.e(TAG, "inject_rnode: $result")
                }

            } catch (e: IOException) {
                Log.e(TAG, "BT connect failed: ${e.message}")
                _serviceStatus.value = "Connection failed"
                bridge.stop()
            }
        }
    }

    private fun disconnectBluetooth() {
        bridge.stop()
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
        _serviceStatus.value = "Disconnected"
        updateNotification("Disconnected")
    }

    private fun applyRadioConfig() {
        if (!bridge.isRunning) return
        val cfg = _radioConfig.value
        serviceScope.launch {
            pyModule?.callAttr(
                "inject_rnode",
                cfg.frequencyHz.toLong(),
                cfg.bandwidthHz.toLong(),
                cfg.txPower,
                cfg.spreadingFactor,
                cfg.codingRate
            )
        }
    }

    // ── Python callback ───────────────────────────────────────────────────────

    inner class PythonCallback {

        fun onCsvReceived(senderHash: String, content: String) {
            Log.i(TAG, "CSV from ${senderHash.take(8)}: ${content.take(80)}")
            serviceScope.launch { processCsvContent(content, senderHash) }
        }

        fun onAnnounceReceived(destHash: String, displayName: String) {
            val existing = _discoveredNodes.value[destHash]
            val node = DiscoveredNode(
                destinationHash = destHash,
                displayName     = displayName.ifBlank { null },
                aspect          = "lxmf.delivery",
                hops            = 0,
                lastSeen        = System.currentTimeMillis(),
                announceCount   = (existing?.announceCount ?: 0) + 1
            )
            _discoveredNodes.value = _discoveredNodes.value + (destHash to node)
            serviceScope.launch {
                _rawFrameLog.emit("ANNOUNCE $destHash ${displayName.take(20)}")
            }
        }
    }

    // ── CSV processing ────────────────────────────────────────────────────────

    private suspend fun processCsvContent(content: String, sender: String) {
        val records = CsvParser.parsePayload(content)
        if (records.isEmpty()) {
            Log.d(TAG, "No records in: ${content.take(60)}")
            _rawFrameLog.emit("Received but no CSV parsed from ${sender.take(8)}")
            return
        }
        var saved = 0; var dupes = 0
        for (record in records) {
            when (repository.insertRecord(record)) {
                is HarvestRepository.InsertResult.Success -> {
                    saved++
                    _messageCount.value++
                    _lastMessageTime.value = System.currentTimeMillis()
                    Log.i(TAG, "Saved: ${record.harvesterId}/${record.blockId} total=${record.ripeBunches + record.emptyBunches}")
                }
                is HarvestRepository.InsertResult.Duplicate -> {
                    dupes++
                    Log.d(TAG, "Duplicate: ${record.harvesterId}/${record.blockId}")
                }
                is HarvestRepository.InsertResult.Error -> {
                    Log.e(TAG, "Insert error for ${record.harvesterId}")
                }
            }
        }
        _duplicateCount.value += dupes
        _rawFrameLog.emit("Saved $saved record(s) from ${sender.take(8)} (dupes=$dupes)")
        if (saved > 0) updateNotification("Received from ${records.first().harvesterId}")
    }

    // ── Nickname ──────────────────────────────────────────────────────────────

    fun getNickname(hash: String): String? =
        getSharedPreferences("node_nicknames", Context.MODE_PRIVATE).getString(hash, null)

    fun setNickname(hash: String, nick: String) =
        getSharedPreferences("node_nicknames", Context.MODE_PRIVATE)
            .edit().putString(hash, nick.ifBlank { null }).apply()

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "RNS Receiver", NotificationManager.IMPORTANCE_LOW)
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RNS Harvest Receiver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
            )
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
}
