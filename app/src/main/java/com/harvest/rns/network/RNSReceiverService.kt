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
import java.security.MessageDigest
import java.util.UUID

class RNSReceiverService : Service() {

    companion object {
        private const val TAG = "RNSReceiverService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "rns_receiver_channel"
        private const val PREFS_NAME = "rns_prefs"
        private const val PREF_IDENTITY = "node_identity_hex"

        const val ACTION_CONNECT      = "com.harvest.rns.action.CONNECT"
        const val ACTION_DISCONNECT   = "com.harvest.rns.action.DISCONNECT"
        const val ACTION_APPLY_RADIO  = "com.harvest.rns.action.APPLY_RADIO"
        const val EXTRA_DEVICE        = "extra_bluetooth_device"

        // ── Singleton state ────────────────────────────────────────────────────
        private val _messageCount    = MutableStateFlow(0)
        private val _duplicateCount  = MutableStateFlow(0)
        private val _lastMessageTime = MutableStateFlow<Long>(0)
        private val _serviceStatus   = MutableStateFlow("Stopped")
        private val _discoveredNodes = MutableStateFlow<Map<String, DiscoveredNode>>(emptyMap())
        private val _radioConfig     = MutableStateFlow(RadioConfig())
        private val _ownAddress      = MutableStateFlow("Generating…")
        private val _rawFrameLog     = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 100)

