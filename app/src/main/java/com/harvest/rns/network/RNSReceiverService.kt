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
import com.harvest.rns.network.rns.RnsIdentity
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
    private lateinit var identity: RnsIdentity
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var frameProcessorJob: Job? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        btManager  = BluetoothRNodeManager(applicationContext)
        repository = HarvestRepository(HarvestDatabase.getInstance(applicationContext).harvestDao())
        identity   = RnsIdentity.loadOrCreate(applicationContext)
        // Show the LXMF delivery destination hash — this is what senders must enter
        _ownAddress.value = identity.lxmfAddressHex
        Log.i(TAG, "Identity hash:      ${identity.addressHex}")
        Log.i(TAG, "LXMF delivery addr: ${identity.lxmfAddressHex}")
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

    // Identity is now managed by RnsIdentity class

    // ─── Frame pipeline ───────────────────────────────────────────────────────

    private fun startFrameProcessor() {
        frameProcessorJob = serviceScope.launch {
            btManager.incomingFrames.collect { processFrame(it) }
        }
    }

    private suspend fun processFrame(raw: ByteArray) {
        try {
            // Skip frames too short for RNS (heartbeats, telemetry, status)
            // 10-byte cmd=0x25 frames are RNode radio telemetry (RSSI/SNR/freq), not RNS packets
            if (raw.size < 12) {
                val hexPreview = raw.take(10).joinToString("") { "%02x".format(it) }
                _rawFrameLog.emit("[status ${raw.size}b: $hexPreview]")
                return
            }

            // Emit to raw log with full hex
            val hexPreview = raw.take(24).joinToString("") { "%02x".format(it) }
            val entry = "${raw.size}b: $hexPreview${if (raw.size > 24) "…" else ""}"
            _rawFrameLog.emit(entry)
            Log.d(TAG, "Frame $entry")

            val pkt = RnsFrameDecoder.decode(raw)

            if (pkt == null) {
                Log.d(TAG, "RNS decode failed (${raw.size}b) — trying as raw CSV")
                tryRawCsv(raw)
                return
            }

            Log.d(TAG, "RNS pkt type=${pkt.packetType} data=${pkt.data.size}b")

            when (pkt.packetType) {
                RnsFrameDecoder.PACKET_TYPE_ANNOUNCE -> processAnnounce(pkt)
                RnsFrameDecoder.PACKET_TYPE_DATA     -> processDataPacket(pkt)
                else -> {
                    // Unknown type — try both DATA and ANNOUNCE handling
                    Log.d(TAG, "Unknown pkt type=${pkt.packetType}, trying DATA then ANNOUNCE")
                    processDataPacket(pkt)   // try CSV/LXMF first
                    processAnnounce(pkt)     // also register as node
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
        Log.d(TAG, "processDataPacket: ${pkt.data.size}b payload")

        // Strategy 1: try raw CSV FIRST — if it starts with printable ASCII and has commas
        // it is plaintext CSV sent directly (bypassing LXMF), try this before LXMF
        // to avoid the compact LXMF parser stripping the first 10 bytes as a fake "src hash"
        if (looksLikeCsv(pkt.data)) {
            Log.d(TAG, "Payload looks like raw CSV — trying directly")
            if (tryRawCsv(pkt.data)) return
        }

        // Strategy 2: try decrypting with our identity keypair (RNS encrypted message)
        val decrypted = identity.decrypt(pkt.data)
        if (decrypted != null) {
            Log.i(TAG, "Decrypted ${pkt.data.size}b → ${decrypted.size}b")
            val lxmf = LxmfMessageParser.parse(decrypted)
            if (lxmf != null && lxmf.content.isNotBlank()) {
                processCsvContent(lxmf.content); return
            }
            if (tryRawCsv(decrypted)) return
        }

        // Strategy 3: try LXMF parse (handles ratchet-encrypted and compact LXMF)
        val lxmf = LxmfMessageParser.parse(pkt.data)
        if (lxmf != null && lxmf.content.isNotBlank()) {
            Log.i(TAG, "LXMF from ${lxmf.sourceHashHex}: ${lxmf.content.length} chars")
            processCsvContent(lxmf.content)
            return
        }

        // Strategy 4: last resort raw CSV on full payload
        if (tryRawCsv(pkt.data)) return

        Log.d(TAG, "No parseable data in ${pkt.data.size}b packet")
    }

    /** Returns true if the payload looks like a plaintext CSV (not binary/encrypted) */
    private fun looksLikeCsv(data: ByteArray): Boolean {
        if (data.size < 4) return false
        // Check first 20 bytes — all must be printable ASCII
        val check = data.take(minOf(20, data.size))
        if (!check.all { b -> val i = b.toInt() and 0xFF; i in 32..126 || i == 10 || i == 13 }) return false
        // Must contain at least one comma
        return data.any { it == ','.code.toByte() }
    }

    /**
     * Try to parse bytes as raw UTF-8 CSV text.
     * Returns true if at least one record was successfully parsed.
     */
    private suspend fun tryRawCsv(data: ByteArray): Boolean {
        val text = try {
            String(data, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            return false
        }

        Log.d(TAG, "tryRawCsv: '${text.take(80).replace("\n", "↵")}'")

        if (text.length < 3 || !text.any { it == ',' }) {
            Log.d(TAG, "tryRawCsv: no commas found, not CSV")
            return false
        }

        val records = CsvParser.parsePayload(text)
        if (records.isEmpty()) {
            Log.d(TAG, "tryRawCsv: CsvParser returned 0 records")
            return false
        }

        processCsvContent(text)
        return true
    }

    private suspend fun processCsvContent(csv: String) {
        val records = CsvParser.parsePayload(csv)
        Log.i(TAG, "processCsvContent: ${records.size} record(s) from ${csv.length} chars")
        if (records.isEmpty()) return

        var newCount = 0
        var dupCount = 0
        for (r in records) {
            when (repository.insertRecord(r)) {
                is HarvestRepository.InsertResult.Success -> {
                    newCount++
                    _messageCount.value++
                    _lastMessageTime.value = System.currentTimeMillis()
                    Log.i(TAG, "Saved: ${r.harvesterId}/${r.blockId} ripe=${r.ripeBunches}")
                }
                is HarvestRepository.InsertResult.Duplicate -> {
                    dupCount++
                    _duplicateCount.value++
                    Log.d(TAG, "Duplicate: ${r.externalId}")
                }
                is HarvestRepository.InsertResult.Error -> {
                    Log.e(TAG, "DB insert error for ${r.externalId}")
                }
            }
        }
        Log.i(TAG, "processCsvContent done: $newCount new, $dupCount duplicate")
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
                    is BluetoothRNodeManager.BtState.Connected   -> {
                        // Announce ourselves on the network so other nodes know we exist
                        delay(1000) // wait for radio to stabilise
                        sendSelfAnnounce()
                        "Connected: ${state.device.name}"
                    }
                    is BluetoothRNodeManager.BtState.Reconnecting -> "Reconnecting…"
                    is BluetoothRNodeManager.BtState.Error       -> "Error: ${state.message}"
                }
                _serviceStatus.value = s
                updateNotification(s)
            }
        }
    }

    /**
     * Send an RNS ANNOUNCE packet so other nodes can discover this receiver
     * and route LXMF messages to it.
     *
     * RNS ANNOUNCE structure (inside KISS DATA frame):
     *   Header byte 0: propagation=BROADCAST, dest=SINGLE, type=ANNOUNCE
     *                  = 0b00_00_00_01 = 0x01
     *   Header byte 1: hops = 0
     *   Dest hash:     our 10-byte address (first 10 bytes of our 16-byte address)
     *   Data:          minimal announce payload (public key placeholder + app data)
     *
     * The announce tells the network: "this destination hash exists at this node".
     * Other nodes' RNS stacks will accept LXMF messages addressed to this hash.
     */
    private fun sendSelfAnnounce() {
        serviceScope.launch {
            try {
                // Use LXMF delivery destination hash (not raw identity hash)
                // This is what RNS.Destination(identity, SINGLE, "lxmf","delivery") produces
                val hashBytes = identity.lxmfDeliveryHash.copyOf(10)

                // Build announce data with REAL Ed25519 + X25519 public keys
                // This allows senders to encrypt messages to us correctly
                val appData = "lxmf.delivery\u0000RNS Harvest Receiver".toByteArray(Charsets.UTF_8)
                val announceData = identity.buildAnnounceData(appData)
                val sigPlaceholder = ByteArray(64) { 0x00 }  // sig not verified by receiver

                fun concat(vararg arrays: ByteArray): ByteArray {
                    val total = arrays.sumOf { it.size }
                    val out = ByteArray(total)
                    var pos = 0
                    for (a in arrays) { a.copyInto(out, pos); pos += a.size }
                    return out
                }

                val fullAnnounceData = concat(announceData, sigPlaceholder)

                // RNS ANNOUNCE header: type=ANNOUNCE(0x01)
                val header0 = 0x01.toByte()
                val header1 = 0x00.toByte()  // 0 hops

                val packet = concat(byteArrayOf(header0, header1), hashBytes, fullAnnounceData)

                val kissFrame = RnsFrameDecoder.KissFramer.encodeInterfaceFrame(packet)
                btManager.sendRawFrame(kissFrame)

                Log.i(TAG, "Self-announce sent with real keys: ${identity.addressHex.take(16)}…")
            } catch (e: Exception) {
                Log.e(TAG, "Announce error: ${e.message}")
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
