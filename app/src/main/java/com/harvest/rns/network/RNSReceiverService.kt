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
     * Generate (or restore) a persistent RNS destination address for this receiver.
     *
     * RNS identity/destination addresses as displayed in Sideband, rnsh, and RNode
     * tools are 32 hex characters (16 bytes). This is the full truncated hash of
     * the identity public key: SHA-256(public_key)[0:16].
     *
     * Since we don't have a full Ed25519 keypair here, we derive the address from
     * a stable random seed using the same 16-byte truncated SHA-256 approach, which
     * produces an address in exactly the format RNS users expect to enter into their
     * LXMF destination configuration.
     *
     * The address is generated once, persisted, and never changes across app restarts.
     * Delete app data to regenerate.
     */
    private fun generateOwnAddress() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_IDENTITY, null)

        // Migrate old 20-char address to 32-char format
        if (stored != null && stored.length == 32) {
            _ownAddress.value = stored
            Log.i(TAG, "Own address (restored): $stored")
            return
        }

        // Generate a new stable 32-char (16-byte) identity address
        // Use two rounds of SHA-256 to mix entropy from UUID + app package
        val seed   = UUID.randomUUID().toString() + packageName
        val round1 = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val round2 = MessageDigest.getInstance("SHA-256").digest(round1)

        // Take first 16 bytes → 32 hex chars (matches RNS address display format)
        val address = round2.take(16).joinToString("") { "%02x".format(it) }

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
            // Skip frames that are too short to be RNS (heartbeats, status frames)
            if (raw.size < 2) {
                val cmdByte = if (raw.isNotEmpty()) "cmd=0x${(raw[0].toInt() and 0xFF).toString(16)}" else "empty"
                _rawFrameLog.emit("[$cmdByte heartbeat/status]")
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

        // Strategy 1: try LXMF parse — harvester sent via Sideband/LXMF
        val lxmf = LxmfMessageParser.parse(pkt.data)
        if (lxmf != null && lxmf.content.isNotBlank()) {
            Log.i(TAG, "LXMF message from ${lxmf.sourceHashHex}: '${lxmf.title}' (${lxmf.content.length} chars)")
            processCsvContent(lxmf.content)
            return
        }

        // Strategy 2: try raw CSV on the stripped RNS payload
        Log.d(TAG, "LXMF parse failed or empty — trying raw CSV on ${pkt.data.size}b payload")
        if (tryRawCsv(pkt.data)) return

        // Strategy 3: try the entire raw frame as CSV (sender bypassed RNS framing)
        Log.d(TAG, "Payload CSV failed — no parseable data in this packet")
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
                val addr = _ownAddress.value
                if (addr.length < 20) return@launch

                // Convert hex address to bytes (use first 10 bytes for wire hash)
                val hashBytes = ByteArray(10) { i ->
                    addr.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }

                // Minimal announce payload:
                // 32 bytes Ed25519 pub key placeholder + 32 bytes X25519 placeholder
                // + app data "lxmf.delivery\u0000RNS Harvest Receiver"
                val appData = "lxmf.delivery\u0000RNS Harvest Receiver".toByteArray(Charsets.UTF_8)
                val keyPlaceholder = ByteArray(64) { 0x00 }
                val nameHash = ByteArray(10) { 0x00 }
                val randomBlob = ByteArray(10) { (it * 7).toByte() }
                val sigPlaceholder = ByteArray(64) { 0x00 }

                // Concatenate ByteArrays (Kotlin has no + for ByteArray)
                fun concat(vararg arrays: ByteArray): ByteArray {
                    val total = arrays.sumOf { it.size }
                    val out = ByteArray(total)
                    var pos = 0
                    for (a in arrays) { a.copyInto(out, pos); pos += a.size }
                    return out
                }

                val announceData = concat(keyPlaceholder, nameHash, randomBlob, appData, sigPlaceholder)

                // Build RNS packet bytes
                val header0 = 0x01.toByte()  // ANNOUNCE, broadcast, single dest
                val header1 = 0x00.toByte()  // 0 hops

                val packet = concat(byteArrayOf(header0, header1), hashBytes, announceData)

                // Wrap in CMD_INTERFACES KISS frame (0x25) — the RNode
                // requires this format to actually transmit the packet over LoRa.
                // Standard CMD_DATA (0x00) only works for direct serial comms.
                val kissFrame = RnsFrameDecoder.KissFramer.encodeInterfaceFrame(packet)
                btManager.sendRawFrame(kissFrame)

                Log.i(TAG, "Self-announce sent via CMD_INTERFACES (${addr.take(8)}…)")
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