        val messageCount:    StateFlow<Int>                        = _messageCount
        val duplicateCount:  StateFlow<Int>                        = _duplicateCount
        val lastMessageTime: StateFlow<Long>                       = _lastMessageTime
        val serviceStatus:   StateFlow<String>                     = _serviceStatus
        val discoveredNodes: StateFlow<Map<String, DiscoveredNode>> = _discoveredNodes
        val radioConfig:     StateFlow<RadioConfig>                = _radioConfig
        val ownAddress:      StateFlow<String>                     = _ownAddress
        val rawFrameLog:     SharedFlow<String>                    = _rawFrameLog
    }

    // ─── Binder ───────────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): RNSReceiverService = this@RNSReceiverService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ─── Members ──────────────────────────────────────────────────────────────
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
        generateOwnAddress()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for RNode…"))
        observeBluetoothState()
        startFrameProcessor()
        _serviceStatus.value = "Running — not connected"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DEVICE)
                device?.let { btManager.connect(it) }
            }
            ACTION_DISCONNECT -> btManager.disconnect()
            ACTION_APPLY_RADIO -> {
                val cfg = RadioConfig(
                    intent.getLongExtra("freq",  _radioConfig.value.frequencyHz),
                    intent.getIntExtra("bw",     _radioConfig.value.bandwidthHz),
                    intent.getIntExtra("sf",     _radioConfig.value.spreadingFactor),
                    intent.getIntExtra("cr",     _radioConfig.value.codingRate),
                    intent.getIntExtra("txpwr",  _radioConfig.value.txPower)
                )
                applyRadioConfig(cfg)
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

    // ─── Own address ──────────────────────────────────────────────────────────

    /**
     * Generate (or restore) a persistent RNS-style identity hash for this receiver app.
     *
     * In a full RNS stack the identity is an Ed25519 keypair and the address is
     * a truncated SHA-256 of the public key. Here we generate a stable random UUID
     * and derive a 10-byte (20 hex char) hash from it — the same format RNS uses
     * for destination addresses on the wire.
     *
     * This address is shown to the user so field harvesters can configure it as
     * their LXMF delivery destination. It persists across app restarts.
     */
    private fun generateOwnAddress() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_IDENTITY, null)
        if (stored != null) {
            _ownAddress.value = stored
            Log.i(TAG, "Own address (restored): $stored")
            return
        }

        // First run — generate a stable identity
        val uuid  = UUID.randomUUID().toString()
        val digest = MessageDigest.getInstance("SHA-256")
        val hash   = digest.digest(uuid.toByteArray())
        // Take first 10 bytes (80 bits) — same truncation as RNS
        val address = hash.take(10).joinToString("") { "%02x".format(it) }

        prefs.edit().putString(PREF_IDENTITY, address).apply()
        _ownAddress.value = address
        Log.i(TAG, "Own address (new): $address")
    }

    // ─── Frame pipeline ───────────────────────────────────────────────────────

    private fun startFrameProcessor() {
        frameProcessorJob = serviceScope.launch {
            btManager.incomingFrames.collect { processFrame(it) }
        }
    }

    private suspend fun processFrame(raw: ByteArray) {
        try {
            // Always emit to raw log (for the Nodes tab debug visibility)
            val hexPreview = raw.take(20).joinToString("") { "%02x".format(it) }
            _rawFrameLog.emit("${raw.size}b: $hexPreview${if (raw.size > 20) "…" else ""}")

            val pkt = RnsFrameDecoder.decode(raw)

            if (pkt == null) {
                // Decode failed — try raw CSV anyway (simple senders)
                tryRawCsv(raw)
                return
            }

            when (pkt.packetType) {
                RnsFrameDecoder.PACKET_TYPE_ANNOUNCE -> {
                    processAnnounce(pkt)
                }
                RnsFrameDecoder.PACKET_TYPE_DATA -> {
                    processDataPacket(pkt)
                }
                else -> {
                    // Unknown type — log and also try as announce in case
                    // our bit parsing is off (belt-and-suspenders)
                    Log.d(TAG, "Unknown packet type ${pkt.packetType} — also trying as announce")
                    processAnnounce(pkt)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame error: ${e.message}", e)
        }
    }

    // ─── ANNOUNCE ─────────────────────────────────────────────────────────────

    private fun processAnnounce(pkt: RnsFrameDecoder.RnsPacket) {
        val hashHex = pkt.destinationHash.joinToString("") { "%02x".format(it) }
        Log.i(TAG, "Processing ANNOUNCE from $hashHex (${pkt.hops} hops, ${pkt.data.size}b data)")

        // Parse app data — even if it fails, still register the node by its hash
        val node = RnsAnnounceParser.parse(hashHex, pkt.data, pkt.hops)
            ?: DiscoveredNode(hashHex, null, null, pkt.hops)

        val current = _discoveredNodes.value.toMutableMap()
        val existing = current[hashHex]
        current[hashHex] = if (existing != null) {
            existing.copy(
                lastSeen      = System.currentTimeMillis(),
                hops          = pkt.hops,
                announceCount = existing.announceCount + 1,
                displayName   = node.displayName ?: existing.displayName,
                aspect        = node.aspect ?: existing.aspect
            )
        } else {
            node
        }
        _discoveredNodes.value = current
        Log.i(TAG, "Node registry: ${current.size} node(s) — ${node.label} @ $hashHex")
    }

    // ─── DATA ─────────────────────────────────────────────────────────────────

    private suspend fun processDataPacket(pkt: RnsFrameDecoder.RnsPacket) {
        val lxmf = LxmfMessageParser.parse(pkt.data)
        if (lxmf != null && lxmf.content.isNotBlank()) {
            Log.i(TAG, "LXMF from ${lxmf.sourceHashHex}")
            processCsvContent(lxmf.content)
        } else {
            tryRawCsv(pkt.data)
        }
    }

    private suspend fun tryRawCsv(data: ByteArray) {
        val text = try { String(data, Charsets.UTF_8).trim() } catch (_: Exception) { return }
        if (text.length > 5 && text.any { it == ',' }) {
            processCsvContent(text)
        }
    }

    private suspend fun processCsvContent(csv: String) {
        val records = CsvParser.parsePayload(csv)
        if (records.isEmpty()) return
        var newCount = 0
        for (r in records) {
            when (repository.insertRecord(r)) {
                is HarvestRepository.InsertResult.Success   -> { newCount++; _messageCount.value++; _lastMessageTime.value = System.currentTimeMillis() }
                is HarvestRepository.InsertResult.Duplicate -> _duplicateCount.value++
                else -> {}
            }
        }
        if (newCount > 0) updateNotification("Received $newCount report(s) — Total: ${_messageCount.value}")
    }

    // ─── Radio config ─────────────────────────────────────────────────────────

    fun applyRadioConfig(config: RadioConfig) {
        serviceScope.launch {
            try {
                for (frame in config.buildAllFrames()) {
                    btManager.sendRawFrame(frame)
                    delay(60)
                }
                _radioConfig.value = config
                updateNotification("Radio: ${config.frequencyMHz()} SF${config.spreadingFactor}")
            } catch (e: Exception) {
                Log.e(TAG, "Radio config error: ${e.message}")
            }
        }
    }

    // ─── BT state ─────────────────────────────────────────────────────────────

    private fun observeBluetoothState() {
        serviceScope.launch {
            btManager.state.collect { state ->
                val s = when (state) {
                    is BluetoothRNodeManager.BtState.Idle        -> "Not connected"
                    is BluetoothRNodeManager.BtState.Connecting  -> "Connecting to ${state.device.name}…"
                    is BluetoothRNodeManager.BtState.Connected   -> "Connected: ${state.device.name}"
                    is BluetoothRNodeManager.BtState.Reconnecting -> "Reconnecting…"
                    is BluetoothRNodeManager.BtState.Error       -> "Error: ${state.message}"
                }
                _serviceStatus.value = s
                updateNotification(s)
            }
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "RNS Harvest Receiver", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RNS Harvest Receiver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun getBluetoothState()  = btManager.state
    fun connectToDevice(d: BluetoothDevice) = btManager.connect(d)
    fun disconnectDevice()   = btManager.disconnect()
    fun clearDiscoveredNodes() { _discoveredNodes.value = emptyMap() }
}
